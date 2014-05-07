/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;

import org.bouncycastle.crypto.tls.*;

import org.jitsi.sctp4j.*;
import org.jitsi.service.configuration.*;
import org.jitsi.service.libjitsi.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.service.packetlogging.*;
import org.jitsi.util.*;
import org.jitsi.videobridge.dtls.DtlsLayer;

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
    implements SctpDataCallback
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
     * Note: randomly choosen.
     */
    private final static int DTLS_BUFFER_SIZE = 2048;

    /**
     * SCTP transport buffer size.
     */
    private final static int SCTP_BUFFER_SIZE = DTLS_BUFFER_SIZE - 13;

    /**
     * DTLS layer used by this <tt>SctpConnection</tt>.
     */
    private final DtlsLayer dtlsLayer;

    /**
     * Remote SCTP port.
     */
    private int remoteSctpPort;

    /**
     * <tt>SctpSocket</tt> used for SCTP transport.
     */
    private SctpSocket sctpSocket;

    /**
     * ICE/UDP transport used by DTLS layer.
     */
    private DatagramTransportImpl datagramTransport;

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

        this.dtlsLayer = new DtlsLayer();
    }

    /**
     * {@inheritDoc}
     *
     * FIXME: merge this method with the one used by {@link RtpChannel}
     *        once DTLS common code base is extracted. See {@link DtlsLayer}.
     */
    public void setTransport(IceUdpTransportPacketExtension transport)
        throws IOException
    {
        if (transport != null)
        {
            List<DtlsFingerprintPacketExtension> dfpes
                = transport.getChildExtensionsOfType(
                        DtlsFingerprintPacketExtension.class);

            if (!dfpes.isEmpty())
            {
                Map<String,String> remoteFingerprints
                    = new LinkedHashMap<String,String>();

                for (DtlsFingerprintPacketExtension dfpe : dfpes)
                {
                    remoteFingerprints.put(
                        dfpe.getHash(),
                        dfpe.getFingerprint());
                }

                dtlsLayer.setRemoteFingerprints(remoteFingerprints);
            }
        }

        super.setTransport(transport);
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
     * Sets the values of the properties of a specific
     * <tt>ColibriConferenceIQ.SctpConnection</tt> to the values
     * of the respective properties of this instance. Thus, the specified
     * <tt>iq</tt> may be thought of as a description of this instance.
     *
     * @param iq the <tt>ColibriConferenceIQ.SctpConnection</tt> on which to set
     *           the values of the properties of this instance
     */
    @Override
    public void describe(ColibriConferenceIQ.ChannelCommon iq)
    {
        super.describe(iq);

        describeSrtpControl((ColibriConferenceIQ.SctpConnection)iq);
    }

    /**
     * Puts the description of DTLS part of the transport into given
     * <tt>ColibriConferenceIQ.SctpConnection</tt>.
     *
     * FIXME: merge this method with the one used by {@link RtpChannel}
     *        once DTLS common code base is extracted. See {@link DtlsLayer}.
     */
    protected void describeSrtpControl(ColibriConferenceIQ.SctpConnection iq)
    {
        String fingerprint = dtlsLayer.getLocalFingerprint();
        String hash = dtlsLayer.getLocalFingerprintHashFunction();

        IceUdpTransportPacketExtension transportPE = iq.getTransport();

        if (transportPE == null)
        {
            transportPE = new RawUdpTransportPacketExtension();
            iq.setTransport(transportPE);
        }

        DtlsFingerprintPacketExtension fingerprintPE
            = transportPE.getFirstChildOfType(
            DtlsFingerprintPacketExtension.class);

        if (fingerprintPE == null)
        {
            fingerprintPE = new DtlsFingerprintPacketExtension();
            transportPE.addChildExtension(fingerprintPE);
        }
        fingerprintPE.setFingerprint(fingerprint);
        fingerprintPE.setHash(hash);
    }

    /**
     * {@inheritDoc}
     */
    protected void maybeStartStream()
        throws IOException
    {
        // connector
        StreamConnector connector = createStreamConnector();

        if (connector == null)
            return;

        dtlsLayer.setSetup(
            isInitiator()
                ? DtlsControl.Setup.PASSIVE
                : DtlsControl.Setup.ACTIVE);

        this.datagramTransport
            = new DatagramTransportImpl(connector, createStreamTarget());

        final DTLSTransport finalDtlsTransport
            = dtlsLayer.startDtls(datagramTransport);

        if(finalDtlsTransport == null)
        {
            throw new RuntimeException("Failed to start DTLS");
        }

        new Thread(new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    Sctp.init(0);

                    runOnDtlsTransport(finalDtlsTransport);
                }
                catch (IOException e)
                {
                    logger.error(e, e);
                }
                finally
                {
                    Sctp.finish();
                }
            }
        }).start();
    }

    private void runOnDtlsTransport(final DTLSTransport transport)
        throws IOException
    {
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

        // Fixme: local SCTP port is hardcoded in bridge offer SDP(Jitsi Meet)
        this.sctpSocket = Sctp.createSocket(5000);

        // Implement output network link for SCTP stack on DTLS transport
        sctpSocket.setLink(new NetworkLink()
        {
            @Override
            public void onConnOut(org.jitsi.sctp4j.SctpSocket s, byte[] packet)
            {
                try
                {
                    if(LOG_SCTP_PACKETS)
                        LibJitsi.getPacketLoggingService().logPacket(
                            PacketLoggingService.ProtocolName.ICE4J,
                            new byte[]{0,0,0,1},
                            5000,
                            new byte[]{0,0,0,2},
                            remoteSctpPort,
                            PacketLoggingService.TransportName.UDP,
                            true,
                            packet);

                    // Send through DTLS transport
                    transport.send(packet, 0, packet.length);
                }
                catch (IOException e)
                {
                    // FIXME: propagate error back to SCTP stack
                    logger.error(e);
                }
            }
        });

        logger.info("Connecting SCTP to port: " + remoteSctpPort);
        sctpSocket.connect(remoteSctpPort);

        // Notify that from now on SCTP connection is considered functional
        notifySctpConnectionReady();

        // Receive loop, breaks when SCTP socket is closed
        while (true)
        {
            int received
                = transport.receive(receiveBuffer, 0, receiveBuffer.length, 0);

            if(received <= 0)
                break;

            // FIXME: fix buffer allocations
            byte[] packet = new byte[received];
            System.arraycopy(receiveBuffer, 0, packet, 0, received);

            if(LOG_SCTP_PACKETS)
                LibJitsi.getPacketLoggingService().logPacket(
                    PacketLoggingService.ProtocolName.ICE4J,
                    new byte[]{0,0,0,2},
                    remoteSctpPort,
                    new byte[]{0,0,0,1},
                    5000,
                    PacketLoggingService.TransportName.UDP,
                    false,
                    packet);

            // Pass network packet to SCTP stack
            sctpSocket.onConnIn(packet);
        }

        // Eventually close the socket, although it should happen from expire()
        sctpSocket.close();
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
            onCtrlPacket(data, sid);
        }
        else if(ppid == WEB_RTC_PPID_STRING || ppid == WEB_RTC_PPID_BIN)
        {
            WebRtcDataStream channel = channels.get(sid);

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
    private void onCtrlPacket(byte[] data, int sid)
    {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(data);

        int messageType = buffer.get();
        if(messageType == MSG_CHANNEL_ACK)
        {
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

            logger.info("WebRTC data channel open request: "
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
    public WebRtcDataStream openChannel(int type, int prio, long reliab,
                                        int sid,  String label)
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
     * FIXME: to be removed once DTLS common code base is extracted
     */
    @Override
    public void setInitiator(boolean initiator)
    {
        if (isInitiator() != initiator)
        {
            dtlsLayer.setSetup(
                    isInitiator()
                        ? DtlsControl.Setup.PASSIVE
                        : DtlsControl.Setup.ACTIVE);
        }

        super.setInitiator(initiator);
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
            if (sctpSocket != null)
            {
                sctpSocket.close();
            }
        }
        finally
        {
            if(datagramTransport != null)
            {
                datagramTransport.close();
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

    /**
     * Implements <tt>DatagramTransport</tt> for DTLS layer.
     * Runs on ICE/UDP socket returned by ice4j.
     *
     * @author Pawel Domas
     */
    class DatagramTransportImpl
        implements DatagramTransport
    {
        /**
         * Remote address selected during ICE negotiations.
         * Used as UDP packets destination address.
         */
        private final InetSocketAddress remoteAddr;

        /**
         * Socket returned by ice4j that represents local endpoint.
         */
        private final DatagramSocket datagramSocket;

        /**
         * Creates new <tt>DatagramTransportImpl</tt> from given
         * <tt>StreamConnector</tt> and <tt>MediaStreamTarget</tt> that server
         * as UDP transport layer for DTLS packets.
         *
         * @param connector <tt>StreamConnector</tt> that describes local
         *                  endpoint.
         * @param target <tt>MediaStreamTarget</tt> that describes remote
         *               endpoint.
         */
        public DatagramTransportImpl(StreamConnector connector,
                                     MediaStreamTarget target)
        {
            this.datagramSocket = connector.getDataSocket();
            this.remoteAddr = target.getDataAddress();
        }

        @Override
        public int getReceiveLimit()
            throws IOException
        {
            return DTLS_BUFFER_SIZE;
        }

        @Override
        public int getSendLimit()
            throws IOException
        {
            return DTLS_BUFFER_SIZE;
        }

        @Override
        public int receive(byte[] bytes, int off, int len, int waitMillis)
            throws IOException
        {
            datagramSocket.setSoTimeout(waitMillis);

            DatagramPacket p = new DatagramPacket(bytes, off, len);
            try
            {
                datagramSocket.receive(p);

                return p.getLength();
            }
            catch (SocketTimeoutException e)
            {
                return -1;
            }
        }

        @Override
        public void send(byte[] bytes, int off, int len)
            throws IOException
        {
            DatagramPacket packet = new DatagramPacket(bytes, off, len);
            packet.setAddress(remoteAddr.getAddress());
            packet.setPort(remoteAddr.getPort());
            datagramSocket.send(packet);
        }

        @Override
        public void close()
            throws IOException
        {
            datagramSocket.close();
        }
    }
}
