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

package org.jitsi.nlj.util

import org.jitsi.nlj.rtp.VideoRtpPacket
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.NodeStatsProducer
import org.jitsi.rtp.rtp.RtpPacket
import java.util.concurrent.ConcurrentHashMap

/**
 * Creates a packet cache for packets by SSRC
 */
class PacketCache(
    /**
     * A function which dictates which packets to cache.
     */
    val packetPredicate: (RtpPacket) -> Boolean = { it is VideoRtpPacket }
) : NodeStatsProducer {
    /**
     * The max number of packets to cache per SSRC.
     */
    private val size: Int
    private val packetCaches: MutableMap<Long, RtpPacketCache> = ConcurrentHashMap()
    private var stopped = false

    companion object {
        val SIZE_PACKETS: String = "${PacketCache::class.java}.SIZE_PACKETS"
        private val defaultConfiguration = Configuration()

        init {
            defaultConfiguration[SIZE_PACKETS] = 2500
        }
    }

    init {
        size = defaultConfiguration.getInt(SIZE_PACKETS)
    }

    private fun getCache(ssrc: Long): RtpPacketCache {
        return packetCaches.computeIfAbsent(ssrc) { RtpPacketCache(size) }
    }

    /**
     * Stores a copy of the given packet in the cache.
     */
    fun insert(packet: RtpPacket) =
        !stopped && packetPredicate(packet) && getCache(packet.ssrc).insert(packet)

    /**
     * Gets a copy of the packet in the cache with the given SSRC and sequence number, if the cache contains it.
     * The instance is wrapped in a [ArrayCache.Container].
     */
    fun get(ssrc: Long, seqNum: Int): ArrayCache<RtpPacket>.Container? = getCache(ssrc).get(seqNum)

    /**
     * Gets copies of the latest packets in the cache. Tries to get at least [numBytes] bytes total.
     */
    fun getMany(ssrc: Long, numBytes: Int): Set<RtpPacket> = getCache(ssrc).getMany(numBytes)

    /**
     * Updates the timestamp of a packet in the cache (if it is in the cache). This is used when we re-transmit a
     * packet in order to update the timestamp without re-adding the packet to the cache (which is expensive).
     */
    fun updateTimestamp(ssrc: Long, seqNum: Int, timeAdded: Long) = getCache(ssrc).updateTimestamp(seqNum, timeAdded)

    fun stop() {
        stopped = true
        packetCaches.forEach { _, cache -> cache.flush() }
    }

    override fun getNodeStats(): NodeStatsBlock = NodeStatsBlock("PacketCache").apply {
        packetCaches.values.forEach {
            aggregate(it.getNodeStats())
        }
    }
}

/**
 * Implements a cache for RTP packets.
 */
class RtpPacketCache(size: Int) : ArrayCache<RtpPacket>(size, RtpPacket::clone) {

    private val rfc3711IndexTracker = Rfc3711IndexTracker()

    override fun discardItem(item: RtpPacket) {
        BufferPool.returnBuffer(item.buffer)
    }

    /**
     * Gets a packet with a given RTP sequence number from the cache.
     */
    fun get(sequenceNumber: Int): Container? {
        // Note that we use [interpret] because we don't want the ROC to get out of sync because of funny requests
        // (NACKs)
        val index = rfc3711IndexTracker.interpret(sequenceNumber)
        return super.getContainer(index)
    }

    fun insert(rtpPacket: RtpPacket): Boolean {
        val index = rfc3711IndexTracker.update(rtpPacket.sequenceNumber)
        return super.insertItem(rtpPacket, index)
    }

    fun updateTimestamp(seqNum: Int, timeAdded: Long) {
        val index = rfc3711IndexTracker.interpret(seqNum)
        super.updateTimeAdded(index, timeAdded)
    }

    fun getMany(numBytes: Int): Set<RtpPacket> {
        var bytesRemaining = numBytes
        val packets = mutableSetOf<RtpPacket>()

        forEachDescending {
            packets.add(it)
            bytesRemaining -= it.length
            bytesRemaining > 0
        }

        return packets
    }
}
