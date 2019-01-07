package org.jitsi.videobridge.datachannel.protocol;

import java.nio.*;

public class DataChannelProtocolMessageParser
{
    private static int getMessageType(byte[] data)
    {
        return ByteBuffer.wrap(data).get(0) & 0xFF;
    }

    public static DataChannelMessage parse(byte[] data, long ppid)
    {
        if (ppid == DataChannelProtocolConstants.WEBRTC_DCEP_PPID)
        {
            int messageType = getMessageType(data);
            switch (messageType)
            {
                case DataChannelProtocolConstants.MSG_TYPE_CHANNEL_ACK: {
                    return new OpenChannelAckMessage();
                }
                case DataChannelProtocolConstants.MSG_TYPE_CHANNEL_OPEN: {
                    return OpenChannelMessage.parse(data);
                }
                default: {
                    System.out.println("Unrecognized datachannel control message type: " + messageType);
                    return null;
                }
            }
        }
        else if (ppid == DataChannelProtocolConstants.WEBRTC_PPID_STRING)
        {
            return DataChannelStringMessage.parse(data);
        }
        else if (ppid == DataChannelProtocolConstants.WEBRTC_PPID_BIN)
        {
            return new DataChannelBinaryMessage(data);
        }
        else
        {
            System.out.println("Unrecognized data channel ppid: " + ppid);
        }
        return null;
    }
}
