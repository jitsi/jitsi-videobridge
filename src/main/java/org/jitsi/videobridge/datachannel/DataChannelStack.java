/*
 * Copyright @ 2018 - present 8x8, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jitsi.videobridge.datachannel;

import org.jitsi.utils.logging2.*;
import org.jitsi.videobridge.datachannel.protocol.*;

import java.nio.*;
import java.util.*;

/**
 * We need the stack to look at all incoming messages so that it can listen for an 'open channel'
 * message from the remote side
 *
 * Handles DataChannel negotiation and the routing of all Data Channel messages
 * to specific {@link DataChannel} instances.
 *
 * @author Brian Baldino
 */
//TODO: revisit thread safety in here
//TODO: add an arbitrary ID
public class DataChannelStack
{
    private final Map<Integer, DataChannel> dataChannels = new HashMap<>();
    private final DataChannelDataSender dataChannelDataSender;
    private final Logger logger;
    private DataChannelStackEventListener listener;

    /**
     * Initializes a new {@link DataChannelStack} with a specific sender.
     * @param dataChannelDataSender the sender.
     */
    public DataChannelStack(DataChannelDataSender dataChannelDataSender, Logger parentLogger)
    {
        this.dataChannelDataSender = dataChannelDataSender;
        logger = parentLogger.createChildLogger(DataChannelStack.class.getName());
    }

    /**
     * Handles a received packet.
     */
    public void onIncomingDataChannelPacket(ByteBuffer data, int sid, int ppid)
    {
        logger.debug(() -> "Data channel stack received SCTP message");
        DataChannelMessage message = DataChannelProtocolMessageParser.parse(data.array(), ppid);
        if (message instanceof OpenChannelMessage)
        {
            logger.info("Received data channel open message");
            OpenChannelMessage openChannelMessage = (OpenChannelMessage)message;
            // Remote side wants to open a channel
            DataChannel dataChannel = new RemotelyOpenedDataChannel(
                    dataChannelDataSender,
                    logger,
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
                logger.error("Could not find data channel for sid " + sid);
                return;
            }
            dataChannel.onIncomingMsg(message);
        }
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
     */
    public DataChannel createDataChannel(int channelType, int priority, long reliability, int sid, String label)
    {
        synchronized (dataChannels) {
            DataChannel dataChannel = new DataChannel(
                    dataChannelDataSender, logger, channelType, priority, reliability, sid, label);
            dataChannels.put(sid, dataChannel);
            return dataChannel;
        }
    }

    /**
     * TODO: these 2 feel a bit awkward since they are so similar, but we use
     * a different one for a remote channel (fired by the stack) and a
     * locally-created channel (fired by the data channel itself).
     */
    public interface DataChannelStackEventListener
    {
        /**
         * The data channel was opened by the remote side.
         */
        void onDataChannelOpenedRemotely(DataChannel dataChannel);
    }

    public interface DataChannelEventListener
    {
        /**
         * The data channel was opened.
         */
        void onDataChannelOpened();
    }

    public interface DataChannelMessageListener
    {
        /**
         * A message received.
         */
        void onDataChannelMessage(DataChannelMessage dataChannelMessage);
    }

    public interface DataChannelDataSender
    {
        /**
         * Sends a message.
         */
        int send(ByteBuffer data, int sid, int ppid);
    }
}
