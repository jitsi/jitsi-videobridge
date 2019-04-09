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
package org.jitsi_modified.impl.neomedia.rtp;

import org.jetbrains.annotations.*;
import org.jitsi.impl.neomedia.transform.*;
import org.jitsi.nlj.util.*;
import org.jitsi.rtp.rtp.*;
import org.jitsi.util.concurrent.*;
import org.jitsi.utils.logging.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * An simple interface which allows a packet to be retrieved from a
 * cache/storage by an SSRC identifier and a sequence number.
 *
 * @author Boris Grozev
 * @author George Politis
 */
public class RtpPacketCache
    implements AutoCloseable
{
    /**
     * The <tt>Logger</tt> used by the <tt>RtpPacketCache</tt> class and
     * its instances to print debug information.
     */
    private static final Logger logger
        = Logger.getLogger(org.jitsi.impl.neomedia.rtp.RawPacketCache.class);

    /**
     * Configuration property for number of streams to cache
     */
    public final static String NACK_CACHE_SIZE_STREAMS
        = "org.jitsi_modified.impl.neomedia.rtp.RtpPacketCache.CACHE_SIZE_STREAMS";

    /**
     * Configuration property number of packets to cache.
     */
    public final static String NACK_CACHE_SIZE_PACKETS
        = "org.jitsi_modified.impl.neomedia.rtp.RtpPacketCache.CACHE_SIZE_PACKETS";

    /**
     * Configuration property for nack cache size in milliseconds.
     */
    public final static String NACK_CACHE_SIZE_MILLIS
        = "org.jitsi_modified.impl.neomedia.rtp.RtpPacketCache.CACHE_SIZE_MILLIS";

    private static final int BUFFER_SIZE = 1500 + RtpPacket.BYTES_TO_LEAVE_AT_END_OF_PACKET;

    private static Configuration defaultConfiguration = new Configuration();

    static {
        int DEFAULT_SIZE_MILLIS = 1000;
        int DEFAULT_MAX_SSRC_COUNT = 50;
        int DEFAULT_NACK_CACHE_SIZE_PACKETS = 500;
        defaultConfiguration.put(NACK_CACHE_SIZE_MILLIS, DEFAULT_SIZE_MILLIS);
        defaultConfiguration.put(NACK_CACHE_SIZE_STREAMS, DEFAULT_MAX_SSRC_COUNT);
        defaultConfiguration.put(NACK_CACHE_SIZE_PACKETS, DEFAULT_NACK_CACHE_SIZE_PACKETS);
    }

    private Configuration config = defaultConfiguration;

    /**
     * Packets added to the cache more than <tt>SIZE_MILLIS</tt> ago might be
     * cleared from the cache.
     *
     * FIXME(gp) the cache size should be adaptive based on the RTT.
     */
    private final int SIZE_MILLIS;

    /**
     * The maximum number of different SSRCs for which a cache will be created.
     */
    private final int MAX_SSRC_COUNT;

    /**
     * The maximum number of packets cached for each SSRC.
     */
    private final int MAX_SIZE_PACKETS;

    /**
     * The size of {@link #pool} and {@link #containersPool}.
     */
    private static int POOL_SIZE = 100;

    /**
     * The amount of time, after which the cache for an SSRC will be cleared,
     * unless new packets have been inserted.
     */
    private final int SSRC_TIMEOUT_MILLIS;

    /**
     * The pool of <tt>RtpPacket</tt>s which we use to avoid allocation and GC.
     */
    private final Queue<RtpPacket> pool
        = new LinkedBlockingQueue<>(POOL_SIZE);

    /**
     * A cache of unused {@link Container} instances.
     */
    private final Queue<Container> containersPool
        = new LinkedBlockingQueue<>(POOL_SIZE);

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
     * Contains a <tt>Cache</tt> instance for each SSRC.
     */
    private final Map<Long, Cache> caches = new HashMap<>();

    /**
     * The age in milliseconds of the oldest packet retrieved from any of the
     * {@link Cache}s of this instance.
     */
    private MonotonicAtomicLong oldestHit = new MonotonicAtomicLong();

    /**
     * The hash code or other identifier of the owning stream, if any. Only
     * used for logging.
     */
    private final int streamId;

    public RtpPacketCache(int streamId) {
        this(streamId, null);
    }

    /**
     * Initializes a new {@link CachingTransformer} instance.
     * @param streamId the identifier of the owning stream.
     * @param configOverrides any overridden config values
     */
    public RtpPacketCache(int streamId, @Nullable Configuration configOverrides)
    {
        this.streamId = streamId;
        config.merge(configOverrides);

        this.SIZE_MILLIS = config.getInt(NACK_CACHE_SIZE_MILLIS);
        this.MAX_SSRC_COUNT = config.getInt(NACK_CACHE_SIZE_STREAMS);
        this.MAX_SIZE_PACKETS = config.getInt(NACK_CACHE_SIZE_PACKETS);
        this.SSRC_TIMEOUT_MILLIS = this.SIZE_MILLIS + 50;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close()
        throws Exception
    {
        if (totalPacketsAdded.get() > 0)
        {
            logger.info(Logger.Category.STATISTICS,
                "closed,stream=" + streamId
                    + " max_size_bytes=" + maxSizeInBytes
                    + ",max_size_packets=" + maxSizeInPackets
                    + ",total_hits=" + totalHits.get()
                    + ",total_misses=" + totalMisses.get()
                    + ",total_packets=" + totalPacketsAdded.get()
                    + ",oldest_hit_ms=" + oldestHit);
        }

        synchronized (caches)
        {
            caches.clear();
        }
        pool.clear();
        containersPool.clear();
    }

    /**
     * Gets the packet, encapsulated in a {@link Container} with the given SSRC
     * and RTP sequence number from the cache. If no such packet is found,
     * returns <tt>null</tt>.
     * @param ssrc The SSRC of the packet.
     * @param seq the RTP sequence number of the packet.
     * @return the packet, encapsulated in a {@link Container} with the given
     * SSRC and RTP sequence number from the cache. If no such packet is found,
     * returns <tt>null</tt>.
     */
    public Container getContainer(long ssrc, int seq)
    {
        Cache cache = getCache(ssrc & 0xffff_ffffL, false);

        Container container = cache != null ? cache.get(seq) : null;

        if (container != null)
        {
            if (container.timeAdded > 0)
            {
                oldestHit
                    .increase(System.currentTimeMillis() - container.timeAdded);
            }
            totalHits.incrementAndGet();
        }
        else
        {
            totalMisses.incrementAndGet();
        }

        return container;
    }

    /**
     * Gets the packet with the given SSRC and RTP sequence number from the
     * cache. If no such packet is found, returns <tt>null</tt>.
     * @param ssrc The SSRC of the packet.
     * @param seq the RTP sequence number of the packet.
     * @return the packet with the given SSRC and RTP sequence number from the
     * cache. If no such packet is found, returns <tt>null</tt>.
     */
    public RtpPacket get(long ssrc, int seq)
    {
        Container container = getContainer(ssrc, seq);
        return container == null ? null : container.pkt;
    }

    /**
     * Gets the {@link Cache} instance which caches packets with SSRC
     * <tt>ssrc</tt>, creating if <tt>create</tt> is set and creation is
     * possible (the maximum number of caches hasn't been reached).
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
    public void cachePacket(RtpPacket pkt)
    {
        Cache cache = getCache(pkt.getSsrc(), true);

        if (cache != null)
        {
            if (logger.isTraceEnabled())
            {
                logger.trace("Caching a packet. SSRC=" + pkt.getSsrc()
                    + " seq=" + pkt.getSequenceNumber());
            }
            cache.insert(pkt);
            totalPacketsAdded.incrementAndGet();
        }
    }



    /**
     * Gets an unused <tt>RtpPacket</tt> with at least <tt>len</tt> bytes of
     * buffer space.
     * @param len the minimum available length
     * @return An unused <tt>RtpPacket</tt> with at least <tt>len</tt> bytes of
     * buffer space.
     */
    private RtpPacket getFreePacket(int len)
    {
        RtpPacket pkt = pool.poll();
        if (pkt == null)
            pkt = new RtpPacket(new byte[BUFFER_SIZE], 0, 0);

        if (pkt.getBuffer() == null || pkt.getBuffer().length < len)
            pkt.setBuffer(new byte[BUFFER_SIZE]);
        pkt.setOffset(0);
        pkt.setLength(0);

        return pkt;
    }

    /**
     * @return  an unused {@link Container} instance.
     */
    private Container getFreeContainer()
    {
        Container container = containersPool.poll();
        if (container == null)
        {
            container = new Container();
        }
        return container;
    }

    /**
     * Checks for {@link Cache} instances which have not received new packets
     * for a period longer than {@link #SSRC_TIMEOUT_MILLIS} and removes them.
     */
    public void clean(long now)
    {
        synchronized (caches)
        {
            if (logger.isDebugEnabled())
            {
                logger.debug("Cleaning RtpPacketCache " + hashCode());
            }

            Iterator<Map.Entry<Long,Cache>> iter
                = caches.entrySet().iterator();
            while (iter.hasNext())
            {
                Map.Entry<Long,Cache> entry = iter.next();
                Cache cache = entry.getValue();
                if (cache.lastInsertTime + SSRC_TIMEOUT_MILLIS < now)
                {
                    if (logger.isDebugEnabled())
                    {
                        logger.debug("Removing cache for SSRC " + entry
                            .getKey());
                    }
                    cache.empty();
                    iter.remove();
                }
            }
        }
    }

    /**
     * Returns a {@link Container} and its {@link RtpPacket} to the list of
     * free containers (and packets).
     * @param container the container to return.
     */
    private void returnContainer(Container container)
    {
        if (container != null)
        {
            if (container.pkt != null)
            {
                pool.offer(container.pkt);
            }

            container.pkt = null;
            containersPool.offer(container);
        }
    }

    /**
     * Gets the most recent packets from the cache that pertains to the SSRC
     * that is specified as an argument, not exceeding the number of bytes
     * specified as an argument.
     *
     * @param ssrc the SSRC whose most recent packets to retrieve.
     * @param bytes the maximum total size of the packets to retrieve.
     * @return the set of the most recent packets to retrieve, not exceeding the
     * number of bytes specified as an argument, or null if there are no packets
     * in the cache
     */
    public Set<Container> getMany(long ssrc, int bytes)
    {
        Cache cache = getCache(ssrc & 0xffffffffL, false);
        return cache == null ? null : cache.getMany(bytes);
    }

    /**
     * Updates the timestamp of the packet in the cache with SSRC {@code ssrc}
     * and sequence number {@code seq}, if such a packet exists in the cache,
     * setting it to {@code ts}.
     * @param ssrc the SSRC of the packet.
     * @param seq the sequence number of the packet.
     * @param ts the timestamp to set.
     */
    public void updateTimestamp(long ssrc, int seq, long ts)
    {
        Cache cache = getCache(ssrc, false);
        if (cache != null)
        {
            synchronized (cache)
            {
                Container container = cache.doGet(seq);
                if (container != null)
                {
                    container.timeAdded = ts;
                }
            }
        }
    }

    /**
     * Implements a cache for the packets of a specific SSRC.
     */
    private class Cache
    {
        /**
         * The underlying container. It maps a packet index (based on its RTP
         * sequence number, in the same way as used in SRTP (RFC3711)) to the
         * <tt>RtpPacket</tt> with the packet contents.
         */
        private TreeMap<Integer, Container> cache = new TreeMap<>();

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
        private synchronized void insert(RtpPacket pkt)
        {
            int len = pkt.getLength();
            RtpPacket cachePacket = getFreePacket(len);
            System.arraycopy(pkt.getBuffer(), pkt.getOffset(),
                cachePacket.getBuffer(), 0,
                len);
            cachePacket.setLength(len);

            int index = calculateIndex(pkt.getSequenceNumber());
            Container container = getFreeContainer();
            container.pkt = cachePacket;
            container.timeAdded = System.currentTimeMillis();

            // If the packet is already in the cache, we want to update the
            // timeAdded field for retransmission purposes. This is implemented
            // by simply replacing the old packet.
            Container oldContainer = cache.put(index, container);

            synchronized (sizesSyncRoot)
            {
                sizeInPackets++;
                sizeInBytes += len;
                if (oldContainer != null)
                {
                    sizeInPackets--;
                    sizeInBytes -= oldContainer.pkt.getLength();
                }

                if (sizeInPackets > maxSizeInPackets)
                    maxSizeInPackets = sizeInPackets;
                if (sizeInBytes > maxSizeInBytes)
                    maxSizeInBytes = sizeInBytes;
            }

            returnContainer(oldContainer);
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
            if (s_l < 0x8000)
                if (seq - s_l > 0x8000)
                    v = (int) ((ROC - 1) & 0xffff_ffffL);
                else if (s_l - 0x1_0000 > seq)
                    v = (int) ((ROC + 1) & 0xffff_ffffL );

            if (v == ROC && seq > s_l)
                s_l = seq;
            else if (v == ((ROC + 1) & 0xffff_ffffL))
            {
                s_l = seq;
                ROC = v;
            }


            return seq + v * 0x1_0000;
        }

        /**
         * Returns a copy of the RTP packet with sequence number {@code seq}
         * from the cache, or {@code null} if the cache does not contain a
         * packet with this sequence number.
         * @param seq the RTP sequence number of the packet to get.
         * @return a copy of the RTP packet with sequence number {@code seq}
         * from the cache, or {@code null} if the cache does not contain a
         * packet with this sequence number.
         */
        private synchronized Container get(int seq)
        {
            Container container = doGet(seq);

            return
                container == null
                    ? null
                    : new Container(
                    new RtpPacket(container.pkt.getBuffer().clone(),
                        container.pkt.getOffset(),
                        container.pkt.getLength()),
                    container.timeAdded);
        }

        /**
         * Returns the RTP packet with sequence number {@code seq}
         * from the cache, or {@code null} if the cache does not contain a
         * packet with this sequence number.
         * @param seq the RTP sequence number of the packet to get.
         * @return the RTP packet with sequence number {@code seq}
         * from the cache, or {@code null} if the cache does not contain a
         * packet with this sequence number.
         */
        private synchronized Container doGet(int seq)
        {
            // Since sequence numbers wrap at 2^16, we can't know with absolute
            // certainty which packet the request refers to. We assume that it
            // is for the latest packet (i.e. the one with the highest index).
            Container container = cache.get(seq + ROC * 0x1_0000);

            // Maybe the ROC was just bumped recently.
            if (container == null && ROC > 0)
                container = cache.get(seq + (ROC-1) * 0x1_0000);

            // Since the cache only stores <tt>SIZE_MILLIS</tt> milliseconds of
            // packets, we assume that it doesn't contain packets spanning
            // more than one ROC.
            return container;
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

            long cleanBefore = System.currentTimeMillis() - SIZE_MILLIS;

            Iterator<Map.Entry<Integer,Container>> iter
                = cache.entrySet().iterator();
            int removedPackets = 0;
            int removedBytes = 0;
            while (iter.hasNext())
            {
                Container container = iter.next().getValue();
                RtpPacket pkt = container.pkt;

                if (size > MAX_SIZE_PACKETS)
                {
                    // Remove until we go under the max size, regardless of the
                    // timestamps.
                    size--;
                }
                else if (container.timeAdded >= 0 &&
                         container.timeAdded > cleanBefore)
                {
                    // We reached a packet with a timestamp after 'cleanBefore'.
                    // The rest of the packets are even more recent.
                    break;
                }

                iter.remove();
                removedBytes += pkt.getLength();
                removedPackets++;
                returnContainer(container);
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
            for (Container container : cache.values())
            {
                removedBytes += container.pkt.getBuffer().length;
                returnContainer(container);
            }

            synchronized (sizesSyncRoot)
            {
                sizeInPackets -= cache.size();
                sizeInBytes -= removedBytes;
            }

            cache.clear();
        }

        /**
         * Gets the most recent packets from this cache, not exceeding the
         * number of bytes specified as an argument.
         *
         * @param bytes the maximum number of bytes to retrieve.
         * @return the set of the most recent packets to retrieve, not exceeding
         * the number of bytes specified as an argument, or null if there are
         * no packets in the cache.
         */
        public synchronized Set<Container> getMany(int bytes)
        {
            if (cache.isEmpty() || bytes < 1)
            {
                return null;
            }

            // XXX(gp) This is effectively Copy-on-Read and is inefficient. We
            // should implement a Copy-on-Write method or something else that is
            // more efficient than this..
            Set<Container> set = new HashSet<>();

            Iterator<Map.Entry<Integer, Container>> it
                = cache.descendingMap().entrySet().iterator();
            while (it.hasNext() && bytes > 0)
            {
                Container container = it.next().getValue();
                if (container != null && container.pkt != null)
                {
                    set.add(container);
                    bytes -= container.pkt.getLength();
                }
            }

            return set;
        }
    }

    /**
     * A container for packets in the cache.
     */
    public class Container
    {
        /**
         * The {@link RtpPacket} which this container holds.
         */
        public RtpPacket pkt;

        /**
         * The time (in milliseconds since the epoch) that the packet was
         * added to the cache.
         */
        public long timeAdded;

        /**
         * Initializes a new empty {@link Container} instance.
         */
        public Container()
        {
            this(null, -1);
        }

        /**
         * Initializes a new {@link Container} instance.
         * @param pkt the packet to hold.
         * @param timeAdded the time the packet was added.
         */
        public Container(RtpPacket pkt, long timeAdded)
        {
            this.pkt = pkt;
            this.timeAdded = timeAdded;
        }
    }

}
