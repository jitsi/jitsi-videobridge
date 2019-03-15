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
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class PartitionedByteBufferPool implements ByteBufferPoolImpl
{
    private static int NUM_PARTITIONS = 8;
    private List<LinkedBlockingQueue<byte[]>> partitions = new ArrayList<>(NUM_PARTITIONS);

    public PartitionedByteBufferPool(int initialSize)
    {
        for (int i = 0; i < NUM_PARTITIONS; ++i)
        {
            partitions.add(new LinkedBlockingQueue<>());
        }
        partitions.forEach(partition -> {
            for (int i = 0; i < initialSize; ++i)
            {
                partition.add(new byte[1500]);
            }
        });
    }

    private static Random random = new Random();

    private static int getPartition()
    {
//        return (int) (Thread.currentThread().getId() % NUM_PARTITIONS);
        return random.nextInt(NUM_PARTITIONS);
    }

    private byte[] doGetBuffer(int requiredSize)
    {
        int partition = getPartition();
        LinkedBlockingQueue<byte[]> pool = partitions.get(partition);
        byte[] buf = pool.poll();
        if (buf == null) {
            buf = new byte[1500];
        }
        if (ByteBufferPool.enabledBookkeeping)
        {
            System.out.println("got buffer " + System.identityHashCode(buf) +
                    " from thread " + Thread.currentThread().getId() + ", partition " + partition + " now has size " + pool.size());
        }

        return buf;
    }

    @Override
    public byte[] getBuffer(int size)
    {
        //        System.out.println("got buffer, pool size is now " + pool.size());
//        StackTraceElement callingFunction = Thread.currentThread().getStackTrace()[2];
//        System.out.println("Got array " + System.identityHashCode(buf.array()) + " in " + callingFunction.toString());
        return doGetBuffer(size);
    }

    @Override
    public void returnBuffer(byte[] buf)
    {
//        StackTraceElement callingFunction = Thread.currentThread().getStackTrace()[2];
//        System.out.println("Returned array " + System.identityHashCode(buf.array()) + " from " + callingFunction.toString());
//        System.out.println("Returned array " + System.identityHashCode(buf.array()));
        if (buf.length == 1500) {
            int partition = getPartition();
            LinkedBlockingQueue<byte[]> pool = partitions.get(partition);
            pool.offer(buf);
            if (ByteBufferPool.enabledBookkeeping)
            {
                System.out.println("returned buffer " + System.identityHashCode(buf) +
                        " from thread " + Thread.currentThread().getId() + ", partition " + partition +
                        " now has size " + pool.size());
            }
        }
        else {
            if (ByteBufferPool.enabledBookkeeping)
            {
                System.out.println("ignoring returned array of size " + buf.length);
            }
        }
    }

    @Override
    public String getStats()
    {
        StringBuilder sb = new StringBuilder();

        return sb.toString();
    }
}
