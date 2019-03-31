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
import java.nio.charset.*;

/**
 * @author Brian Baldino
 */
public class DataChannelStringMessage extends DataChannelMessage
{
    public final String data;

    /**
     * Initializes a new {@link DataChannelStringMessage} instance.
     * @param data
     */
    public DataChannelStringMessage(String data)
    {
        this.data = data;
    }

    /**
     * Parses a {@link DataChannelStringMessage} from a byte array.
     * @param data
     * @return
     */
    public static DataChannelStringMessage parse(byte[] data)
    {
        String stringData = new String(data, StandardCharsets.UTF_8);
        return new DataChannelStringMessage(stringData);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ByteBuffer getBuffer()
    {
        return ByteBuffer.wrap(data.getBytes(StandardCharsets.UTF_8));
    }
}
