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

import org.jetbrains.annotations.*;
import org.jitsi.rtp.*;

/**
 * @author Brian Baldino
 */
public class DataChannelPacket extends Packet
{
    public final int sid;
    public final int ppid;

    /**
     * Initializes a new {@link DataChannelPacket}.
     */
    public DataChannelPacket(
            byte[] data, int offset, int length, int sid, int ppid)
    {
        super(data, offset, length);
        this.sid = sid;
        this.ppid = ppid;
    }

    /**
     * {@inheritDoc}
     */
    @NotNull
    @Override
    public Packet clone()
    {
        return new DataChannelPacket(
                getBuffer().clone(), getOffset(), getLength(), sid, ppid);
    }
}
