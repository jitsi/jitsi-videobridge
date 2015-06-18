/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;

import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;

import org.ice4j.socket.*;
import org.jitsi.impl.neomedia.*;
import org.jitsi.impl.neomedia.transform.dtls.*;
import org.jitsi.impl.osgi.framework.*;
import org.jitsi.sctp4j.*;
import org.jitsi.service.configuration.*;
import org.jitsi.service.libjitsi.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.service.packetlogging.*;
import org.jitsi.util.*;

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
    private final static int DTLS_BUFFER_SIZE = 2048;

    /**
     * Switch used for debugging SCTP traffic purposes.
     * FIXME to be removed
     */
    private final static boolean LOG_SCTP_PACKETS = false;

    /**
     * The logger
     */
    private static final Logger logger = Logger.getLogger(SctpConnection.class);

    /**
     * Message type used to acknowledge WebRTC data channel allocation on SCTP
     * stream ID on which <tt>MSG_OPEN_CHANNEL</tt> message arrives.
     */
    private final static int MSG_CHANNEL_ACK = 0x2;

    private static final byte[] MSG_CHANNEL_ACK_BYTES
        = new byte[] { MSG_CHANNEL_ACK };

    /**
     * Message with this type sent over control PPID in order to open new WebRTC
     * data channel on SCTP stream ID that this message is sent.
     */
    private final static int MSG_OPEN_CHANNEL = 0x3;

    /**
     * SCTP transport buffer size.
     */
    private final static int SCTP_BUFFER_SIZE = DTLS_BUFFER_SIZE - 13;

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
    private final Map<Integer,WebRtcDataStream> channels
        = new HashMap<Integer,WebRtcDataStream>();

    /**
     * Debug ID used to distinguish SCTP sockets in packet logs.
     */
    private final int debugId;

    /**
     * The <tt>AsyncExecutor</tt> which is to asynchronously dispatch the events
     * fired by this instance in order to prevent possible listeners from
     * blocking this <tt>SctpConnection</tt> in general and {@link #sctpSocket}
     * in particular for too long. The timeout of <tt>15</tt> is chosen to be in
     * accord with the time it takes to expire a <tt>Channel</tt>.
     */
    private final AsyncExecutor<Runnable> eventDispatcher
        = new AsyncExecutor<Runnable>(15, TimeUnit.MILLISECONDS);

    /**
     * Datagram socket for ICE/UDP layer.
     */
    private IceSocketWrapper iceSocket;

    /**
     * List of <tt>WebRtcDataStreamListener</tt>s that will be notified whenever
     * new WebRTC data channel is opened.
     */
    private final List<WebRtcDataStreamListener> listeners
        = new ArrayList<WebRtcDataStreamListener>();

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
     * @throws Exception if an error occurs while initializing the new instance
     */
    public SctpConnection(
            String id,
            Content content,
            Endpoint endpoint,
            int remoteSctpPort,
            String channelBundleId,
            Boolean initiator)
        throws Exception
    {
        super(content, id,
              channelBundleId,
              IceUdpTransportPacketExtension.NAMESPACE,
              initiator);

        setEndpoint(endpoint.getID());

        this.remoteSctpPort = remoteSctpPort;
        this.debugId = generateDebugId();
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
                if(!listeners.contains(listener))
                {
                    listeners.add(listener);
                }
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void closeStream()
        throws IOException
    {
        try
        {
            synchronized (this)
            {
                assocIsUp = false;
                acceptedIncomingConnection = false;
                if (sctpSocket != null)
                {
                    sctpSocket.close();
                    sctpSocket = null;
                }
            }
        }
        finally
        {
            if (iceSocket != null)
            {
                // It is now the responsibility of the transport manager to
                // close the socket.
                //iceUdpSocket.close();
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
            //TODO: support RawUdp once RawUdpTransportManager is updated
            //return new RawUdpTransportManager(this);
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
    public void expire()
    {
        try
        {
            eventDispatcher.shutdown();
        }
        finally
        {
            super.expire();
        }
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
     * Returns default <tt>WebRtcDataStream</tt> if it's ready or <tt>null</tt>
     * otherwise.
     * @return <tt>WebRtcDataStream</tt> if it's ready or <tt>null</tt>
     *         otherwise.
     * @throws IOException
     */
    public WebRtcDataStream getDefaultDataStream()
        throws IOException
    {
        WebRtcDataStream def;

        synchronized (this)
        {
            if(sctpSocket == null)
            {
                def = null;
            }
            else
            {
                // Channel that runs on sid 0
                def = channels.get(0);
                if (def == null)
                {
                    def = openChannel(0, 0, 0, 0, "default");
                }
                // Pawel Domas: Must be acknowledged before use
                /*
                 * XXX Lyubomir Marinov: We're always sending ordered. According
                 * to "WebRTC Data Channel Establishment Protocol", we can start
                 * sending messages containing user data after the
                 * DATA_CHANNEL_OPEN message has been sent without waiting for
                 * the reception of the corresponding DATA_CHANNEL_ACK message.
                 */
//                if (!def.isAcknowledged())
//                    def = null;
            }
        }
        return def;
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
     * {@inheritDoc}
     */
    @Override
    protected void maybeStartStream()
        throws IOException
    {
        // connector
        final StreamConnector connector = getStreamConnector();

        if (connector == null)
            return;

        synchronized (this)
        {
            if (started)
                return;

            threadPool.execute(
                    new Runnable()
                    {
                        @Override
                        public void run()
                        {
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
                                    logger.error(
                                            "Failed to shutdown SCTP stack",
                                            e);
                                }
                            }
                        }
                    });

            started = true;
        }
    }

    /**
     * Submits {@link #notifyChannelOpenedInEventDispatcher(WebRtcDataStream)}
     * to {@link #eventDispatcher} for asynchronous execution.
     *
     * @param dataChannel
     */
    private void notifyChannelOpened(final WebRtcDataStream dataChannel)
    {
        if (!isExpired())
        {
            eventDispatcher.execute(
                    new Runnable()
                    {
                        @Override
                        public void run()
                        {
                            notifyChannelOpenedInEventDispatcher(dataChannel);
                        }
                    });
        }
    }

    private void notifyChannelOpenedInEventDispatcher(
            WebRtcDataStream dataChannel)
    {
        /*
         * When executing asynchronously in eventDispatcher, it is technically
         * possible that this SctpConnection may have expired by now.
         */
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
     * Submits {@link #notifySctpConnectionReadyInEventDispatcher()} to
     * {@link #eventDispatcher} for asynchronous execution.
     */
    private void notifySctpConnectionReady()
    {
        if (!isExpired())
        {
            eventDispatcher.execute(
                    new Runnable()
                    {
                        @Override
                        public void run()
                        {
                            notifySctpConnectionReadyInEventDispatcher();
                        }
                    });
        }
    }

    /**
     * Notifies the <tt>WebRtcDataStreamListener</tt>s added to this instance
     * that this <tt>SctpConnection</tt> is ready i.e. it is connected to the
     * remote peer and operational.
     */
    private void notifySctpConnectionReadyInEventDispatcher()
    {
        /*
         * When executing asynchronously in eventDispatcher, it is technically
         * possible that this SctpConnection may have expired by now.
         */
        if (!isExpired() && isReady())
        {
            WebRtcDataStreamListener[] ls = getChannelListeners();

            if (ls != null)
            {
                for(WebRtcDataStreamListener l : ls)
                {
                    l.onSctpConnectionReady(this);
                }
            }
        }
    }

    /**
     * Handles control packet.
     * @param data raw packet data that arrived on control PPID.
     * @param sid SCTP stream id on which the data has arrived.
     */
    private synchronized void onCtrlPacket(byte[] data, int sid)
        throws IOException
    {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        int messageType = /* 1 byte unsigned integer */ 0xFF & buffer.get();

        if(messageType == MSG_CHANNEL_ACK)
        {
            if (logger.isDebugEnabled())
            {
                logger.debug(
                        getEndpoint().getID() + " ACK received SID: " + sid);
            }
            // Open channel ACK
            WebRtcDataStream channel = channels.get(sid);
            if(channel != null)
            {
                // Ack check prevents from firing multiple notifications
                // if we get more than one ACKs (by mistake/bug).
                if(!channel.isAcknowledged())
                {
                    channel.ackReceived();
                    notifyChannelOpened(channel);
                }
                else
                {
                    logger.warn("Redundant ACK received for SID: " + sid);
                }
            }
            else
            {
                logger.error("No channel exists on sid: "+sid);
            }
        }
        else if(messageType == MSG_OPEN_CHANNEL)
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
                logger.debug(
                        "!!! " + getEndpoint().getID()
                            + " data channel open request on SID: " + sid
                            + " type: " + channelType + " prio: " + priority
                            + " reliab: " + reliability + " label: " + label
                            + " proto: " + protocol);
            }

            if(channels.containsKey(sid))
            {
                logger.error("Channel on sid: " + sid + " already exists");
            }

            WebRtcDataStream newChannel
                = new WebRtcDataStream(sctpSocket, sid, label, true);
            channels.put(sid, newChannel);

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
    protected void onEndpointChanged(Endpoint oldValue, Endpoint newValue)
    {
        super.onEndpointChanged(oldValue, newValue);

        if (oldValue != null)
            oldValue.setSctpConnection(null);
        if (newValue != null)
            newValue.setSctpConnection(this);
    }

    /**
     * Implements notification in order to track socket state.
     */
    @Override
    public synchronized void onSctpNotification(SctpSocket socket,
                                   SctpNotification notification)
    {
        if (logger.isDebugEnabled())
        {
            logger.debug("socket=" + socket + "; notification=" + notification);
        }
        switch (notification.sn_type)
        {
        case SctpNotification.SCTP_ASSOC_CHANGE:
            SctpNotification.AssociationChange assocChange
                = (SctpNotification.AssociationChange) notification;

            switch (assocChange.state)
            {
            case SctpNotification.AssociationChange.SCTP_COMM_UP:
                if (!assocIsUp)
                {
                    boolean wasReady = isReady();

                    assocIsUp = true;
                    if (isReady() && !wasReady)
                        notifySctpConnectionReady();
                }
                break;

            case SctpNotification.AssociationChange.SCTP_COMM_LOST:
            case SctpNotification.AssociationChange.SCTP_SHUTDOWN_COMP:
            case SctpNotification.AssociationChange.SCTP_CANT_STR_ASSOC:
                try
                {
                    closeStream();
                }
                catch (IOException e)
                {
                    logger.error("Error closing SCTP socket", e);
                }
                break;
            }
            break;
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
        if(ppid == WEB_RTC_PPID_CTRL)
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
        else if(ppid == WEB_RTC_PPID_STRING || ppid == WEB_RTC_PPID_BIN)
        {
            WebRtcDataStream channel;

            synchronized (this)
            {
                channel = channels.get(sid);
            }

            if(channel == null)
            {
                logger.error("No channel found for sid: " + sid);
                return;
            }
            if(ppid == WEB_RTC_PPID_STRING)
            {
                // WebRTC String
                String str;
                String charsetName = "UTF-8";

                try
                {
                    str = new String(data, charsetName);
                }
                catch (UnsupportedEncodingException uee)
                {
                    logger.error(
                            "Unsupported charset encoding/name " + charsetName,
                            uee);
                    str = null;
                }
                channel.onStringMsg(str);
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
    public synchronized WebRtcDataStream openChannel(
            int type, int prio, long reliab, int sid,  String label)
        throws IOException
    {
        if(channels.containsKey(sid))
        {
            throw new IOException("Channel on sid: " + sid + " already exists");
        }

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
            labelByteLength = labelBytes.length;
            if (labelByteLength > 0xFFFF)
                labelByteLength = 0xFFFF;
        }

        // Protocol Length & Protocol
        String protocol = WEBRTC_DATA_CHANNEL_PROTOCOL;
        byte[] protocolBytes = protocol.getBytes("UTF-8");
        int protocolByteLength = protocolBytes.length;

        if (protocolByteLength > 0xFFFF)
        {
            protocolByteLength = 0xFFFF;
        }

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
        if(labelByteLength != 0)
        {
            packet.put(labelBytes, 0, labelByteLength);
        }
        // Protocol
        if (protocolByteLength != 0)
        {
            packet.put(protocolBytes, 0, protocolByteLength);
        }

        int sentCount
            = sctpSocket.send(packet.array(), true, sid, WEB_RTC_PPID_CTRL);

        if(sentCount != packet.capacity())
        {
            throw new IOException("Failed to open new chanel on sid: " + sid);
        }

        WebRtcDataStream channel
            = new WebRtcDataStream(sctpSocket, sid, label, false);

        channels.put(sid, channel);

        return channel;
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
        DtlsControlImpl dtlsControl
                = (DtlsControlImpl) getTransportManager().getDtlsControl(this);
        DtlsTransformEngine engine = dtlsControl.getTransformEngine();
        final DtlsPacketTransformer transformer
                = (DtlsPacketTransformer) engine.getRTPTransformer();

        byte[] receiveBuffer = new byte[SCTP_BUFFER_SIZE];

        if(LOG_SCTP_PACKETS)
        {
            System.setProperty(
                    ConfigurationService.PNAME_SC_HOME_DIR_LOCATION,
                    System.getProperty("java.io.tmpdir"));
            System.setProperty(
                    ConfigurationService.PNAME_SC_HOME_DIR_NAME,
                    SctpConnection.class.getName());
        }

        synchronized (this)
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

                // Send through DTLS transport
                transformer.sendApplicationData(packet, 0, packet.length);
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

        // FIXME manage threads
        threadPool.execute(
                new Runnable()
                {
                    @Override
                    public void run()
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
                                    break;
                                }
                                Thread.sleep(100);
                                sctpSocket = SctpConnection.this.sctpSocket;
                            }
                            if (isReady())
                            {
                                notifySctpConnectionReady();
                            }
                        }
                        catch (Exception e)
                        {
                            logger.error("Error accepting SCTP connection", e);
                        }

                        if (sctpSocket == null && logger.isInfoEnabled())
                        {
                            logger.info("SctpConnection " + getID() + " closed"
                                        + " before SctpSocket accept()-ed.");
                        }
                    }
                });

        // Notify that from now on SCTP connection is considered functional
        sctpSocket.setDataCallback(this);

        // Setup iceSocket
        DatagramSocket datagramSocket = connector.getDataSocket();
        if (datagramSocket != null)
        {
            this.iceSocket = new IceUdpSocketWrapper(datagramSocket);
        }
        else
        {
            this.iceSocket
                    = new IceTcpSocketWrapper(connector.getDataTCPSocket());
        }

        DatagramPacket rcvPacket
            = new DatagramPacket(receiveBuffer, 0, receiveBuffer.length);

        // Receive loop, breaks when SCTP socket is closed
        try
        {
            do
            {
                iceSocket.receive(rcvPacket);

                RawPacket raw
                    = new RawPacket(
                            rcvPacket.getData(),
                            rcvPacket.getOffset(),
                            rcvPacket.getLength());

                raw = transformer.reverseTransform(raw);
                // Check for app data
                if (raw == null)
                    continue;

                if(LOG_SCTP_PACKETS)
                {
                    LibJitsi.getPacketLoggingService().logPacket(
                            PacketLoggingService.ProtocolName.ICE4J,
                            new byte[] { 0,0,0, (byte) (debugId +1) },
                            remoteSctpPort,
                            new byte[] { 0,0,0, (byte) debugId },
                            5000,
                            PacketLoggingService.TransportName.UDP,
                            false,
                            raw.getBuffer(), raw.getOffset(), raw.getLength());
                }

                if (sctpSocket == null)
                    break;

                // Pass network packet to SCTP stack
                sctpSocket.onConnIn(
                    raw.getBuffer(), raw.getOffset(), raw.getLength());
            }
            while (true);
        }
        catch (SocketException ex)
        {
            if (!"Socket closed".equals(ex.getMessage()))
            {
                throw ex;
            }
        }
        finally
        {
            // Eventually, close the socket although it should happen from
            // expire().
            synchronized (this)
            {
                assocIsUp = false;
                acceptedIncomingConnection = false;
                if(sctpSocket != null)
                {
                    sctpSocket.close();
                    sctpSocket = null;
                }
            }
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
        int sendAck = sctpSocket.send(ack, true, sid, WEB_RTC_PPID_CTRL);

        if(sendAck != ack.length)
        {
            logger.error("Failed to send open channel confirmation");
        }
    }
}
