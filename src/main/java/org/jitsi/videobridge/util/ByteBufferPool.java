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

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class ByteBufferPool
{
    private static ByteBufferPoolImpl poolImpl = new PartitionedByteBufferPool(100);
//    private static ByteBufferPoolImpl poolImpl = new SingleByteBufferPool(100);

    private static Map<Integer, StackTraceElement[]> bookkeeping = new ConcurrentHashMap<>();
    public static final Boolean enabledBookkeeping = false;

    private static AtomicInteger numBuffersOut = new AtomicInteger(0);
    private static AtomicInteger numBuffersIn = new AtomicInteger(0);

    public static byte[] getBuffer(int size)
    {
        byte[] buf = poolImpl.getBuffer(size);
        if (enabledBookkeeping)
        {
            bookkeeping.put(System.identityHashCode(buf), Thread.currentThread().getStackTrace());
            numBuffersOut.incrementAndGet();
            System.out.println("Thread " + Thread.currentThread().getId() + " got array " + System.identityHashCode(buf));
        }
        return buf;
    }

    public static void returnBuffer(byte[] buf)
    {
        poolImpl.returnBuffer(buf);
        if (enabledBookkeeping)
        {
            System.out.println("Thread " + Thread.currentThread().getId() + " returned array " + System.identityHashCode(buf));
            Integer arrayId = System.identityHashCode(buf);
            bookkeeping.remove(arrayId);
            numBuffersIn.incrementAndGet();
        }
    }

    public static String getStats() {
        StringBuilder sb = new StringBuilder();
        sb.append("there are ~" + bookkeeping.size() + " outstanding buffers\n");
        sb.append("num buffers given out: " + numBuffersOut.get() + "\n");
        sb.append("num buffers returned: " + numBuffersIn.get() + "\n");

        bookkeeping.forEach((arrayId, stacktrace) -> {
            sb.append(arrayId).append(" acquired from:\n");
            for (StackTraceElement stackTraceElement : stacktrace)
            {
                sb.append(stackTraceElement.toString()).append("\n");
            }
        });
        return sb.toString();
    }
}
