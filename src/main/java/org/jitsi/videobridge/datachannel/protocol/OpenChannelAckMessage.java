package org.jitsi.videobridge.datachannel.protocol;

public class OpenChannelAckMessage extends DataChannelProtocolMessage
{
    public OpenChannelAckMessage()
    {
        super(DataChannelProtocolConstants.MSG_TYPE_CHANNEL_ACK);
    }
}
