package org.jitsi.videobridge.datachannel.protocol;

import java.nio.*;

public class DataChannelBinaryMessage extends DataChannelMessage
{
    public final byte[] data;

    public DataChannelBinaryMessage(byte[] data)
    {
        this.data = data;
    }

    @Override
    public ByteBuffer getBuffer()
    {
        return ByteBuffer.wrap(data);
    }
}
