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
 * @author Brian Baldino
 */
public class DataChannelBinaryMessage extends DataChannelMessage
{
    public final byte[] data;

    /**
     * Initializes a new {@link DataChannelBinaryMessage} from a byte array.
     * @param data the byte array.
     */
    public DataChannelBinaryMessage(byte[] data)
    {
        this.data = data;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ByteBuffer getBuffer()
    {
        return ByteBuffer.wrap(data);
    }
}
