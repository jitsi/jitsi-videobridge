/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge;

import org.jitsi.impl.neomedia.*;
import org.jitsi.impl.neomedia.transform.dtls.*;

import org.jitsi.sctp4j.*;
import org.jitsi.service.configuration.*;
import org.jitsi.service.libjitsi.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.service.packetlogging.*;
import org.jitsi.util.*;

import javax.media.rtp.*;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.util.*;

/**
 * Class is a transport layer for WebRTC data channels. It consists of SCTP
 * connection running on top of ICE/DTLS layer. Manages WebRTC data channels.
 * See http://tools.ietf.org/html/draft-ietf-rtcweb-data-channel-08 for more
 * info on WebRTC data channels.<br/>
 *
 * Control protocol:
 * http://tools.ietf.org/html/draft-ietf-rtcweb-data-protocol-03
 * <br/>
 *
 * FIXME: handle closing of data channels(SCTP stream reset)
 *
 * @author Pawel Domas
 */
public class SctpConnection
    extends Channel
    implements SctpDataCallback,
               SctpSocket.NotificationListener
{
    /**
     * The logger
     */
    private static final Logger logger = Logger.getLogger(SctpConnection.class);

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
     * Payload protocol id that identifies binary data in WebRTC data channel.
     */
    static final int WEB_RTC_PPID_BIN = 53;

    /**
     * Message with this type sent over control PPID in order to open new WebRTC
     * data channel on SCTP stream ID that this message is sent.
     */
    private final static int MSG_OPEN_CHANNEL = 0x3;

    /**
     * Message type used to acknowledge WebRTC data channel allocation on SCTP
     * stream ID on which <tt>MSG_OPEN_CHANNEL</tt> message arrives.
     */
    private final static int MSG_CHANNEL_ACK = 0x2;

    /**
     * Switch used for debugging SCTP traffic purposes.
     * FIXME: to be removed
     */
    private final static boolean LOG_SCTP_PACKETS = false;

    /**
     * DTLS transport buffer size.
     * Note: randomly chosen.
     */
    private final static int DTLS_BUFFER_SIZE = 2048;

    /**
     * SCTP transport buffer size.
     */
    private final static int SCTP_BUFFER_SIZE = DTLS_BUFFER_SIZE - 13;

    /**
     * DTLS layer used by this <tt>SctpConnection</tt>.
     */
    private final DtlsControlImpl dtlsControl;

    /**
     * Remote SCTP port.
     */
    private int remoteSctpPort;

    /**
     * <tt>SctpSocket</tt> used for SCTP transport.
     */
    private SctpSocket sctpSocket;

    /**
     * Indicates whether this <tt>SctpConnection</tt> is connected to other
     * peer.
     */
    private boolean ready;

    /**
     * Flag prevents from starting this connection multiple times from
     * {@link #maybeStartStream()}.
     */
    private boolean started;

    /**
     * List of <tt>WebRtcDataStreamListener</tt>s that will be notified whenever
     * new WebRTC data channel is opened.
     */
    private List<WebRtcDataStreamListener> listenerList
        = new ArrayList<WebRtcDataStreamListener>();

    /**
     * Data channels mapped by SCTP stream identified(sid).
     */
    private HashMap<Integer, WebRtcDataStream> channels
        = new HashMap<Integer, WebRtcDataStream>();

    /**
     * Generator used to track debug IDs.
     */
    private static int debugIdGen = -1;

    /**
     * Debug ID used to distinguish SCTP sockets in packet logs.
     */
    private final int debugId;

    /**
     * Datagram socket for ICE/UDP layer.
     */
    private DatagramSocket iceUdpSocket;

    /**
     * Initializes new <tt>SctpConnection</tt> instance.
     *
     * @param content the <tt>Content</tt> which is initializing the new
     *                instance
     * @param endpoint the <tt>Endpoint</tt> of newly created
     *                 <tt>SctpConnection</tt>
     * @param remoteSctpPort the SCTP port used by remote peer
     * @throws Exception if an error occurs while initializing the new instance
     */
    public SctpConnection(Content content, Endpoint endpoint,
                          int remoteSctpPort)
        throws Exception
    {
        super(content);

        setEndpoint(endpoint.getID());

        this.remoteSctpPort = remoteSctpPort;

        this.dtlsControl = new DtlsControlImpl(true);

        this.debugId = generateDebugId();
    }

    private static synchronized int generateDebugId()
    {
        debugIdGen += 2;
        return debugIdGen;
    }

    /**
     * {@inheritDoc}
     *
     * Implemented for logging purposes.
     */
    @Override
    public String getID()
    {
        return "SCTP_with_" + getEndpoint().getID();
    }


    /**
     * Returns <tt>true</tt> if this <tt>SctpConnection</tt> is connected to
     * other peer and operational.
     *
     * @return <tt>true</tt> if this <tt>SctpConnection</tt> is connected to
     * other peer and operational.
     */
    public boolean isReady()
    {
        return ready;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected DtlsControl getDtlsControl()
    {
        return dtlsControl;
    }

    /**
     * {@inheritDoc}
     */
    protected synchronized void maybeStartStream()
        throws IOException
    {
        if(started)
            return;

        // connector
        final StreamConnector connector = createStreamConnector();

        if (connector == null)
            return;

        dtlsControl.setSetup(
            isInitiator()
                ? DtlsControl.Setup.PASSIVE
                : DtlsControl.Setup.ACTIVE);

        dtlsControl.start(MediaType.DATA);

        new Thread(new Runnable()
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
                        logger.error("Failed to shutdown SCTP stack", e);
                    }
                }
            }
        }, "SctpConnectionReceiveThread").start();

        started = true;
    }

    private void runOnDtlsTransport(StreamConnector connector)
        throws IOException
    {
        RTPConnectorUDPImpl rtpConnector
            = new RTPConnectorUDPImpl(connector);

        MediaStreamTarget streamTarget = createStreamTarget();

        rtpConnector.addTarget(
            new SessionAddress(
                streamTarget.getDataAddress().getAddress(),
                streamTarget.getDataAddress().getPort())
        );

        dtlsControl.setConnector(rtpConnector);

        DtlsTransformEngine engine = dtlsControl.getTransformEngine();
        final DtlsPacketTransformer transformer
            = (DtlsPacketTransformer) engine.getRTPTransformer();

        byte[] receiveBuffer = new byte[SCTP_BUFFER_SIZE];

        if(LOG_SCTP_PACKETS)
        {
            System.setProperty(
                ConfigurationService.PNAME_SC_HOME_DIR_LOCATION,
                "E:/temp/");
            System.setProperty(
                ConfigurationService.PNAME_SC_HOME_DIR_NAME,
                "videobridgeSctp");
        }

        synchronized (this)
        {
            // Fixme: local SCTP port is hardcoded in bridge offer SDP(Jitsi Meet)
            this.sctpSocket = Sctp.createSocket(5000);
        }

        // Implement output network link for SCTP stack on DTLS transport
        sctpSocket.setLink(new NetworkLink()
        {
            private final RawPacket rawPacket = new RawPacket();

            @Override
            public void onConnOut(org.jitsi.sctp4j.SctpSocket s, byte[] packet)
                throws IOException
            {
                if (LOG_SCTP_PACKETS)
                    LibJitsi.getPacketLoggingService().logPacket(
                        PacketLoggingService.ProtocolName.ICE4J,
                        new byte[]{0, 0, 0, (byte) debugId},
                        5000,
                        new byte[]{0, 0, 0, (byte) (debugId + 1)},
                        remoteSctpPort,
                        PacketLoggingService.TransportName.UDP,
                        true,
                        packet);

                // Send through DTLS transport
                rawPacket.setBuffer(packet);
                rawPacket.setLength(packet.length);

                transformer.transform(rawPacket);
            }
        });

        logger.info("Connecting SCTP to port: " + remoteSctpPort +
            " to " + getEndpoint().getID());

        sctpSocket.setNotificationListener(this);

        sctpSocket.listen();

        // FIXME: manage threads
        new Thread(new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    while (!sctpSocket.accept())
                    {
                        Thread.sleep(100);
                    }
                }
                catch (Exception e)
                {
                    logger.error("Error accepting SCTP connection", e);
                }
            }
        }, "SctpAcceptThread").start();

        // Notify that from now on SCTP connection is considered functional
        sctpSocket.setDataCallback(this);

        // Receive loop, breaks when SCTP socket is closed
        this.iceUdpSocket = rtpConnector.getDataSocket();
        DatagramPacket rcvPacket
            = new DatagramPacket(receiveBuffer, 0, receiveBuffer.length);

        try
        {
            while (true)
            {
                iceUdpSocket.receive(rcvPacket);

                RawPacket raw = new RawPacket(
                    rcvPacket.getData(), rcvPacket.getOffset(),
                    rcvPacket.getLength());

                raw = transformer.reverseTransform(raw);
                // Check for app data
                if (raw == null)
                    continue;

                if(LOG_SCTP_PACKETS)
                    LibJitsi.getPacketLoggingService().logPacket(
                        PacketLoggingService.ProtocolName.ICE4J,
                        new byte[]{0,0,0, (byte) (debugId +1)},
                        remoteSctpPort,
                        new byte[]{0,0,0, (byte) debugId},
                        5000,
                        PacketLoggingService.TransportName.UDP,
                        false,
                        raw.getBuffer(), raw.getOffset(), raw.getLength());

                // Pass network packet to SCTP stack
                sctpSocket.onConnIn(
                    raw.getBuffer(), raw.getOffset(), raw.getLength());
            }
        }
        finally
        {
            // Eventually close the socket, although it should happen from
            // expire()
            if(sctpSocket != null)
                sctpSocket.close();
        }

    }

    /**
     * {@inheritDoc}
     *
     * SCTP input data callback.
     */
    @Override
    public void onSctpPacket(byte[] data, int sid, int ssn, int tsn, long ppid,
                             int context, int flags)
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
                channel.onStringMsg(new String(data));
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
     * Handles control packet.
     * @param data raw packet data that arrived on control PPID.
     * @param sid SCTP stream id on which the data has arrived.
     */
    private synchronized void onCtrlPacket(byte[] data, int sid)
        throws IOException
    {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(data);

        int messageType = buffer.get();
        if(messageType == MSG_CHANNEL_ACK)
        {
            logger.info(getEndpoint().getID() + " ACK received SID: " + sid);
            // Open channel ACK
            WebRtcDataStream channel = channels.get(sid);
            if(channel != null)
            {
                // Ack check prevents from firing multiple notifications
                // if we get more than one ACKs(by mistake/bug)
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
            int channelType = buffer.get();
            int priority = buffer.getShort();
            long reliability = buffer.getInt();

            int labelLength = buffer.getShort();
            int protocolLength = buffer.getShort();

            byte[] labelRaw = new byte[labelLength];
            buffer.get(labelRaw);
            String label = new String(labelRaw);

            byte[] protocolRaw = new byte[protocolLength];
            buffer.get(protocolRaw);
            String protocol = new String(protocolRaw);

            logger.info("!!! " + getEndpoint().getID() +
                " data channel open request"
                            + " on SID: " + sid
                            + " type: " + channelType
                            + " prio: " + priority
                            + " reliab: " + reliability
                            + " label: " + label
                            + " proto: " + protocol);

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
     * Sends acknowledgment for open channel request on given SCTP stream ID.
     * @param sid SCTP stream identifier to be used for sending ack.
     */
    private void sendOpenChannelAck(int sid)
        throws IOException
    {
        // Send ACK
        byte[] ack = new byte[] { MSG_CHANNEL_ACK };
        int sendAck = sctpSocket.send(ack, true, sid, WEB_RTC_PPID_CTRL);
        if(sendAck != ack.length)
        {
            logger.error("Failed to send open channel confirmation");
        }
    }

    /**
     * Opens new WebRTC data channel using specified parameters.
     * @param type channel type as defined in control protocol description.
     *             Use 0 for "realiable".
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
            throw new IOException(
                "Channel on sid: " + sid + " already exists");
        }

        ByteBuffer packet = ByteBuffer.allocate(14 + label.length());

        // Message open new channel on current sid
        packet.put((byte)MSG_OPEN_CHANNEL);
        // Channel type
        packet.put((byte)type);
        // Channel priority
        packet.putShort((short) prio);
        // Channel reliability
        packet.putInt((int) reliab);
        // Label length
        packet.putShort((short) label.length());
        // Protocol length
        packet.putShort((short) 0);
        // Label content
        if(label.length() > 0)
        {
            packet.put(label.getBytes("UTF8"));
        }
        // Protocol is not used for the time being
        // TODO: eventually write protocol content here

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

    private void notifyChannelOpened(WebRtcDataStream dataChannel)
    {
        for(WebRtcDataStreamListener l : listenerList)
        {
            l.onChannelOpened(dataChannel);
        }
    }

    private void notifySctpConnectionReady()
    {
        for(WebRtcDataStreamListener l : listenerList)
        {
            l.onSctpConnectionReady();
        }
    }

    /**
     * Adds <tt>WebRtcDataStreamListener</tt> to the list of listeners.
     * @param listener the <tt>WebRtcDataStreamListener</tt> to be added to
     *                 the listeners list.
     */
    public void addChannelListener(WebRtcDataStreamListener listener)
    {
        if(!listenerList.contains(listener))
        {
            listenerList.add(listener);
        }
    }

    /**
     * Removes <tt>WebRtcDataStreamListener</tt> from the list of listeners.
     * @param l the <tt>WebRtcDataStreamListener</tt> to be removed from
     *          the listeners list.
     */
    public void removeChannelListener(WebRtcDataStreamListener l)
    {
        listenerList.remove(l);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void onEndpointChanged(Endpoint oldValue, Endpoint newValue)
    {
        if (oldValue != null)
            oldValue.setSctpConnection(null);

        if (newValue != null)
            newValue.setSctpConnection(this);
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
                if (sctpSocket != null)
                {
                    sctpSocket.close();
                }
                sctpSocket = null;
            }
        }
        finally
        {
            if(iceUdpSocket != null)
            {
                iceUdpSocket.close();
            }
        }
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
        synchronized (this)
        {
            if(sctpSocket == null)
                return null;

            // Channel that runs on sid 0
            WebRtcDataStream def = channels.get(0);
            if (def == null)
            {
                def = openChannel(0, 0, 0, 0, "default");
            }
            // Must be acknowledged before use
            return def.isAcknowledged() ? def : null;
        }
    }

    /**
     * Implements notification in order to track socket state.
     */
    @Override
    public synchronized void onSctpNotification(SctpSocket socket,
                                   SctpNotification notification)
    {
        logger.info("Socket("+socket+") "+notification);

        if(notification.sn_type == SctpNotification.SCTP_ASSOC_CHANGE)
        {
            SctpNotification.AssociationChange assocChange
                = (SctpNotification.AssociationChange) notification;
            switch (assocChange.state)
            {
                case SctpNotification.AssociationChange.SCTP_COMM_UP:
                    ready = true;
                    notifySctpConnectionReady();
                    break;

                case SctpNotification.AssociationChange.SCTP_COMM_LOST:
                case SctpNotification.AssociationChange.SCTP_SHUTDOWN_COMP:
                case SctpNotification.AssociationChange.SCTP_CANT_STR_ASSOC:
                    ready = false;
                    try
                    {
                        closeStream();
                    }
                    catch (IOException e)
                    {
                        logger.error("Error closing sctp socket", e);
                    }
                    break;
            }
        }
    }

    /**
     * Interface used to notify about WebRTC data channels opened by
     * remote peer.
     */
    public interface WebRtcDataStreamListener
    {
        /**
         * Indicates that this <tt>SctpConnection</tt> has established SCTP
         * connection. After that it can be used to either open WebRTC data
         * channel or listen for channels opened by remote peer.
         */
        public void onSctpConnectionReady();

        /**
         * Fired when new WebRTC data channel is opened.
         * @param newStream the <tt>WebRtcDataStream</tt> that represents opened
         *                  WebRTC data channel.
         */
        public void onChannelOpened(WebRtcDataStream newStream);
    }
}
