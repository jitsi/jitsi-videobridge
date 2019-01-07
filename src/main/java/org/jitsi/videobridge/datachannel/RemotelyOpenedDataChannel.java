package org.jitsi.videobridge.datachannel;

import org.jitsi.videobridge.datachannel.protocol.*;
import org.jitsi_modified.sctp4j.*;

import java.nio.*;

/**
 * Same as {@link DataChannel} but automatically sends the open channel ack message upon creation
 */
public class RemotelyOpenedDataChannel extends DataChannel
{
    public RemotelyOpenedDataChannel(SctpSocket sctpSocket, int channelType, int priority, long reliability, int sid, String label)
    {
        super(sctpSocket, channelType, priority, reliability, sid, label);
        ready = true;
        sendOpenChannelAck();
    }

    protected void sendOpenChannelAck()
    {
        OpenChannelAckMessage openChannelAckMessage = new OpenChannelAckMessage();

        ByteBuffer msg = openChannelAckMessage.getBuffer();
        sctpSocket.send(msg, true, sid, DataChannelProtocolConstants.WEBRTC_DCEP_PPID);
    }
}
