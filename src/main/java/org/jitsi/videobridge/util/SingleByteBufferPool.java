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

package org.jitsi.videobridge.util;

import java.nio.*;
import java.util.concurrent.*;

public class SingleByteBufferPool implements ByteBufferPoolImpl
{
    private ArrayBlockingQueue<ByteBuffer> pool =
            new ArrayBlockingQueue<>(1000, true);

    public SingleByteBufferPool(int initialSize)
    {
        for (int i = 0; i < initialSize; ++i)
        {
            pool.add(ByteBuffer.allocate(1500));
        }
    }

    @Override
    public ByteBuffer getBuffer(int size)
    {
        ByteBuffer buf = pool.poll();
        if (buf == null)
        {
            buf = ByteBuffer.allocate(1500);
        }
        buf.limit(size);
        return buf;
    }

    @Override
    public void returnBuffer(ByteBuffer buf)
    {
        pool.offer(buf);
    }

    @Override
    public String getStats()
    {
        StringBuilder sb = new StringBuilder();

        return sb.toString();
    }
}
