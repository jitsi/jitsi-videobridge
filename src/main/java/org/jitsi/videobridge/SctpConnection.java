/*
 * Copyright @ 2015 Atlassian Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jitsi.videobridge;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.logging.*;

import org.jitsi.xmpp.extensions.jingle.*;

import org.ice4j.socket.*;
import org.jitsi.impl.neomedia.transform.dtls.*;
import org.jitsi.impl.osgi.framework.*;
import org.jitsi.sctp4j.*;
import org.jitsi.service.configuration.*;
import org.jitsi.service.libjitsi.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.service.packetlogging.*;
import org.jitsi.util.*;
import org.jitsi.utils.queue.*;
import org.jitsi.utils.concurrent.ExecutorUtils;
import org.jitsi.utils.logging.Logger; // Disambiguation.

/**
 * Class is a transport layer for WebRTC data channels. It consists of SCTP
 * connection running on top of ICE/DTLS layer. Manages WebRTC data channels.
 * See http://tools.ietf.org/html/draft-ietf-rtcweb-data-channel-08 for more
 * info on WebRTC data channels.
 * <p>
 * Control protocol:
 * http://tools.ietf.org/html/draft-ietf-rtcweb-data-protocol-03
 * </p>
 *
 * FIXME handle closing of data channels(SCTP stream reset)
 *
 * @author Pawel Domas
 * @author Lyubomir Marinov
 * @author Boris Grozev
 */
