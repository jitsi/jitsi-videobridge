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
 * https://tools.ietf.org/html/draft-ietf-rtcweb-data-protocol-08#section-5
 * Base class for Data Channel 'protocol' messages; that is, messages
 * used in Data Channel setup.
 *
 * @author Brian Baldino
 */
public class DataChannelProtocolMessage extends DataChannelMessage
{
    /**
     * The only common field is the message type, so we'll call that the 'header'
     */
    private static int HEADER_SIZE_BYTES = 1;
    protected static int MAX_LABEL_LENGTH = 0xFFFF;
    protected static int MAX_PROTOCOL_LENGTH = 0xFFFF;

    private final int messageType;

    /**
     * Initializes a new {@link DataChannelProtocolMessage} instance.
     * @param messageType the message type.
     */
    public DataChannelProtocolMessage(int messageType)
    {
        this.messageType = messageType;
    }

    protected int getSizeBytes()
    {
        return HEADER_SIZE_BYTES;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ByteBuffer getBuffer()
    {
        ByteBuffer buf = ByteBuffer.allocate(getSizeBytes());
        serialize(buf);
        return buf;
    }

    public void serialize(ByteBuffer destination)
    {
        destination.put((byte)messageType);
    }
}
