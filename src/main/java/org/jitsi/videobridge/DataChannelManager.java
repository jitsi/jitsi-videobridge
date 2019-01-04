package org.jitsi.videobridge;

//import org.jitsi.sctp4j.SctpDataCallback;
//import org.jitsi.util.Logger;
//import org.jitsi_modified.sctp4j.SctpDataSender;
//
//import java.io.IOException;
//import java.nio.ByteBuffer;
//import java.nio.charset.StandardCharsets;
//import java.util.Map;
//import java.util.concurrent.ConcurrentHashMap;

/**
 * Manage DataChannels for a single participant
 */
//public class DataChannelManager
//    implements SctpDataCallback {
//    private static final Logger logger
//            = Logger.getLogger(DataChannelManager.class);
//
//    private final SctpDataSender dataSender;
//
//    /**
//     * Payload protocol id that identifies binary data in WebRTC data channel.
//     */
//    private static final int WEB_RTC_PPID_BIN = 53;
//
//    /**
//     * Payload protocol id for control data. Used for <tt>WebRtcDataStream</tt>
//     * allocation.
//     */
//    private static final int WEB_RTC_PPID_CTRL = 50;
//
//    /**
//     * Payload protocol id that identifies text data UTF8 encoded in WebRTC data
//     * channels.
//     */
//    private static final int WEB_RTC_PPID_STRING = 51;
//
//    /**
//     * Message type used to acknowledge WebRTC data channel allocation on SCTP
//     * stream ID on which <tt>MSG_OPEN_CHANNEL</tt> message arrives.
//     */
//    private static final int MSG_CHANNEL_ACK = 0x2;
//
//    private static final byte[] MSG_CHANNEL_ACK_BYTES = { MSG_CHANNEL_ACK };
//
//    /**
//     * Message with this type sent over control PPID in order to open new WebRTC
//     * data channel on SCTP stream ID that this message is sent.
//     */
//    private static final int MSG_OPEN_CHANNEL = 0x3;
//
//    /**
//     * The <tt>String</tt> value of the <tt>Protocol</tt> field of the
//     * <tt>DATA_CHANNEL_OPEN</tt> message.
//     */
//    private static final String WEBRTC_DATA_CHANNEL_PROTOCOL
//            = "http://jitsi.org/protocols/colibri";
//
//    private final Map<Integer, WebRtcDataStream2> dataChannels = new ConcurrentHashMap<>();
//
//    private final String id;
//
//    private static int getDataChannelMessageType(ByteBuffer packet) {
//        /* 1 byte unsigned integer */
//        return 0xFF & packet.get(0);
//    }
//
//    private class DataChannelOpenMsg {
//        int channelType;
//        int priority;
//        long reliability;
//        String label;
//        String protocol;
//
//        /**
//         *
//         * @param data a buffer representing the entire packet (including the message type)
//         */
//        public DataChannelOpenMsg(ByteBuffer data) {
//            channelType = 0xFF & data.get(1);
//            priority = 0xFFFF & data.getShort(2);
//            reliability = 0xFFFFFFFFL & data.getInt(4);
//            int labelLength = 0xFFFF & data.getShort(8);
//            int protocolLength = 0xFFFF & data.getShort(10);
//            if (labelLength == 0) {
//                label = "";
//            } else {
//                byte[] labelBytes = new byte[labelLength];
//                data.get(labelBytes, 12, labelLength);
//                label = new String(labelBytes, StandardCharsets.UTF_8);
//            }
//            if (protocolLength == 0) {
//                protocol = "";
//            } else {
//                byte[] protocolBytes = new byte[protocolLength];
//                data.get(protocolBytes, 12 + labelLength, protocolLength);
//                protocol = new String(protocolBytes, StandardCharsets.UTF_8);
//            }
//        }
//
//        public DataChannelOpenMsg(
//                int channelType,
//                int priority,
//                long reliability,
//                String label,
//                String protocol) {
//            this.channelType = channelType;
//            this.priority = priority;
//            this.reliability = reliability;
//            this.label = label;
//            this.protocol = protocol;
//        }
//
//        public ByteBuffer getBuffer() {
//            int labelLength = Math.min(label.getBytes(StandardCharsets.UTF_8).length, 0xFFFF);
//            int protocolLength = Math.min(protocol.getBytes(StandardCharsets.UTF_8).length, 0xFFFF);
//            ByteBuffer buf = ByteBuffer.allocate(12 + labelLength + protocolLength);
//            buf.put(0, (byte)MSG_OPEN_CHANNEL);
//            buf.put(1, (byte)channelType);
//            buf.putShort(2, (short)priority);
//            buf.putInt(4, (int)reliability);
//            buf.putShort(8, (short)labelLength);
//            buf.putShort(10, (short)protocolLength);
//            if (labelLength > 0) {
//                buf.position(12);
//                buf.put(label.getBytes(StandardCharsets.UTF_8), 0, labelLength);
//            }
//            if (protocolLength > 0) {
//                buf.position(12 + labelLength);
//                buf.put(protocol.getBytes(StandardCharsets.UTF_8), 0, protocolLength);
//            }
//            buf.rewind();
//            return buf;
//        }
//
//        @Override
//        public String toString() {
//            return "channelType: " + channelType + ", " +
//                    "priority: " + priority + ", " +
//                    "reliability: " + reliability + ", " +
//                    "label: " + label + ", " +
//                    "protocol: " + protocol;
//        }
//    }
//
//    public DataChannelManager(String id, SctpDataSender dataSender) {
//        this.id = id;
//        this.dataSender = dataSender;
//    }
//
//    private void onDataChannelAck(int sid) {
//        logger.info(id + ": ack received for sid " + sid);
//        WebRtcDataStream2 channel = dataChannels.get(sid);
//        if (channel == null) {
//            logger.error(id + ": no channel found for sid " + sid);
//            return;
//        }
//        if (!channel.isAcknowledged()) {
//            channel.ackReceived();
//            notifyChannelOpened(channel);
//        }
//    }
//
//    private void sendOpenChannelAck(int sid) {
//        try {
//            if (dataSender.send(MSG_CHANNEL_ACK_BYTES, true, sid, WEB_RTC_PPID_CTRL) != MSG_CHANNEL_ACK_BYTES.length) {
//                logger.error(id + ": Failed to send open channel ack for sid " + sid);
//            }
//        } catch (IOException e) {
//            logger.error(id + ": Failed to send open channel ack for sid " + sid + ": " + e);
//        }
//    }
//
//    private void onDataChannelOpen(DataChannelOpenMsg openMsg, int sid) {
//        logger.info(id + ": receive data channel open for sid " + sid + ": " + openMsg);
//
//        WebRtcDataStream2 newChannel
//                = new WebRtcDataStream2(dataSender, sid, openMsg.label, true);
//        WebRtcDataStream2 previousChannel = dataChannels.put(sid, newChannel);
//        if (previousChannel != null) {
//            logger.error(id + ": data channel for sid " + sid + " already exists!");
//        }
//        sendOpenChannelAck(sid);
//        notifyChannelOpened(newChannel);
//    }
//
//    private void notifyChannelOpened(WebRtcDataStream2 channel) {
//        //TODO
//    }
//
//    private void onDataChannelCtrlPacket(ByteBuffer packet, int sid) {
//        int messageType = getDataChannelMessageType(packet);
//        switch (messageType) {
//            case MSG_CHANNEL_ACK: {
//                onDataChannelAck(sid);
//                break;
//            }
//            case MSG_OPEN_CHANNEL: {
//                DataChannelOpenMsg openMsg = new DataChannelOpenMsg(packet);
//                onDataChannelOpen(openMsg, sid);
//                break;
//            }
//            default: {
//                logger.error("Unknown data channel ctrl message type: " + messageType);
//            }
//        }
//    }
//
//    private void onDataChannelData(ByteBuffer data, int sid, long ppid) {
//        WebRtcDataStream2 channel = dataChannels.get(sid);
//        if (channel == null) {
//            logger.error(id + ": No channel found for sid " + sid +
//                    " when trying to process incoming data");
//            return;
//        }
//        if (ppid == WEB_RTC_PPID_STRING) {
//            String str = new String(data.array(), StandardCharsets.UTF_8);
//            channel.onStringMsg(str);
//        } else {
//            channel.onBinaryMsg(data.array());
//        }
//    }
//
//    /**
//     * Handle incoming SCTP data after it's been processed by the SCTP stack
//     */
//    @Override
//    public void onSctpPacket(
//            byte[] data,
//            /*
//             * Unique stream identifier
//             */
//            int streamId,
//            /*
//             * This value contains the stream sequence number that the
//             * remote endpoint placed in the DATA chunk.  For fragmented
//             * messages, this is the same number for all deliveries of the
//             * message (if more than one recvmsg() is needed to read the
//             * message).
//             */
//            int streamSequenceNumber,
//            /*
//             * a Transmission Sequence Number (TSN) that was assigned to one
//             * of the SCTP DATA chunks.
//             */
//            int transmissionSequenceNumber,
//            /*
//             * This value is the same information that was passed by the
//             *  upper layer in the peer application (used by WebRTC as a message type field)
//             */
//            long ppid,
//            int context,
//            int flags) {
//        if (ppid == WEB_RTC_PPID_CTRL) {
//            onDataChannelCtrlPacket(ByteBuffer.wrap(data), streamId);
//        } else if (ppid == WEB_RTC_PPID_STRING || ppid == WEB_RTC_PPID_BIN) {
//            onDataChannelData(ByteBuffer.wrap(data), streamId, ppid);
//        }
//    }
//
//    /**
//     * Opens new WebRTC data channel using specified parameters.
//     * @param type channel type as defined in control protocol description.
//     *             Use 0 for "reliable".
//     * @param prio channel priority. The higher the number, the lower
//     *             the priority.
//     * @param reliab Reliability Parameter<br/>
//     *
//     * This field is ignored if a reliable channel is used.
//     * If a partial reliable channel with limited number of
//     * retransmissions is used, this field specifies the number of
//     * retransmissions.  If a partial reliable channel with limited
//     * lifetime is used, this field specifies the maximum lifetime in
//     * milliseconds.  The following table summarizes this:<br/></br>
//
//    +------------------------------------------------+------------------+
//    | Channel Type                                   |   Reliability    |
//    |                                                |    Parameter     |
//    +------------------------------------------------+------------------+
//    | DATA_CHANNEL_RELIABLE                          |     Ignored      |
//    | DATA_CHANNEL_RELIABLE_UNORDERED                |     Ignored      |
//    | DATA_CHANNEL_PARTIAL_RELIABLE_REXMIT           |  Number of RTX   |
//    | DATA_CHANNEL_PARTIAL_RELIABLE_REXMIT_UNORDERED |  Number of RTX   |
//    | DATA_CHANNEL_PARTIAL_RELIABLE_TIMED            |  Lifetime in ms  |
//    | DATA_CHANNEL_PARTIAL_RELIABLE_TIMED_UNORDERED  |  Lifetime in ms  |
//    +------------------------------------------------+------------------+
//     * @param sid SCTP stream id that will be used by new channel
//     *            (it must not be already used).
//     * @param label text label for the channel.
//     * @return new instance of <tt>WebRtcDataStream</tt> that represents opened
//     *         WebRTC data channel.
//     * @throws IOException if IO error occurs.
//     */
//    public WebRtcDataStream2 openChannel(
//            int type, int prio, long reliab, int sid, String label)
//            throws IOException
//    {
//        logger.info(id + ": Opening DataChannel with sid " + sid);
//        DataChannelOpenMsg openMsg = new DataChannelOpenMsg(type, prio, reliab, label, WEBRTC_DATA_CHANNEL_PROTOCOL);
//        return dataChannels.computeIfAbsent(sid, key -> {
//            WebRtcDataStream2 newChannel = new WebRtcDataStream2(dataSender, sid, label, false);
//            try {
//                dataSender.send(openMsg.getBuffer().array(), true, sid, WEB_RTC_PPID_CTRL);
//                return newChannel;
//            } catch (IOException e) {
//                logger.error(id + ": Error sending Data Channel open: " + e);
//                e.printStackTrace();
//                return null;
//            }
//        });
//    }
//}
