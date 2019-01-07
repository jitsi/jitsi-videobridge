package org.jitsi.videobridge.datachannel.protocol;

import java.nio.*;

/**
 * https://tools.ietf.org/html/draft-ietf-rtcweb-data-protocol-08#section-5
 */
public class DataChannelProtocolMessage extends DataChannelMessage
{
    /**
     * The only common field is the message type, so we'll call that the 'header'
     */
    private static int HEADER_SIZE_BYTES = 1;
    protected static int MAX_LABEL_LENGTH = 0xFFFF;
    protected static int MAX_PROTOCOL_LENGTH = 0xFFFF;

    private final int messageType;

    public DataChannelProtocolMessage(int messageType) {
        this.messageType = messageType;
    }

    protected int getSizeBytes() {
        return HEADER_SIZE_BYTES;
    }

    public ByteBuffer getBuffer()
    {
        ByteBuffer buf = ByteBuffer.allocate(getSizeBytes());
        serialize(buf);
        return buf;
    }

    public void serialize(ByteBuffer destination)
    {
        destination.put((byte)messageType);
    }
}
