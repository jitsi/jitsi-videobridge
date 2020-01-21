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

/**
 * Same as {@link DataChannel} but automatically sends the open channel ack message upon creation
 *
 * @author Brian Baldino
 */
public class RemotelyOpenedDataChannel extends DataChannel
{
    /**
     * Initializes a new {@link RemotelyOpenedDataChannel} instance.
     */
    public RemotelyOpenedDataChannel(
            DataChannelStack.DataChannelDataSender dataChannelDataSender,
            Logger parentLogger,
            int channelType, int priority, long reliability, int sid,
            String label)
    {
        super(dataChannelDataSender, parentLogger, channelType, priority,
                reliability, sid, label);
        ready = true;
        sendOpenChannelAck();
    }

    /**
     * Sends an {@link OpenChannelAckMessage} message.
     */
    protected void sendOpenChannelAck()
    {
        OpenChannelAckMessage openChannelAckMessage = new OpenChannelAckMessage();

        ByteBuffer msg = openChannelAckMessage.getBuffer();
        sendData(msg, sid, DataChannelProtocolConstants.WEBRTC_DCEP_PPID);
    }
}
