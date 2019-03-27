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

import org.jitsi.util.*;
import org.json.simple.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Implements a {@link ByteBufferPoolImpl}.
 *
 * @author Brian Baldino
 * @author Boris Grozev
 */
public class ByteBufferPool
{
    /**
     * The underlying pool implementation.
     */
    private static final PartitionedByteBufferPool poolImpl
            = new PartitionedByteBufferPool();

    /**
     * The {@link Logger}
     */
    private static final Logger logger = Logger.getLogger(ByteBufferPool.class);

    /**
     * TODO Brian
     */
    private static final Map<Integer, StackTraceElement[]> bookkeeping
            = new ConcurrentHashMap<>();

    /**
     * Whether to enable or disable book keeping.
     */
    public static final Boolean ENABLE_BOOKKEEPING = false;

    /**
     * Total number of buffers requested.
     */
    private static final AtomicInteger numRequests = new AtomicInteger(0);

    /**
     * Total number of buffers returned.
     */
    private static final AtomicInteger numReturns = new AtomicInteger(0);

    /**
     * Gets the current thread ID.
     */
    private static long threadId()
    {
        return Thread.currentThread().getId();
    }

    /**
     * Gets the current stack trace.
     */
    private static StackTraceElement[] getStackTrace()
    {
        return Thread.currentThread().getStackTrace();
    }

    /**
     * Gets the current stack trace as a multi-line string.
     */
    private static String getStackTraceAsString()
    {
        StringBuilder sb = new StringBuilder();
        for (StackTraceElement ste : getStackTrace())
        {
            sb.append(ste.toString()).append("\n");
        }
        return sb.toString();
    }

    /**
     * Returns a buffer from the pool.
     *
     * @param size the minimum size.
     */
    public static byte[] getBuffer(int size)
    {
        numRequests.incrementAndGet();
        byte[] buf = poolImpl.getBuffer(size);
        if (ENABLE_BOOKKEEPING)
        {
            bookkeeping.put(System.identityHashCode(buf), getStackTrace());
            logger.info("Thread " + threadId() + " got array "
                    + System.identityHashCode(buf));
        }
        return buf;
    }

    /**
     * Returns a buffer to the pool.
     * @param buf
     */
    public static void returnBuffer(byte[] buf)
    {
        numReturns.incrementAndGet();
        poolImpl.returnBuffer(buf);

        if (ENABLE_BOOKKEEPING)
        {
            logger.info("Thread " + threadId() + " returned array "
                    + System.identityHashCode(buf));
            Integer arrayId = System.identityHashCode(buf);
            if (bookkeeping.remove(arrayId) == null)
            {
                logger.info("Thread " + threadId()
                        + " returned a buffer we didn't give out from\n"
                        + getStackTraceAsString());
            }
        }
    }

    /**
     * Gets a JSON representation of the statistics about the pool.
     */
    public static JSONObject getStatsJson()
    {
        JSONObject stats = new JSONObject();
        stats.put("outstanding_buffers", bookkeeping.size());
        stats.put("num_requests", numRequests.get());
        stats.put("num_returns", numReturns.get());
        poolImpl.addStats(stats);

        return stats;
    }

    /**
     * Enables of disables tracking of statistics for the pool.
     * @param enable whether to enable it or disable it.
     */
    public static void enableStatistics(boolean enable)
    {
        poolImpl.enableStatistics(enable);
    }
}
