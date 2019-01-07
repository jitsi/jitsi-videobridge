package org.jitsi.videobridge.datachannel.protocol;

public class DataChannelProtocolConstants
{
    /**
     * The <tt>String</tt> value of the <tt>Protocol</tt> field of the
     * <tt>DATA_CHANNEL_OPEN</tt> message.
     */
    public static final String PROTOCOL_STRING = "http://jitsi.org/protocols/colibri";

    /**
     * DataChannel message types
     //TODO: enum?
     */
    public static final int MSG_TYPE_CHANNEL_OPEN = 0x3;
    public static final int MSG_TYPE_CHANNEL_ACK = 0x2;

    /**
     * Channel types
     * TODO: enum?
     */
    public static final int RELIABLE = 0x00;
    public static final int RELIABLE_UNORDERED = 0x80;
    public static final int PARTIAL_RELIABLE_REXMIT = 0x01;
    public static final int PARTIAL_RELIABLE_REXMIT_UNORDERED = 0x81;
    public static final int PARTIAL_RELIABLE_TIMED = 0x02;
    public static final int PARTIAL_RELIABLE_TIMED_UNORDERED = 0x82;

    /**
     * This is the SCTP PPID used for datachannel establishment protocol
     * https://tools.ietf.org/html/draft-ietf-rtcweb-data-protocol-08#section-8.1
     */
    public static final int WEBRTC_DCEP_PPID = 50;

    /**
     * Payload protocol id that identifies text data UTF8 encoded in WebRTC data
     * channels.
     */
    public static final int WEBRTC_PPID_STRING = 51;

    /**
     * Payload protocol id that identifies binary data in WebRTC data channel.
     */
    public static final int WEBRTC_PPID_BIN = 53;
}
