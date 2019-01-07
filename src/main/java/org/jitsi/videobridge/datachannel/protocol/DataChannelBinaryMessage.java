package org.jitsi.videobridge.datachannel.protocol;

public class DataChannelBinaryMessage extends DataChannelMessage
{
    public final byte[] data;

    public DataChannelBinaryMessage(byte[] data)
    {
        this.data = data;
    }
}
