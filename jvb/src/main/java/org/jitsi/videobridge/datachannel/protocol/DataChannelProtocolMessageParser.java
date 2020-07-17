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

package org.jitsi.videobridge.datachannel.protocol;

import java.nio.*;

/**
 * Parses Data Channel protocol messages
 *
 * @author Brian Baldino
 */
public class DataChannelProtocolMessageParser
{
    /**
     * Reads the message type from a byte array.
     * @param data
     * @return
     */
    private static int getMessageType(byte[] data)
    {
        return ByteBuffer.wrap(data).get(0) & 0xFF;
    }

    /**
     * TODO(brian): change data to be a ByteBuffer
     */
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
                    // TODO
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
            // TODO
            System.out.println("Unrecognized data channel ppid: " + ppid);
        }
        return null;
    }
}
