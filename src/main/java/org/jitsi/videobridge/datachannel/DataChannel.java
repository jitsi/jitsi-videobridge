package org.jitsi.videobridge.datachannel;

import org.jitsi.rtp.extensions.*;
import org.jitsi.videobridge.datachannel.protocol.*;
import org.jitsi_modified.sctp4j.*;

import java.nio.*;

public class DataChannel
{
    protected final SctpSocket sctpSocket;
    protected final int channelType;
    protected final int priority;
    protected final long reliability;
    protected final int sid;
    protected final String label;

    private DataChannelStack.DataChannelEventListener eventListener;

    //TODO: all data channel instances will be sharing the socket, so make sure it's thread safe
    public DataChannel(SctpSocket sctpSocket, int channelType, int priority, long reliability, int sid, String label)
    {
        this.sctpSocket = sctpSocket;
        this.channelType = channelType;
        this.priority = priority;
        this.reliability = reliability;
        this.sid = sid;
        this.label = label;
    }

    public void open()
    {
        OpenChannelMessage openMessage = new OpenChannelMessage(
                channelType,
                priority,
                reliability,
                label,
                DataChannelProtocolConstants.PROTOCOL_STRING);

        ByteBuffer msg = openMessage.getBuffer();
        if (sctpSocket.send(msg, true, sid, DataChannelProtocolConstants.WEBRTC_DCEP_PPID) < 0)
        {
            System.out.println("Error sending data channel open message");
        }
    }

    public void onDataChannelEvents(DataChannelStack.DataChannelEventListener listener)
    {
        this.eventListener = listener;
    }

    public void onIncomingMsg(DataChannelMessage message)
    {
        if (message instanceof OpenChannelAckMessage)
        {
            eventListener.onDataChannelOpened();
        }
        else if (message instanceof DataChannelStringMessage)
        {
            DataChannelStringMessage dataChannelStringMessage = (DataChannelStringMessage)message;
            System.out.println("Received data channel string message: " + dataChannelStringMessage.data);
        }
        else if (message instanceof DataChannelBinaryMessage)
        {
            DataChannelBinaryMessage dataChannelBinaryMessage = (DataChannelBinaryMessage)message;
            System.out.println("Received data channel binary message: " +
                    ByteBufferKt.toHex(ByteBuffer.wrap(dataChannelBinaryMessage.data)));
        }
    }
}
