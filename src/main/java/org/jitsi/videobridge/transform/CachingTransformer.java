/*
 * Copyright @ 2015 Atlassian Pty Ltd
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
package org.jitsi.videobridge.transform;

import org.jitsi.impl.neomedia.*;
import org.jitsi.impl.neomedia.transform.*;
import org.jitsi.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Implements a cache of outgoing RTP packets.
 * @author Boris Grozev
 */
public class CachingTransformer
    extends SinglePacketTransformer
    implements RawPacketCache, TransformEngine
{
    /**
     * The <tt>Logger</tt> used by the <tt>CachingTransformer</tt> class and
     * its instances to print debug information.
     */
    private static final Logger logger
            = Logger.getLogger(CachingTransformer.class);

    /**
     * Packets added to the cache more than <tt>SIZE_MILLIS</tt> ago might be
     * cleared from the cache.
     */
    private static int SIZE_MILLIS = 10000;

    /**
     * Assumed rate of the RTP clock.
     */
    private static int RTP_CLOCK_RATE = 90000;

    /**
     * <tt>SIZE_MILLIS</tt> expressed as a number of ticks on the RTP clock.
     */
    private static int SIZE_RTP_CLOCK_TICKS
            = (RTP_CLOCK_RATE / 1000) * SIZE_MILLIS;

    /**
     * The maximum number of different SSRCs for which a cache will be created.
     */
    private static int MAX_SSRC_COUNT = 10;

    /**
     * The maximum number of packets cached for each SSRC.
     */
    private static int MAX_SIZE_PACKETS = 3000;

    /**
     * The size of {@link #pool}.
     */
    private static int POOL_SIZE = 100;

    /**
     * The amount of time, after which the cache for an SSRC will be cleared,
     * unless new packets have been inserted.
     */
    private static int SSRC_TIMEOUT_MILLIS = SIZE_MILLIS + 50;

    /**
     * Returns <tt>true</tt> iff <tt>a</tt> is less than <tt>b</tt> modulo 2^32.
     */
    private static boolean lessThanTS(long a, long b)
    {
        if (a == b)
            return false;
        else if (a > b)
            return a - b >= (1L << 31);
        else //a < b
            return b - a < (1L << 31);
    }

    /**
     * The pool of <tt>RawPacket</tt>s which we use to avoid allocation and GC.
     */
    private Queue<RawPacket> pool
        = new LinkedBlockingQueue<RawPacket>(POOL_SIZE);

    /**
     * An object used to synchronize access to {@link #sizeInBytes},
     * {@link #maxSizeInBytes}, {@link #sizeInPackets} and
     * {@link #maxSizeInPackets}.
     */
    private final Object sizesSyncRoot = new Object();

    /**
     * The current size in bytes of the cache (for all SSRCs combined).
     */
    private int sizeInBytes = 0;

    /**
     * The maximum reached size in bytes of the cache (for all SSRCs combined).
     */
    private int maxSizeInBytes = 0;

    /**
     * The current number of packets in the cache (for all SSRCs combined).
     */
    private int sizeInPackets = 0;

    /**
     * The maximum reached number of packets in the cache (for all SSRCs
     * combined).
     */
    private int maxSizeInPackets = 0;

    /**
     * Counts the number of requests (calls to {@link #get(long, int)}) which
     * the cache was able to answer.
     */
    private AtomicInteger totalHits = new AtomicInteger(0);

    /**
     * Counts the number of requests (calls to {@link #get(long, int)}) which
     * the cache was not able to answer.
     */
    private AtomicInteger totalMisses = new AtomicInteger(0);

    /**
     * Counts the total number of packets added to this cache.
     */
    private AtomicInteger totalPacketsAdded = new AtomicInteger(0);

    /**
     * Whether or not this <tt>TransformEngine</tt> has been closed.
     */
    private boolean closed = false;

    /**
     * Contains a <tt>Cache</tt> instance for each SSRC.
     */
    private final Map<Long, Cache> caches = new HashMap<Long, Cache>();

    /**
     * The thread which monitors
     */
    private final CleanerThread cleanerThread = new CleanerThread();

    /**
     * Initializes a new <tt>CachingTransformer</tt> instance.
     */
    public CachingTransformer()
    {
        cleanerThread.start();
    }

    /**
     * {@inheritDoc}
     *
     * Transforms an outgoing packet.
     */
    @Override
    public RawPacket transform(RawPacket pkt)
    {
        if (!closed && pkt != null && pkt.getVersion() == 2)
            cachePacket(pkt);
        return pkt;
    }

    /**
     * {@inheritDoc}
     * Transforms an incoming packet.
     */
    @Override
    public RawPacket reverseTransform(RawPacket pkt)
    {
        return pkt;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close()
    {
        if (closed)
            return;
        closed = true;
        logger.info("Closing. Maximum size reached: "
                            + maxSizeInBytes + " bytes, "
                            + maxSizeInPackets + " packets; "
                            + totalHits + " hits, "
                            + totalMisses + " misses ("
                            + (totalHits.get() + totalMisses.get())
                            + " total requests); "
                            + totalPacketsAdded.get() + " total packets added.");

        synchronized (caches)
        {
            caches.clear();
        }

        synchronized (cleanerThread)
        {
            cleanerThread.notifyAll();
        }
    }

    /**
     * {@inheritDoc}
     *
     * Implements
     * {@link org.jitsi.videobridge.transform.RawPacketCache#get(long, int)}.
     */
    public RawPacket get(long ssrc, int seq)
    {
        Cache cache = getCache(ssrc & 0xffffffffL, false);

        RawPacket pkt =  cache != null ? cache.get(seq) : null;

        if (pkt != null)
            totalHits.incrementAndGet();
        else
            totalMisses.incrementAndGet();

        return pkt;
    }


    /**
     * Gets the {@link org.jitsi.videobridge.transform.CachingTransformer.Cache}
     * instance which caches packets with SSRC <tt>ssrc</tt>, creating if
     * <tt>create</tt> is set and creation is possible (the maximum number of
     * caches hasn't been reached).
     * @param ssrc the SSRC.
     * @param create whether to create an instance if one doesn't already exist.
     * @return the cache for <tt>ssrc</tt> or <tt>null</tt>.
     */
    private Cache getCache(long ssrc, boolean create)
    {
        synchronized (caches)
        {
            Cache cache = caches.get(ssrc);
            if (cache == null && create)
            {
                if (caches.size() < MAX_SSRC_COUNT)
                {
                    cache = new Cache();
                    caches.put(ssrc, cache);
                }
                else
                {
                    logger.warn("Not creating a new cache for SSRC " + ssrc
                                        + ": too many SSRCs already cached.");
                }
            }

            return cache;
        }
    }

    /**
     * Saves a packet in the cache.
     * @param pkt the packet to save.
     */
    private void cachePacket(RawPacket pkt)
    {
        Cache cache = getCache(pkt.getSSRC() & 0xffffffffL, true);

        if (cache != null)
        {
            cache.insert(pkt);
            totalPacketsAdded.incrementAndGet();
        }
    }

    /**
     * Gets an unused <tt>RawPacket</tt> with at least <tt>len</tt> bytes of
     * buffer space.
     * @param len the minimum available length
     * @return An unused <tt>RawPacket</tt> with at least <tt>len</tt> bytes of
     * buffer space.
     */
    private RawPacket getFreePacket(int len)
    {
        RawPacket pkt = pool.poll();
        if (pkt == null)
            pkt = new RawPacket(new byte[len], 0, 0);

        if (pkt.getBuffer() == null || pkt.getBuffer().length < len)
            pkt.setBuffer(new byte[len]);
        pkt.setOffset(0);
        pkt.setLength(0);

        return pkt;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PacketTransformer getRTPTransformer()
    {
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PacketTransformer getRTCPTransformer()
    {
        return null;
    }

    /**
     * Implements a cache for the packets of a specific SSRC.
     */
    private class Cache
    {
        /**
         * The underlying container. It maps a packet index (based on its RTP
         * sequence number, in the same way as used in SRTP (RFC3711)) to the
         * <tt>RawPacket</tt> with the packet contents.
         */
        private TreeMap<Integer, RawPacket> cache
                = new TreeMap<Integer, RawPacket>();

        /**
         * Last system time of insertion of a packet in this cache.
         */
        private long lastInsertTime = -1;

        /**
         * A Roll Over Counter (as in by RFC3711).
         */
        private int ROC = 0;

        /**
         * The highest received sequence number (as in RFC3711).
         */
        private int s_l = -1;

        /**
         * Inserts a packet into this <tt>Cache</tt>.
         * @param pkt the packet to insert.
         */
        private synchronized void insert(RawPacket pkt)
        {
            int len = pkt.getLength();
            RawPacket cachePacket = getFreePacket(len);
            System.arraycopy(pkt.getBuffer(), pkt.getOffset(),
                             cachePacket.getBuffer(), 0,
                             len);
            cachePacket.setLength(len);

            int index = calculateIndex(pkt.getSequenceNumber());
            cache.put(index, cachePacket);

            synchronized (sizesSyncRoot)
            {
                sizeInPackets++;
                sizeInBytes += len;

                if (sizeInPackets > maxSizeInPackets)
                    maxSizeInPackets = sizeInPackets;
                if (sizeInBytes > maxSizeInBytes)
                    maxSizeInBytes = sizeInBytes;
            }
            lastInsertTime = System.currentTimeMillis();
            clean();
        }

        /**
         * Calculates the index of an RTP packet based on its RTP sequence
         * number and updates the <tt>s_l</tt> and <tt>ROC</tt> fields. Based
         * on the procedure outlined in RFC3711
         * @param seq the RTP sequence number of the RTP packet.
         * @return the index of the RTP sequence number with sequence number
         * <tt>seq</tt>.
         */
        private int calculateIndex(int seq)
        {
            if (s_l == -1)
            {
                s_l = seq;
                return seq;
            }

            int v = ROC;
            if (s_l < (1<<15))
                if (seq - s_l > (1<<15))
                    v = (int) ((ROC-1) % (1L<<32));
            else if (s_l - (1<<16) > seq)
                v = (int) ((ROC+1) % (1L<<32));

            if (v == ROC && seq > s_l)
                s_l = seq;
            else if (v == ((ROC + 1) % (1L<<32)))
            {
                s_l = seq;
                ROC = v;
            }


            return seq + v * (1<<16);
        }

        /**
         * Returns the RTP packet with sequence number <tt>seq</tt> from the
         * cache, or <tt>null</tt> if the cache does not contain a packet with
         * this sequence number.
         * @param seq the RTP sequence number of the packet to get.
         * @return the RTP packet with sequence number <tt>seq</tt> from the
         * cache, or <tt>null</tt> if the cache does not contain a packet with
         * this sequence number.
         */
        private synchronized RawPacket get(int seq)
        {
            // Since sequence numbers wrap at 2^16, we can't know with absolute
            // certainty which packet the request refers to. We assume that it
            // is for the latest packet (i.e. the one with the highest index).
            RawPacket pkt = cache.get(seq + ROC * (1 << 16));

            // Maybe the ROC was just bumped recently.
            if (pkt == null && ROC > 0)
                pkt = cache.get(seq + (ROC-1)*(1<<16));

            // Since the cache only stores <tt>SIZE_MILLIS</tt> milliseconds of
            // packets, we assume that it doesn't contain packets spanning
            // more than one ROC.

            return
                    pkt == null
                    ? null
                    : new RawPacket(pkt.getBuffer().clone(),
                                    pkt.getOffset(),
                                    pkt.getLength());
        }

        /**
         * Drops the oldest packets from the cache until:
         * 1. The cache contains at most {@link #MAX_SIZE_PACKETS} packets, and
         * 2. The cache only contains packets at most {@link #SIZE_MILLIS}
         * milliseconds older than the newest packet in the cache.
         */
        private synchronized void clean()
        {
            int size = cache.size();
            if (size <= 0)
                return;

            long lastTimestamp = 0xffffffffL & cache.lastEntry().getValue().getTimestamp();
            long cleanBefore = getCleanBefore(lastTimestamp);

            Iterator<Map.Entry<Integer,RawPacket>> iter
                    = cache.entrySet().iterator();
            int removedPackets = 0;
            int removedBytes = 0;
            while (iter.hasNext())
            {
                RawPacket pkt = iter.next().getValue();
                if (size > MAX_SIZE_PACKETS)
                {
                    // Remove until we go under the max size, regardless of the
                    // timestamps.
                    size--;
                }
                else if (lessThanTS(cleanBefore,
                                    0xffffffffL & pkt.getTimestamp()))
                {
                    // We reached a packet with a timestamp after 'cleanBefore'.
                    // The rest of the packets are even more recent.
                    break;
                }

                iter.remove();
                removedBytes += pkt.getLength();
                removedPackets++;
                pool.offer(pkt);
            }

            synchronized (sizesSyncRoot)
            {
                sizeInBytes -= removedBytes;
                sizeInPackets -= removedPackets;
            }

        }

        synchronized private void empty()
        {
            int removedBytes = 0;
            for (RawPacket pkt : cache.values())
            {
                removedBytes += pkt.getBuffer().length;
                pool.offer(pkt);
            }

            synchronized (sizesSyncRoot)
            {
                sizeInPackets -= cache.size();
                sizeInBytes -= removedBytes;
            }

            cache.clear();
        }

        /**
         * Returns the RTP timestamp which is {@link #SIZE_MILLIS} milliseconds
         * older than <tt>ts</tt>.
         * @param ts
         * @return
         */
        private long getCleanBefore(long ts)
        {
            return (ts + (1L<<32) - SIZE_RTP_CLOCK_TICKS) % (1L<<32);
        }
    }

    /**
     * A <tt>Thread</tt> which periodically checks for <tt>Cache</tt> instances
     * which have not received new packets for a period longer than the timeout,
     * and removes them.
     */
    private class CleanerThread
        extends Thread
    {
        private CleanerThread()
        {
            setDaemon(true);
            setName(CleanerThread.class.getCanonicalName());
        }

        @Override
        public void run()
        {
            while (!closed)
            {
                synchronized (caches)
                {
                    long now = System.currentTimeMillis();
                    Iterator<Map.Entry<Long,Cache>> iter
                            = caches.entrySet().iterator();
                    while (iter.hasNext())
                    {
                        Map.Entry<Long,Cache> entry = iter.next();
                        Cache cache = entry.getValue();
                        if (cache.lastInsertTime + SSRC_TIMEOUT_MILLIS < now)
                        {
                            logger.debug("Removing cache for SSRC "
                                                 + entry.getKey());
                            cache.empty();
                            iter.remove();
                        }
                    }
                }

                try
                {
                    synchronized (this)
                    {
                        wait(10000);
                    }
                }
                catch (InterruptedException ie)
                {
                    Thread.currentThread().interrupt();
                }
            }

            logger.debug("CleanerThread done.");
        }
    }
}
