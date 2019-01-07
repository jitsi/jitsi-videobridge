package org.jitsi.videobridge.datachannel.protocol;

import java.nio.*;
import java.nio.charset.*;

public class OpenChannelMessage extends DataChannelProtocolMessage
{
    public final int channelType;
    public final int priority;
    public final long reliability;
    public final String label;
    public final String protocol;

    public OpenChannelMessage(int channelType, int priority, long reliability, String label, String protocol)
    {
        super(DataChannelProtocolConstants.MSG_TYPE_CHANNEL_OPEN);
        this.channelType = channelType;
        this.priority = priority;
        this.reliability = reliability;
        this.label = label;
        this.protocol = protocol;
    }

    public static OpenChannelMessage parse(byte[] data)
    {
        ByteBuffer buf = ByteBuffer.wrap(data);
        int channelType = buf.get(1);
        int priority = buf.getShort(2);
        long reliability = buf.getInt(4);
        int labelLength = buf.getShort(8);
        int protocolLength = buf.getShort(10);
        String label = null;
        if (labelLength > 0)
        {
            buf.position(12);
            byte[] labelBytes = new byte[labelLength];
            buf.get(labelBytes, 0, labelLength);
            label = new String(labelBytes);
        }
        String protocol = null;
        if (protocolLength > 0)
        {
            buf.position(12 + labelLength);
            byte[] protocolBytes = new byte[protocolLength];
            buf.get(protocolBytes, 0, protocolLength);
            protocol = new String(protocolBytes);
        }
        return new OpenChannelMessage(channelType, priority, reliability, label, protocol);
    }

    @Override
    protected int getSizeBytes()
    {
        byte[] labelBytes = label.getBytes(StandardCharsets.UTF_8);
        int labelLength = Math.min(labelBytes.length, MAX_LABEL_LENGTH);
        byte[] protocolBytes = protocol.getBytes(StandardCharsets.UTF_8);
        int protocolLength = Math.min(protocolBytes.length, MAX_PROTOCOL_LENGTH);

        return super.getSizeBytes() + 11 + labelLength + protocolLength;
    }

    @Override
    public void serialize(ByteBuffer destination)
    {
        super.serialize(destination);
        destination.put((byte)channelType);
        destination.putShort((short)priority);
        destination.putInt((int)reliability);

        byte[] labelBytes = label.getBytes(StandardCharsets.UTF_8);
        int labelLength = Math.min(labelBytes.length, MAX_LABEL_LENGTH);
        byte[] protocolBytes = protocol.getBytes(StandardCharsets.UTF_8);
        int protocolLength = Math.min(protocolBytes.length, MAX_PROTOCOL_LENGTH);
        destination.putShort((short)labelLength);
        destination.putShort((short)protocolLength);
        destination.put(labelBytes, 0, labelLength);
        destination.put(protocolBytes, 0, protocolLength);
    }
}
