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
package org.jitsi.nlj.stats

import org.jitsi.nlj.transform.node.incoming.BitrateCalculator
import org.jitsi.nlj.util.Bandwidth
import org.jitsi.utils.OrderedJsonObject
import org.jitsi.nlj.util.bytes
import java.util.concurrent.atomic.AtomicLong

/**
 * Keeps tracks of the basic statistics for a stream of packets.
 *
 * @author Boris Grozev
 */
class PacketStreamStats {
    /**
     * The bitrate in bits per second.
     */
    private val bitrate = BitrateCalculator.createBitrateTracker()

    /**
     * The packet rate in packets per second.
     */
    private val packetRate = BitrateCalculator.createRateTracker()

    /**
     * Total total number of bytes.
     */
    private val bytes = AtomicLong()

    /**
     * Total total number of packets.
     */
    private val packets = AtomicLong()

    fun update(lengthInBytes: Int) {
        val now = System.currentTimeMillis()

        bitrate.update(lengthInBytes.bytes, now)
        bytes.addAndGet(lengthInBytes.toLong())

        packetRate.update(1, now)
        packets.incrementAndGet()
    }

    fun snapshot(): Snapshot {
        val now = System.currentTimeMillis()
        return Snapshot(bitrate.getRate(now), packetRate.getRate(now), bytes.get(), packets.get())
    }

    class Snapshot(
        /**
         * The current bitrate in bits per second.
         */
        val bitrate: Bandwidth,
        /**
         * The current packet rate in packets per second.
         */
        val packetRate: Long,
        /**
         * Total number of bytes.
         */
        val bytes: Long,
        /**
         * Total number of packets.
         */
        val packets: Long
    ) {
        fun toJson() = OrderedJsonObject().apply {
            put("bitrate_bps", bitrate.bps)
            put("packetrate", packetRate)
            put("total_bytes", bytes)
            put("total_packets", packets)
        }

        /**
         * Expose [bitrate] as a [Double] in order to make it accessible from java (since [Bandwidth] is an inline
         * class).
         */
        fun getBitrateBps(): Double = bitrate.bps
    }
}
