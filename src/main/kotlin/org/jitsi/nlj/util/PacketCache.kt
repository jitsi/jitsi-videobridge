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

import org.jitsi.rtp.rtp.RtpPacket
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Creates a packet cache for packets by SSRC
 */
class PacketCache {
    /**
     * Packets added to the cache more than [timeout] ago might be
     * cleared from the cache on insertion.
     *
     * FIXME(gp) the cache size should be adaptive based on the RTT.
     */
    private val timeout: Duration

    private val maxNumElements: Int

    private val packetCaches: MutableMap<Long, RtpPacketCache> = ConcurrentHashMap()

    companion object {
        val NACK_CACHE_SIZE_MILLIS: String = "${PacketCache::class.java}.CACHE_SIZE_MILLIS"
        val NACK_CACHE_SIZE_PACKETS: String = "${PacketCache::class.java}.CACHE_SIZE_PACKETS"
        private val defaultConfiguration = Configuration()
        init {
            defaultConfiguration[NACK_CACHE_SIZE_MILLIS] = 1000
            defaultConfiguration[NACK_CACHE_SIZE_PACKETS] = 500
        }
    }

    init {
        timeout = Duration.ofMillis(defaultConfiguration.getInt(NACK_CACHE_SIZE_MILLIS).toLong())
        maxNumElements = defaultConfiguration.getInt(NACK_CACHE_SIZE_PACKETS)
    }

    private fun getCache(ssrc: Long): RtpPacketCache {
        return packetCaches.computeIfAbsent(ssrc) { _ -> RtpPacketCache(timeout, maxNumElements) }
    }

    fun cachePacket(packet: RtpPacket) = getCache(packet.header.ssrc).insert(packet)

    fun getPacket(ssrc: Long, seqNum: Int): RtpPacket? = getCache(ssrc).get(seqNum)

    fun getMany(ssrc: Long, numBytes: Int): Set<RtpPacket> = getCache(ssrc).getMany(numBytes)

    /**
     * A cache of packets which maps their RFC3711 index to the packet itself.
     * Each insertion, all packets (in order of index) up to the first one
     * which is newer than [timeout] are pruned from the cache.
     */
    private class RtpPacketCache(
        timeout: Duration,
        maxNumElements: Int
    ) {
        private val cache = TimeExpiringCache<Int, RtpPacket>(timeout, maxNumElements)
        private val rfc3711IndexTracker = Rfc3711IndexTracker()

        fun insert(packet: RtpPacket) {
            val index = rfc3711IndexTracker.update(packet.header.sequenceNumber)
            cache.insert(index, packet)
        }

        fun get(seqNum: Int): RtpPacket? {
            // We don't know to which ROC value this sequence number belongs, so we'll try
            // to find it first using the current ROC value, but if that fails we'll try
            // again to use the previous one, just in case we've rolled over recently
            return cache.get(seqNum + rfc3711IndexTracker.roc * 0x1_0000) ?:
                cache.get(seqNum + rfc3711IndexTracker.roc - 1 * 0x1_0000)
        }

        fun getMany(numBytes: Int): Set<RtpPacket> {
            var bytesRemaining = numBytes
            val packets = mutableSetOf<RtpPacket>()
            cache.forEachDescending { pkt ->
                packets.add(pkt)
                bytesRemaining -= pkt.sizeBytes
                bytesRemaining > 0
            }
            return packets
        }
    }
}