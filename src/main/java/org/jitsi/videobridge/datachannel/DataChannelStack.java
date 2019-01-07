package org.jitsi.videobridge.datachannel;

import org.jitsi.videobridge.datachannel.protocol.*;
import org.jitsi_modified.sctp4j.*;

import java.util.*;

/**
 * We need the stack to look at all incoming messages so that it can listen for an 'open channel'
 * message from the remote side
 */
//TODO: revisit thread safety in here
public class DataChannelStack
{
    private final Map<Integer, DataChannel> dataChannels = new HashMap<>();
    private final SctpSocket sctpSocket;
    private DataChannelStackEventListener listener;

    public DataChannelStack(SctpSocket sctpSocket)
    {
        this.sctpSocket = sctpSocket;
        // We assume right now that the only thing of interest coming out of an SCTP socket will be
        // data channel data (since that's all we currently use them for).  If that changed, we'd want to
        // put something else as the SCTP data callback which could then demux the data to a datachannel
        // and whatever else sent/received data over SCTP.
        sctpSocket.dataCallback = (data, sid, ssn, tsn, ppid, context, flags) -> {
            System.out.println("Data channel stack reveived SCTP message");
            DataChannelMessage message = DataChannelProtocolMessageParser.parse(data, ppid);
            if (message instanceof OpenChannelMessage)
            {
                System.out.println("Received data channel open message");
                OpenChannelMessage openChannelMessage = (OpenChannelMessage)message;
                // Remote side wants to open a channel
                DataChannel dataChannel = new RemotelyOpenedDataChannel(
                        sctpSocket,
                        openChannelMessage.channelType,
                        openChannelMessage.priority,
                        openChannelMessage.reliability,
                        sid,
                        openChannelMessage.label);
                dataChannels.put(sid, dataChannel);
                listener.onDataChannelOpenedRemotely(dataChannel);
            }
            else
            {
                DataChannel dataChannel= dataChannels.get(sid);
                if (dataChannel == null)
                {
                    System.out.println("Could not find data channel for sid " + sid);
                    return;
                }
                dataChannel.onIncomingMsg(message);
            }
        };
    }

    public void onDataChannelStackEvents(DataChannelStackEventListener listener)
    {
        this.listener = listener;
    }
    /**
     * Opens new WebRTC data channel using specified parameters.
     * @param channelType channel type as defined in control protocol description.
     *             Use 0 for "reliable".
     * @param priority channel priority. The higher the number, the lower
     *             the priority.
     * @param reliability Reliability Parameter<br/>
     *
     * This field is ignored if a reliable channel is used.
     * If a partial reliable channel with limited number of
     * retransmissions is used, this field specifies the number of
     * retransmissions.  If a partial reliable channel with limited
     * lifetime is used, this field specifies the maximum lifetime in
     * milliseconds.  The following table summarizes this:<br/></br>

    +------------------------------------------------+------------------+
    | Channel Type                                   |   Reliability    |
    |                                                |    Parameter     |
    +------------------------------------------------+------------------+
    | DATA_CHANNEL_RELIABLE                          |     Ignored      |
    | DATA_CHANNEL_RELIABLE_UNORDERED                |     Ignored      |
    | DATA_CHANNEL_PARTIAL_RELIABLE_REXMIT           |  Number of RTX   |
    | DATA_CHANNEL_PARTIAL_RELIABLE_REXMIT_UNORDERED |  Number of RTX   |
    | DATA_CHANNEL_PARTIAL_RELIABLE_TIMED            |  Lifetime in ms  |
    | DATA_CHANNEL_PARTIAL_RELIABLE_TIMED_UNORDERED  |  Lifetime in ms  |
    +------------------------------------------------+------------------+
     * @param sid SCTP stream id that will be used by new channel
     *            (it must not be already used).
     * @param label text label for the channel.
     * @return new instance of <tt>WebRtcDataStream</tt> that represents opened
     *         WebRTC data channel.
     * @throws IOException if IO error occurs.
     */
    public DataChannel createDataChannel(int channelType, int priority, long reliability, int sid, String label)
    {
        synchronized (dataChannels) {
            DataChannel dataChannel = new DataChannel(sctpSocket, channelType, priority, reliability, sid, label);
            dataChannels.put(sid, dataChannel);
            return dataChannel;
        }
    }

    //TODO: these 2 feel a bit awkward since they are so similar, but we use a different one for a remote
    // channel (fired by the stack) and a locally-created channel (fired by the data channel itself).
    public interface DataChannelStackEventListener {
        void onDataChannelOpenedRemotely(DataChannel dataChannel);
    }

    public interface DataChannelEventListener {
        void onDataChannelOpened();
    }
}