public class SctpConnection
    extends Channel
    implements SctpDataCallback,
               SctpSocket.NotificationListener
{
    /**
     * Generator used to track debug IDs.
     */
    private static int debugIdGen = -1;

    /**
     * DTLS transport buffer size.
     * Note: randomly chosen.
     */
    private static final int DTLS_BUFFER_SIZE = 2048;

    /**
     * Switch used for debugging SCTP traffic purposes.
     * FIXME to be removed
     */
    private static final boolean LOG_SCTP_PACKETS = false;

    /**
     * The {@link Logger} used by the {@link SctpConnection} class to
     * print debug information. Note that instances should use {@link #logger}
     * instead.
     */
    private static final Logger classLogger
        = Logger.getLogger(SctpConnection.class);

    /**
     * Message type used to acknowledge WebRTC data channel allocation on SCTP
     * stream ID on which <tt>MSG_OPEN_CHANNEL</tt> message arrives.
     */
    private static final int MSG_CHANNEL_ACK = 0x2;

    private static final byte[] MSG_CHANNEL_ACK_BYTES = { MSG_CHANNEL_ACK };

    /**
     * Message with this type sent over control PPID in order to open new WebRTC
     * data channel on SCTP stream ID that this message is sent.
     */
    private static final int MSG_OPEN_CHANNEL = 0x3;

    /**
     * SCTP transport buffer size.
     */
    private static final int SCTP_BUFFER_SIZE = DTLS_BUFFER_SIZE - 13;

    /**
     * The pool of <tt>Thread</tt>s which run <tt>SctpConnection</tt>s.
     */
    private static final ExecutorService threadPool
        = ExecutorUtils.newCachedThreadPool(
                true,
                SctpConnection.class.getName());

    /**
     * Payload protocol id that identifies binary data in WebRTC data channel.
     */
    static final int WEB_RTC_PPID_BIN = 53;

    /**
     * Payload protocol id for control data. Used for <tt>WebRtcDataStream</tt>
     * allocation.
     */
    static final int WEB_RTC_PPID_CTRL = 50;

    /**
     * Payload protocol id that identifies text data UTF8 encoded in WebRTC data
     * channels.
     */
    static final int WEB_RTC_PPID_STRING = 51;

    /**
     * The <tt>String</tt> value of the <tt>Protocol</tt> field of the
     * <tt>DATA_CHANNEL_OPEN</tt> message.
     */
    private static final String WEBRTC_DATA_CHANNEL_PROTOCOL
        = "http://jitsi.org/protocols/colibri";

    private static synchronized int generateDebugId()
    {
        debugIdGen += 2;
        return debugIdGen;
    }

    /**
     * Indicates if we have accepted incoming connection.
     */
    private boolean acceptedIncomingConnection;

    /**
     * Indicates whether the STCP association is ready and has not been ended by
     * a subsequent state change.
     */
    private boolean assocIsUp;

    /**
     * Data channels mapped by SCTP stream identified(sid).
     */
    private final Map<Integer,WebRtcDataStream> channels = new HashMap<>();

    /**
     * Debug ID used to distinguish SCTP sockets in packet logs.
     */
    private final int debugId;

    /**
     * The <tt>AsyncExecutor</tt> which is used to order incoming SCTP packet's
     * processing and dispatching of the {@link SctpConnection} related events
     * like the data channel opened event.
     *
     * The timeout of <tt>15</tt> is chosen to be in
     * accord with the time it takes to expire a <tt>Channel</tt>.
     */
    private final AsyncExecutor<Runnable> sctpDispatcher
        = new AsyncExecutor<>(15, TimeUnit.MILLISECONDS);

    /**
     * List of <tt>WebRtcDataStreamListener</tt>s that will be notified whenever
     * new WebRTC data channel is opened.
     */
    private final List<WebRtcDataStreamListener> listeners = new ArrayList<>();

    /**
     * There's the SCTP socket accept task running as the first task in
     * the {@link #sctpDispatcher} which blocks any SCTP packet processing until
     * the SCTP socket is writeable. This lock is used to notify the accept
     * task about the SCTP_COMM_UP notification which signals that a SCTP
     * association has been established. It only matters for the case where it
     * has not occurred before the sctpSocket.accept() returned.
     */
    private final Object isReadyWaitLock = new Object();

    /**
     * Remote SCTP port.
     */
    private final int remoteSctpPort;

    /**
     * <tt>SctpSocket</tt> used for SCTP transport.
     */
    private SctpSocket sctpSocket;

    /**
     * Flag prevents from starting this connection multiple times from
     * {@link #maybeStartStream()}.
     */
    private boolean started;

    /**
     * The object used to synchronize access to fields specific to this
     * {@link SctpConnection}. We use it to avoid synchronizing on {@code this}
     * which is a {@link Channel}.
     */
    private final Object syncRoot = new Object();

    /**
     * The {@link PacketQueue} instance in which we place packets coming from
     * the SCTP stack which are to be sent via {@link #transformer}.
     */
    private final RawPacketQueue packetQueue;

    /**
     * The {@link DtlsPacketTransformer} instance which we use to transport
     * SCTP packets.
     */
    private DtlsPacketTransformer transformer = null;

    /**
     * The instance which we use to handle packets read from
     * {@link #packetQueue}.
     */
    private final Handler handler = new Handler();

    /**
     * The {@link Logger} to be used by this instance to print debug
     * information.
     */
    private final Logger logger;

    /**
     * Initializes a new <tt>SctpConnection</tt> instance.
     *
     * @param id the string identifier of this connection instance
     * @param content the <tt>Content</tt> which is initializing the new
     * instance
     * @param endpoint the <tt>Endpoint</tt> of newly created instance
     * @param remoteSctpPort the SCTP port used by remote peer
     * @param channelBundleId the ID of the channel-bundle this
     * <tt>SctpConnection</tt> is to be a part of (or <tt>null</tt> if no it is
     * not to be a part of a channel-bundle).
     */
    public SctpConnection(
            String id,
            Content content,
            AbstractEndpoint endpoint,
            int remoteSctpPort,
            String channelBundleId,
            Boolean initiator)
    {
        super(content,
              id,
              channelBundleId,
              IceUdpTransportPacketExtension.NAMESPACE,
              initiator);

        logger
            = Logger.getLogger(classLogger, content.getConference().getLogger());
        setEndpoint(endpoint);
        packetQueue
            = new RawPacketQueue(
                false,
                getClass().getSimpleName() + "-" + endpoint.getID(),
                handler);

        this.remoteSctpPort = remoteSctpPort;
        debugId = generateDebugId();
    }

    /**
     * Adds <tt>WebRtcDataStreamListener</tt> to the list of listeners.
     *
     * @param listener the <tt>WebRtcDataStreamListener</tt> to be added to the
     * listeners list.
     */
    public void addChannelListener(WebRtcDataStreamListener listener)
    {
        if (listener == null)
        {
            throw new NullPointerException("listener");
        }
        else
        {
            synchronized (listeners)
            {
                if (!listeners.contains(listener))
                    listeners.add(listener);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void closeStream()
    {
        synchronized (syncRoot)
        {
            assocIsUp = false;
            acceptedIncomingConnection = false;
            packetQueue.close();
            if (sctpSocket != null)
            {
                sctpSocket.close();
                sctpSocket = null;
            }
        }
    }

    /**
     * {@inheritDoc}
     *
     * Creates a <tt>TransportManager</tt> instance suitable for an
     * <tt>SctpConnection</tt> (e.g. with 1 component only).
     */
    protected TransportManager createTransportManager(String xmlNamespace)
            throws IOException
    {
        if (IceUdpTransportPacketExtension.NAMESPACE.equals(xmlNamespace))
        {
            Content content = getContent();

            return
                new IceUdpTransportManager(
                        content.getConference(),
                        isInitiator(),
                        1 /* numComponents */,
                        content.getName());
        }
        else if (RawUdpTransportPacketExtension.NAMESPACE.equals(xmlNamespace))
        {
            //TODO Support RawUdp once RawUdpTransportManager is updated.
//            return new RawUdpTransportManager(this);
            throw new IllegalArgumentException(
                    "Unsupported Jingle transport " + xmlNamespace);
        }
        else
        {
            throw new IllegalArgumentException(
                    "Unsupported Jingle transport " + xmlNamespace);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean expire()
    {
        if (!super.expire())
        {
            // Already expired.
            return false;
        }

        sctpDispatcher.shutdown();

        return true;
    }

    /**
     * Allows to loop over available WebRTC data channels instances.
     *
     * @param action the action to be executed for each data channel's instance.
     */
    public void forEachDataStream(Consumer<WebRtcDataStream> action)
    {
        ArrayList<WebRtcDataStream> streams;

        synchronized (syncRoot)
        {
            streams = new ArrayList<>(channels.values());
        }

        streams.forEach(action);
    }

    /**
     * Gets the <tt>WebRtcDataStreamListener</tt>s added to this instance.
     *
     * @return the <tt>WebRtcDataStreamListener</tt>s added to this instance or
     * <tt>null</tt> if there are no <tt>WebRtcDataStreamListener</tt>s added to
     * this instance
     */
    private WebRtcDataStreamListener[] getChannelListeners()
    {
        WebRtcDataStreamListener[] ls;

        synchronized (listeners)
        {
            if (listeners.isEmpty())
            {
                ls = null;
            }
            else
            {
                ls
                    = listeners.toArray(
                            new WebRtcDataStreamListener[listeners.size()]);
            }
        }
        return ls;
    }

    /**
     * Select a WebRTC data channel that should be used for writing by default.
     *
     * The strategy is try to select client's data channel with the highest SID
     * number first. A data channel with the highest SID will usually be the
     * most recent one, because the WebRTC stack tries to allocate SIDs in
     * order.
     *
     * The distinction between client's opened channels vs JVB opened channels
     * comes from the fact that the RFC requires the DTLS initiator to use even
     * SID numbers and the responder to use odd numbers.
     *
     * @return a writeable <tt>WebRtcDataStream</tt> if it's ready or
     * <tt>null</tt> otherwise.
     */
    public WebRtcDataStream getDefaultDataStream()
    {
        synchronized (syncRoot)
        {
            if (sctpSocket != null)
            {
                // Tries to elect a stream with the highest client SID
                WebRtcDataStream highestClientSid
                    = channels.values()
                        .stream()
                        .filter(
                            s -> isInitiator()
                                ? s.getSid() % 2 == 1
                                : s.getSid() % 2 == 0)
                        .max(Comparator.comparingInt(WebRtcDataStream::getSid))
                        .orElse(null);

                if (highestClientSid != null)
                {
                    return highestClientSid;
                }

                // Return the highest SID (probably will be the JVB's one)
                return channels.values()
                    .stream()
                    .max(Comparator.comparingInt(WebRtcDataStream::getSid))
                    .orElse(null);
            }

            return null;
        }
    }

    /**
     * Returns <tt>true</tt> if this <tt>SctpConnection</tt> is connected to the
     * remote peer and operational.
     *
     * @return <tt>true</tt> if this <tt>SctpConnection</tt> is connected to the
     * remote peer and operational
     */
    public boolean isReady()
    {
        return assocIsUp && acceptedIncomingConnection;
    }

    /**
     * This method will try to open a default WebRTC data channel if it hasn't
     * been opened by the client already.
     *
     * When the client is opening the channel, what's been observed is that
     * there are incoming SCTP packets with MSG_OPEN_CHANNEL queued onto
     * the {@link #sctpDispatcher}, before the call to sctpSocket.accept
     * returns. Those packets will not be processed until it does, because
     * the {@link #sctpDispatcher} is a single threaded executor which
     * preserves the order. It's important to note that the call to this method
     * is queued after those packets, because it happens after sctpSocket.accept
     * returned. This should make the check for opened channels reliable enough
     * to prevent from opening unnecessary channels most of the time.
     */
    private void maybeOpenDefaultWebRTCDataChannel()
    {
        boolean openChannel;

        synchronized (syncRoot)
        {
            openChannel
                = !isExpired() && sctpSocket != null && channels.size() == 0;
        }

        if (openChannel)
        {
            openDefaultWebRTCDataChannel();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void maybeStartStream()
    {
        // connector
        final StreamConnector connector = getStreamConnector();

        if (connector == null)
            return;

        synchronized (syncRoot)
        {
            if (started)
                return;

            threadPool.execute(
                () -> {
                    try
                    {
                        Sctp.init();

                        runOnDtlsTransport(connector);
                    }
                    catch (IOException e)
                    {
                        logger.error(e, e);
                    }
                    finally
                    {
                        try
                        {
                            Sctp.finish();
                        }
                        catch (IOException e)
                        {
                            logger.error("Failed to shutdown SCTP stack", e);
                        }
                    }
                });

            started = true;
        }
    }

    /**
     * Notifies listeners about WebRTC data channel opened event.
     *
     * This notification is executed from the {@link #sctpDispatcher}, so that
     * it's in sync with how the SCTP packets are processed allowing any data
     * callbacks to be set immediately when the data channel is open without
     * missing any packets. When the {@link #MSG_OPEN_CHANNEL} message arrives
     * no further packets are processed until all listener have been notified.
     *
     * @param dataChannel
     */
    private void notifyChannelOpened(final WebRtcDataStream dataChannel)
    {
        if (!isExpired())
        {
            WebRtcDataStreamListener[] ls = getChannelListeners();

            if (ls != null)
            {
                for (WebRtcDataStreamListener l : ls)
                {
                    l.onChannelOpened(this, dataChannel);
                }
            }
        }
    }

    /**
     * Handles a control packet.
     *
     * @param data raw packet data that arrived on control PPID.
     * @param sid SCTP stream id on which the data has arrived.
     */
    private void onCtrlPacket(byte[] data, int sid)
        throws IOException
    {
        synchronized (syncRoot)
        {
            onCtrlPacketNotSynchronized(data, sid);
        }
    }

    /**
     * Handles a control packet. Should only be called while holding the lock on
     * {@link #syncRoot}.
     *
     * @param data raw packet data that arrived on control PPID.
     * @param sid SCTP stream id on which the data has arrived.
     */
    private void onCtrlPacketNotSynchronized(byte[] data, int sid)
        throws IOException
    {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        int messageType = /* 1 byte unsigned integer */ 0xFF & buffer.get();

        if (messageType == MSG_CHANNEL_ACK)
        {
            if (logger.isDebugEnabled())
            {
                logger.debug(Logger.Category.STATISTICS,
                             "sctp_ack_received," + getLoggingId()
                                + " sid=" + sid);
            }
            // Open channel ACK
            WebRtcDataStream channel = channels.get(sid);
            if (channel != null)
            {
                // Ack check prevents from firing multiple notifications
                // if we get more than one ACKs (by mistake/bug).
                if (!channel.isAcknowledged())
                {
                    channel.ackReceived();
                    notifyChannelOpened(channel);
                }
                else
                {
                    logger.log(Level.WARNING, Logger.Category.STATISTICS,
                                 "sctp_redundant_ack_received," + getLoggingId()
                                     + " sid=" + sid);
                }
            }
            else
            {
                logger.error(Logger.Category.STATISTICS,
                           "sctp_no_channel_for_sid," + getLoggingId()
                               + " sid=" + sid);
            }
        }
        else if (messageType == MSG_OPEN_CHANNEL)
        {
            int channelType = /* 1 byte unsigned integer */ 0xFF & buffer.get();
            int priority
                = /* 2 bytes unsigned integer */ 0xFFFF & buffer.getShort();
            long reliability
                = /* 4 bytes unsigned integer */ 0xFFFFFFFFL & buffer.getInt();
            int labelLength
                = /* 2 bytes unsigned integer */ 0xFFFF & buffer.getShort();
            int protocolLength
                = /* 2 bytes unsigned integer */ 0xFFFF & buffer.getShort();
            String label;
            String protocol;

            if (labelLength == 0)
            {
                label = "";
            }
            else
            {
                byte[] labelBytes = new byte[labelLength];

                buffer.get(labelBytes);
                label = new String(labelBytes, "UTF-8");
            }
            if (protocolLength == 0)
            {
                protocol = "";
            }
            else
            {
                byte[] protocolBytes = new byte[protocolLength];

                buffer.get(protocolBytes);
                protocol = new String(protocolBytes, "UTF-8");
            }

            if (logger.isDebugEnabled())
            {
                logger.debug(Logger.Category.STATISTICS,
                           "dc_open_request," + getLoggingId()
                           + " sid=" + sid
                           + ",type=" + channelType
                           + ",prio=" + priority
                           + ",reliab=" + reliability
                           + ",label=" + label
                           + ",proto=" + protocol);
            }

            WebRtcDataStream.DataCallback oldCallback = null;
            if (channels.containsKey(sid))
            {
                // FIXME According to the RFC the DTLS initiator should be using
                // even and the responder odd SID numbers, so such conflict
                // should never happen. If it happens then the JVB should
                // shutdown (reset) the SCTP stream identified by
                // the conflicting SID.
                logger.log(Level.SEVERE, Logger.Category.STATISTICS,
                           "sctp_channel_exists," + getLoggingId()
                           + " sid=" + sid);
                oldCallback = channels.get(sid).getDataCallback();
            }

            WebRtcDataStream newChannel
                = new WebRtcDataStream(this, sctpSocket, sid, label, true);
            channels.put(sid, newChannel);

            if (oldCallback != null)
            {
                // Save the data callback from the previous channel object
                newChannel.setDataCallback(oldCallback);
            }

            sendOpenChannelAck(sid);
            notifyChannelOpened(newChannel);
        }
        else
        {
            logger.error("Unexpected ctrl msg type: " + messageType);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void onEndpointChanged(
        AbstractEndpoint oldValue, AbstractEndpoint newValue)
    {
        super.onEndpointChanged(oldValue, newValue);

        if (oldValue != null && oldValue instanceof Endpoint)
        {
            ((Endpoint) oldValue).setSctpConnection(null);
        }
        if (newValue != null && newValue instanceof Endpoint)
        {
            ((Endpoint) newValue).setSctpConnection(this);
        }
    }

    /**
     * Implements notification in order to track socket state.
     */
    @Override
    public void onSctpNotification(SctpSocket socket,
                                   SctpNotification notification)
    {
        synchronized (syncRoot)
        {
            if (logger.isDebugEnabled())
            {
                // SCTP_SENDER_DRY_EVENT is logged too often. It means that the
                // data queue is now empty and we don't care.
                if (SctpNotification.SCTP_SENDER_DRY_EVENT
                    != notification.sn_type)
                {
                    logger.info(Logger.Category.STATISTICS,
                                "sctp_notification," + getLoggingId()
                                    + " notification=" + notification);
                }
            }

            switch (notification.sn_type)
            {
            case SctpNotification.SCTP_ASSOC_CHANGE:
                SctpNotification.AssociationChange assocChange
                    = (SctpNotification.AssociationChange) notification;

                switch (assocChange.state)
                {
                case SctpNotification.AssociationChange.SCTP_COMM_UP:
                    synchronized (isReadyWaitLock)
                    {
                        if (!assocIsUp)
                        {
                            assocIsUp = true;
                            isReadyWaitLock.notifyAll();
                        }
                    }
                    break;

                case SctpNotification.AssociationChange.SCTP_COMM_LOST:
                case SctpNotification.AssociationChange.SCTP_SHUTDOWN_COMP:
                case SctpNotification.AssociationChange.SCTP_CANT_STR_ASSOC:
                    closeStream();
                    break;
                }
                break;
            }
        }
    }

    /**
     * Opens new WebRTC data channel.
     */
    private void openDefaultWebRTCDataChannel()
    {
        try
        {
            // XXX RFC says that the DTLS initiator uses even and responder odd
            // SID numbers for the channels they open. It's important to note
            // here that more complex logic ensuring SID correctness and
            // availability will be required if the bridge would be to open more
            // than 1 channel. The JVB is still able to handle multiple incoming
            // channels because it's the client that verifies that it's SID
            // numbers.
            int sid = isInitiator() ? 0 : 1;

            logger.debug(String.format(
                "Will open default WebRTC data channel for: %s next SID: %d",
                getLoggingId(), sid));

            openChannel(
                /* type */ 0,
                /* prio */ 0,
                /* reliab - 0 means reliable and ordered */ 0,
                /* sid */ sid,
                /* label */ "default");
        }
        catch (IOException e)
        {
            logger.error(
                String.format(
                    "Could open the default data stream for endpoint: %s",
                    getLoggingId()),
                e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * SCTP input data callback.
     */
    @Override
    public void onSctpPacket(
            byte[] data, int sid, int ssn, int tsn, long ppid, int context,
            int flags)
    {
        sctpDispatcher.execute(() -> {
            if (!isExpired() && sctpSocket != null)
            {
                processSctpPacket(data, sid, ssn, tsn, ppid, context, flags);
            }
        });
    }

    /**
     * Opens new WebRTC data channel using specified parameters.
     * @param type channel type as defined in control protocol description.
     *             Use 0 for "reliable".
     * @param prio channel priority. The higher the number, the lower
     *             the priority.
     * @param reliab Reliability Parameter<br/>
     *
     * This field is ignored if a reliable channel is used.
     * If a partial reliable channel with limited number of
     * retransmissions is used, this field specifies the number of
     * retransmissions.  If a partial reliable channel with limited
     * lifetime is used, this field specifies the maximum lifetime in
     * milliseconds.  The following table summarizes this:<br/></br>

    +------------------------------------------------+------------------+
    | Channel Type                                   |   Reliability    |
    |                                                |    Parameter     |
    +------------------------------------------------+------------------+
    | DATA_CHANNEL_RELIABLE                          |     Ignored      |
    | DATA_CHANNEL_RELIABLE_UNORDERED                |     Ignored      |
    | DATA_CHANNEL_PARTIAL_RELIABLE_REXMIT           |  Number of RTX   |
    | DATA_CHANNEL_PARTIAL_RELIABLE_REXMIT_UNORDERED |  Number of RTX   |
    | DATA_CHANNEL_PARTIAL_RELIABLE_TIMED            |  Lifetime in ms  |
    | DATA_CHANNEL_PARTIAL_RELIABLE_TIMED_UNORDERED  |  Lifetime in ms  |
    +------------------------------------------------+------------------+
     * @param sid SCTP stream id that will be used by new channel
     *            (it must not be already used).
     * @param label text label for the channel.
     * @return new instance of <tt>WebRtcDataStream</tt> that represents opened
     *         WebRTC data channel.
     * @throws IOException if IO error occurs.
     */
    public WebRtcDataStream openChannel(
        int type, int prio, long reliab, int sid, String label)
        throws IOException
    {
        synchronized (syncRoot)
        {
            return openChannelNotSynchronized(type, prio, reliab, sid, label);
        }
    }

    /**
     * Opens new WebRTC data channel using specified parameters. This should
     * only be called while holding a lock on {@link #syncRoot}, as it does not
     * obtain any locks on its own.
     * See {@link #openChannel(int, int, long, int, String)} for a more detailed
     * description.
     */
    private WebRtcDataStream openChannelNotSynchronized(
            int type, int prio, long reliab, int sid, String label)
        throws IOException
    {
        if (channels.containsKey(sid))
            throw new IOException("Channel on sid: " + sid + " already exists");

        // Label Length & Label
        byte[] labelBytes;
        int labelByteLength;

        if (label == null)
        {
            labelBytes = null;
            labelByteLength = 0;
        }
        else
        {
            labelBytes = label.getBytes("UTF-8");
            labelByteLength = Math.min(labelBytes.length, 0xFFFF);
        }

        // Protocol Length & Protocol
        String protocol = WEBRTC_DATA_CHANNEL_PROTOCOL;
        byte[] protocolBytes = protocol.getBytes("UTF-8");
        int protocolByteLength = Math.min(protocolBytes.length, 0xFFFF);

        ByteBuffer packet
            = ByteBuffer.allocate(12 + labelByteLength + protocolByteLength);

        // Message open new channel on current sid
        // Message Type
        packet.put((byte) MSG_OPEN_CHANNEL);
        // Channel Type
        packet.put((byte) type);
        // Priority
        packet.putShort((short) prio);
        // Reliability Parameter
        packet.putInt((int) reliab);
        // Label Length
        packet.putShort((short) labelByteLength);
        // Protocol Length
        packet.putShort((short) protocolByteLength);
        // Label
        if (labelByteLength != 0)
            packet.put(labelBytes, 0, labelByteLength);
        // Protocol
        if (protocolByteLength != 0)
            packet.put(protocolBytes, 0, protocolByteLength);

        int sentCount
            = sctpSocket.send(packet.array(), true, sid, WEB_RTC_PPID_CTRL);

        if (sentCount != packet.capacity())
            throw new IOException("Failed to open new chanel on sid: " + sid);

        WebRtcDataStream channel
            = new WebRtcDataStream(this, sctpSocket, sid, label, false);

        channels.put(sid, channel);

        return channel;
    }

    /**
     * Method called from
     * {@link #onSctpPacket(byte[], int, int, int, long, int, int)} which
     * processes incoming SCTP packets.
     *
     * @see {@link SctpDataCallback#onSctpPacket(byte[], int, int, int, long, int, int)}
     * for arguments description.
     */
    private void processSctpPacket(
            byte[] data,
            int sid, int ssn, int tsn, long ppid, int context, int flags)
    {
        if (ppid == WEB_RTC_PPID_CTRL)
        {
            // Channel control PPID
            try
            {
                onCtrlPacket(data, sid);
            }
            catch (IOException e)
            {
                logger.error("IOException when processing ctrl packet", e);
            }
        }
        else if (ppid == WEB_RTC_PPID_STRING || ppid == WEB_RTC_PPID_BIN)
        {
            WebRtcDataStream channel;

            synchronized (syncRoot)
            {
                channel = channels.get(sid);
            }

            if (channel == null)
            {
                logger.error("No channel found for sid: " + sid);
                return;
            }
            if (ppid == WEB_RTC_PPID_STRING)
            {
                // WebRTC String
                String charsetName = "UTF-8";

                try
                {
                    final String str = new String(data, charsetName);
                    channel.onStringMsg(str);
                }
                catch (UnsupportedEncodingException uee)
                {
                    logger.error(
                        "Unsupported charset encoding/name " + charsetName,
                        uee);
                }
            }
            else
            {
                // WebRTC Binary
                channel.onBinaryMsg(data);
            }
        }
        else
        {
            logger.warn("Got message on unsupported PPID: " + ppid);
        }
    }

    /**
     * Removes <tt>WebRtcDataStreamListener</tt> from the list of listeners.
     *
     * @param listener the <tt>WebRtcDataStreamListener</tt> to be removed from
     * the listeners list.
     */
    public void removeChannelListener(WebRtcDataStreamListener listener)
    {
        if (listener != null)
        {
            synchronized (listeners)
            {
                listeners.remove(listener);
            }
        }
    }

    private void runOnDtlsTransport(StreamConnector connector)
        throws IOException
    {
        SrtpControl srtpControl
            = getTransportManager().getSrtpControl(this);
        DtlsTransformEngine engine
            = (DtlsTransformEngine) srtpControl.getTransformEngine();
        DtlsPacketTransformer transformer
            = (DtlsPacketTransformer) engine.getRTPTransformer();
        if (this.transformer == null)
        {
            this.transformer = transformer;
        }

        byte[] receiveBuffer = new byte[SCTP_BUFFER_SIZE];

        if (LOG_SCTP_PACKETS)
        {
            System.setProperty(
                    ConfigurationService.PNAME_SC_HOME_DIR_LOCATION,
                    System.getProperty("java.io.tmpdir"));
            System.setProperty(
                    ConfigurationService.PNAME_SC_HOME_DIR_NAME,
                    SctpConnection.class.getName());
        }

        synchronized (syncRoot)
        {
            // FIXME local SCTP port is hardcoded in bridge offer SDP (Jitsi
            // Meet)
            sctpSocket = Sctp.createSocket(5000);
            assocIsUp = false;
            acceptedIncomingConnection = false;
        }

        // Implement output network link for SCTP stack on DTLS transport
        sctpSocket.setLink(new NetworkLink()
        {
            @Override
            public void onConnOut(SctpSocket s, byte[] packet)
                throws IOException
            {
                if (LOG_SCTP_PACKETS)
                {
                    LibJitsi.getPacketLoggingService().logPacket(
                            PacketLoggingService.ProtocolName.ICE4J,
                            new byte[] { 0, 0, 0, (byte) debugId },
                            5000,
                            new byte[] { 0, 0, 0, (byte) (debugId + 1) },
                            remoteSctpPort,
                            PacketLoggingService.TransportName.UDP,
                            true,
                            packet);
                }

                // Send through DTLS transport. Add to the queue in order to
                // make sure we don't block the thread which executes this.
                packetQueue.add(packet, 0, packet.length);
            }
        });

        if (logger.isDebugEnabled())
        {
            logger.debug(
                    "Connecting SCTP to port: " + remoteSctpPort + " to "
                        + getEndpoint().getID());
        }

        sctpSocket.setNotificationListener(this);
        sctpSocket.listen();

        sctpSocket.setDataCallback(this);

        sctpDispatcher.execute(this::acceptIncomingSctpConnection);

        // Setup iceSocket
        DatagramSocket datagramSocket = connector.getDataSocket();
        IceSocketWrapper iceSocket;

        if (datagramSocket != null)
        {
            iceSocket = new IceUdpSocketWrapper(datagramSocket);
        }
        else
        {
            iceSocket = new IceTcpSocketWrapper(connector.getDataTCPSocket());
        }

        DatagramPacket recv
            = new DatagramPacket(receiveBuffer, 0, receiveBuffer.length);

        // Receive loop, breaks when SCTP socket is closed
        try
        {
            do
            {
                iceSocket.receive(recv);

                RawPacket[] send
                    = {
                        new RawPacket(
                                recv.getData(),
                                recv.getOffset(),
                                recv.getLength())
                    };

                send = transformer.reverseTransform(send);
                // Check for app data
                if (send == null || send.length == 0)
                    continue;

                // We received data for the SCTP socket, this SctpConnection
                // is still alive
                touch(ActivityType.PAYLOAD);

                if (LOG_SCTP_PACKETS)
                {
                    PacketLoggingService pktLogging
                        = LibJitsi.getPacketLoggingService();
                    byte[] srcAddr
                        = new byte[] { 0, 0, 0, (byte) (debugId + 1) };
                    byte[] dstAddr = new byte[] { 0, 0, 0, (byte) debugId };

                    for (RawPacket s : send)
                    {
                        if (s == null)
                            continue;

                        pktLogging.logPacket(
                                PacketLoggingService.ProtocolName.ICE4J,
                                srcAddr, remoteSctpPort,
                                dstAddr, 5000,
                                PacketLoggingService.TransportName.UDP,
                                false,
                                s.getBuffer(), s.getOffset(), s.getLength());
                    }
                }

                if (sctpSocket == null)
                    break;

                // Pass network packet to SCTP stack
                for (RawPacket s : send)
                {
                    if (s != null)
                    {
                        sctpSocket.onConnIn(
                                s.getBuffer(), s.getOffset(), s.getLength());
                    }
                }
            }
            while (true);
        }
        catch (SocketException ex)
        {
            if (!"Socket closed".equals(ex.getMessage())
                && !(ex instanceof SocketClosedException))
            {
                throw ex;
            }
        }
        finally
        {
            // Eventually, close the socket although it should happen in
            // expire().
            closeStream();
        }
    }

    /**
     * This is the first task executed on the {@link #sctpDispatcher} which
     * accepts the incoming connection on the SCTP socket and blocks until
     * the SCTP association is up. All incoming packets are also queued on the
     * {@link #sctpDispatcher}, so that they are processed only when the SCTP
     * sockets is fully operational. It's been noticed that if the remote
     * party send some messages they would arrive, before the accept() has
     * returned.
     *
     * Pawel: I suspect it's because of the Thread.sleep(100), but there was
     * no reliable way to workaround that. We want the socket to be non blocking
     * and in such config the accept() needs to be polled.
     */
    private void acceptIncomingSctpConnection()
    {
        SctpSocket sctpSocket = null;
        try
        {
            // sctpSocket is set to null on close
            sctpSocket = SctpConnection.this.sctpSocket;
            while (sctpSocket != null)
            {
                if (sctpSocket.accept())
                {
                    acceptedIncomingConnection = true;
                    logger.info(
                        String.format("SCTP socket accepted on %s",
                            getLoggingId()));
                    break;
                }
                Thread.sleep(100);
                sctpSocket = SctpConnection.this.sctpSocket;
            }
            // Implement waiting for ready
            synchronized (isReadyWaitLock)
            {
                while (sctpSocket != null && !isExpired() && !isReady())
                {
                    isReadyWaitLock.wait();
                    sctpSocket = SctpConnection.this.sctpSocket;
                }
                // See maybeOpenDefaultWebRTCDataChannel's description
                sctpDispatcher.execute(
                    this::maybeOpenDefaultWebRTCDataChannel);
            }
        }
        catch (Exception e)
        {
            logger.error(
                String.format(
                    "Error accepting SCTP connection %s",
                    getLoggingId()),
                e);
        }

        if (sctpSocket == null)
        {
            logger.info(String.format(
                "SctpConnection %s closed before SctpSocket accept()-ed.",
                getLoggingId()));
        }
    }



    /**
     * Sends acknowledgment for open channel request on given SCTP stream ID.
     * @param sid SCTP stream identifier to be used for sending ack.
     */
    private void sendOpenChannelAck(int sid)
        throws IOException
    {
        // Send ACK
        byte[] ack = MSG_CHANNEL_ACK_BYTES;

        if (sctpSocket.send(ack, true, sid, WEB_RTC_PPID_CTRL) != ack.length)
            logger.error("Failed to send open channel confirmation");
    }

    /**
     * A {@link org.ice4j.util.PacketQueue.PacketHandler} which sends packets
     * over DTLS.
     */
    private class Handler implements PacketQueue.PacketHandler<RawPacket>
    {
        /**
         * {@inheritDoc}
         */
        @Override
        public boolean handlePacket(RawPacket pkt)
        {
            if (pkt == null)
            {
                return true;
            }

            DtlsPacketTransformer transformer = SctpConnection.this.transformer;
            if (transformer == null)
            {
                logger.error("Cannot send SCTP packet, DTLS transformer is null");
                return false;
            }

            transformer.sendApplicationData(
                pkt.getBuffer(), pkt.getOffset(), pkt.getLength());

            return true;
        }
    };
}
