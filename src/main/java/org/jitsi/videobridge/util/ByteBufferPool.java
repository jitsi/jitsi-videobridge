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

import org.jetbrains.annotations.*;
import org.jitsi.nlj.util.*;
import org.jitsi.utils.logging2.*;
import org.json.simple.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * A pool of reusable byte[]
 *
 * @author Brian Baldino
 * @author Boris Grozev
 */
public class ByteBufferPool
{
    /**
     * The pool of buffers is segmented by size to make the overall memory
     * footprint smaller.
     * These thresholds were chosen to minimize the used memory for a trace of
     * requests from a test conference. Using finer segmentation (4 thresholds)
     * results in only a marginal improvement.
     */
    private static int T1 = 220;
    private static int T2 = 775;
    private static int T3 = 1240;

    /**
     * The pool of buffers with size <= T1
     */
    private static final PartitionedByteBufferPool pool1
            = new PartitionedByteBufferPool(T1);
    /**
     * The pool of buffers with size in (T1, T2]
     */
    private static final PartitionedByteBufferPool pool2
            = new PartitionedByteBufferPool(T2);
    /**
     * The pool of buffers with size in (T2, T3]
     */
    private static final PartitionedByteBufferPool pool3
            = new PartitionedByteBufferPool(T3);

    /**
     * The {@link Logger}
     */
    private static final Logger logger = new LoggerImpl(ByteBufferPool.class.getName());

    /**
     * A debug data structure which tracks outstanding buffers and tracks from where (via
     * a stack trace) they were requested and returned.
     */
    private static final Map<Integer, String> bookkeeping
            = new ConcurrentHashMap<>();

    private static class ReturnedBufferBookkeepingInfo
    {
        final String allocTrace;
        final String deallocTrace;
        ReturnedBufferBookkeepingInfo(String a, String d)
        {
            allocTrace = a;
            deallocTrace = d;
        }
    }

    private static final Map<Integer, ReturnedBufferBookkeepingInfo> returnedBookkeeping
        = new ConcurrentHashMap<>();

    /**
     * Whether to enable keeping track of statistics.
     */
    private static boolean enableStatistics = false;

    /**
     * Whether to enable or disable book keeping.
     */
    public static final Boolean ENABLE_BOOKKEEPING = false;

    /**
     * Total number of buffers requested.
     */
    private static final AtomicInteger numRequests = new AtomicInteger(0);

    /**
     * The number of requests which were larger than our threshold and were
     * allocated from java instead.
     */
    private static final AtomicInteger numLargeRequests = new AtomicInteger(0);

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
     * Gets a stack trace as a multi-line string.
     */
    private static String stackTraceAsString(StackTraceElement[] stack)
    {
        StringBuilder sb = new StringBuilder();
        for (StackTraceElement ste : stack)
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
        if (enableStatistics)
        {
            numRequests.incrementAndGet();
        }

        byte[] buf;
        if (size <= T1)
        {
            buf = pool1.getBuffer(size);
        }
        else if (size <= T2)
        {
            buf = pool2.getBuffer(size);
        }
        else if (size <= T3)
        {
            buf = pool3.getBuffer(size);
        }
        else
        {
            buf = new byte[size];
            numLargeRequests.incrementAndGet();
        }

        if (ENABLE_BOOKKEEPING)
        {
            Integer arrayId = System.identityHashCode(buf);

            bookkeeping.put(arrayId, UtilKt.getStackTrace());
            returnedBookkeeping.remove(arrayId);
            logger.info("Thread " + threadId() + " got " + buf.length + "-byte buffer "
                    + arrayId);
        }
        return buf;
    }

    /**
     * Returns a buffer to the pool.
     * @param buf
     */
    public static void returnBuffer(@NotNull byte[] buf)
    {
        if (enableStatistics)
        {
            numReturns.incrementAndGet();
        }

        int len = buf.length;

        if (ENABLE_BOOKKEEPING)
        {
            Integer arrayId = System.identityHashCode(buf);
            logger.info("Thread " + threadId() + " returned " + len + "-byte buffer "
                + arrayId);
            String s;
            ReturnedBufferBookkeepingInfo b;
            if ((s = bookkeeping.remove(arrayId)) != null)
            {
                returnedBookkeeping.put(arrayId, new ReturnedBufferBookkeepingInfo(s, UtilKt.getStackTrace()));
            }
            else if ((b = returnedBookkeeping.get(arrayId)) != null)
            {
                logger.info("Thread " + threadId()
                    + " returned a previously-returned " + len + "-byte buffer at\n"
                    + UtilKt.getStackTrace() +
                    "previously returned at\n" +
                    b.deallocTrace);
            }
            else
            {
                logger.info("Thread " + threadId()
                    + " returned a " + len + "-byte buffer we didn't give out from\n"
                    + UtilKt.getStackTrace());
            }
        }

        if (len <= T1)
        {
            pool1.returnBuffer(buf);
        }
        else if (len <= T2)
        {
            pool2.returnBuffer(buf);
        }
        else if (len < 2000)
        {
            pool3.returnBuffer(buf);
        }
        else
        {
            logger.warn(
                "Received a suspiciously large buffer (size = " + len + ")");
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
        stats.put("num_large_requests", numLargeRequests.get());
        stats.put("num_returns", numReturns.get());
        if (enableStatistics)
        {
            stats.put("pool1", pool1.getStats());
            stats.put("pool2", pool2.getStats());
            stats.put("pool3", pool3.getStats());
        }

        long allAllocations = numLargeRequests.get() + pool1.getNumAllocations()
                + pool2.getNumAllocations() + pool3.getNumAllocations();

        stats.put(
                "allocation_percent",
                (100.0 * allAllocations) / numRequests.get());

        return stats;
    }

    /**
     * Enables of disables tracking of statistics for the pool.
     * @param enable whether to enable it or disable it.
     */
    public static void enableStatistics(boolean enable)
    {
        enableStatistics = enable;
        pool1.enableStatistics(enable);
        pool2.enableStatistics(enable);
        pool3.enableStatistics(enable);
    }
}
