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

public class PartitionedByteBufferPool implements ByteBufferPoolImpl
{
    private static int NUM_PARTITIONS = 8;
    private static Partition[] partitions = new Partition[NUM_PARTITIONS];

    public PartitionedByteBufferPool(int initialSize)
    {
        for (int i = 0; i < NUM_PARTITIONS; ++i)
        {
            partitions[i] = new Partition(i, initialSize);
        }
    }

    private static Random random = new Random();

    private static Partition getPartition()
    {
        return partitions[random.nextInt(NUM_PARTITIONS)];
    }

    @Override
    public byte[] getBuffer(int size)
    {
        return getPartition().getBuffer(size);
    }

    @Override
    public void returnBuffer(byte[] buf)
    {
        getPartition().returnBuffer(buf);
    }

    @Override
    public String getStats()
    {
        StringBuilder sb = new StringBuilder();

        for (Partition p : partitions)
        {
            sb.append(p.getStats()).append("\n");
        }

        return sb.toString();
    }

    private class Partition
    {
        private LinkedBlockingQueue<byte[]> pool = new LinkedBlockingQueue<>();
        private final int id;

        private AtomicInteger numNoAllocationNeeded = new AtomicInteger(0);
        private AtomicInteger numAllocations = new AtomicInteger(0);
        // How many times a partition's pool was empty and had to allocate
        private AtomicInteger numEmptyPoolAllocations = new AtomicInteger(0);
        // How many times the pool wasn't empty, but a buffer of sufficient size couldn't be fiound
        private AtomicInteger numWrongSizeAllocations = new AtomicInteger(0);
        // How many times a buffer has been requested from this partition
        private AtomicInteger numRequests = new AtomicInteger(0);
        private AtomicInteger numReturns = new AtomicInteger(0);
        private long firstRequestTime = -1L;
        private long firstReturnTime = -1L;

        public Partition(int id, int initialSize)
        {
            this.id = id;
            for (int i = 0; i < initialSize; ++i)
            {
                pool.add(new byte[1500]);
            }
            System.out.println("Pool " + id + " initial fill done, size is " + pool.size());
        }

        private byte[] getBuffer(int requiredSize)
        {
            if (ByteBufferPool.ENABLE_BOOKKEEPING)
            {
                System.out.println("partition " + id + " request number " + (numRequests.get() + 1) +
                        ", pool has size " + pool.size());
            }
            byte[] buf;
            int numTries = 0;
            while (true) {
                buf = pool.poll();
                if (buf == null) {
                    buf = new byte[1500];
                    if (ByteBufferPool.ENABLE_BOOKKEEPING)
                    {
                        numEmptyPoolAllocations.incrementAndGet();
                        numAllocations.incrementAndGet();
                    }
                    break;
                } else if (buf.length < requiredSize) {
                    if (ByteBufferPool.ENABLE_BOOKKEEPING)
                    {
                        System.out.println("Needed buffer of size " + requiredSize + ", got size " + buf.length +
                                " retrying");
                    }

                    pool.offer(buf);
                    numTries++;
                    if (numTries == 5) {
                        buf = new byte[1500];
                        if (ByteBufferPool.ENABLE_BOOKKEEPING)
                        {
                            numWrongSizeAllocations.incrementAndGet();
                            numAllocations.incrementAndGet();
                        }
                        break;
                    }
                } else {
                    if (ByteBufferPool.ENABLE_BOOKKEEPING)
                    {
                        numNoAllocationNeeded.incrementAndGet();
                    }
                    break;
                }
            }
            if (ByteBufferPool.ENABLE_BOOKKEEPING)
            {
                numRequests.incrementAndGet();
                if (firstRequestTime == -1L)
                {
                    firstRequestTime = System.currentTimeMillis();
                }
                System.out.println("got buffer " + System.identityHashCode(buf) +
                        " from thread " + Thread.currentThread().getId() + ", partition " + id + " now has size " + pool.size());
            }
            return buf;
        }

        private void returnBuffer(byte[] buf)
        {
            if (ByteBufferPool.ENABLE_BOOKKEEPING)
            {
                numReturns.incrementAndGet();
                if (firstReturnTime == -1L)
                {
                    firstReturnTime = System.currentTimeMillis();
                }
                System.out.println("returned buffer " + System.identityHashCode(buf) +
                        " from thread " + Thread.currentThread().getId() + ", partition " + id +
                        " now has size " + pool.size());

            }
            pool.offer(buf);
        }

        public String getStats()
        {
            long now = System.currentTimeMillis();
            return
            "partition " + id +
            "\n  num buffer requests: " + numRequests.get() +
            "\n  buffer request rate: " + (numRequests.get() / ((now - firstRequestTime) / 1000.0)) + " requests/sec" +
            "\n  num buffer returns: " + numReturns.get() +
            "\n  buffer return rate: " + (numReturns.get() / ((now - firstReturnTime) / 1000.0)) + " requests/sec" +
            "\n  num no allocations needed: " + numNoAllocationNeeded.get() +
            "\n  num buffer allocations: " + numAllocations.get() +
            "\n    num empty fallback allocations: " + numEmptyPoolAllocations.get() +
            "\n    num wrong size allocations: " + numWrongSizeAllocations.get() +
            "\n  current size: " + pool.size();
        }
    }
}
