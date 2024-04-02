/*
 * Copyright @ 2023 - present 8x8, Inc.
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
package org.jitsi.videobridge.stats

import org.jitsi.videobridge.metrics.Metrics
import org.json.simple.JSONArray
import org.json.simple.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * Saves statistics for packets and bytes based on conference size.
 *
 * We assume the stats are updated periodically at an interval of [periodSeconds] seconds. This way we can convert
 * the rates passed to [addValue] to number of packets and bytes.
 */
class ConferencePacketStats private constructor() {
    private val periodSeconds = Metrics.interval.toMillis().toDouble() / 1000

    /** Maps a conference size to a [Stats] instance that sums the added packet and bit rates. */
    private val stats: Map<Int, Stats> = mutableMapOf<Int, Stats>().apply {
        (0..MAX_SIZE).forEach {
            this[it] = Stats()
        }
    }

    val totalPackets = AtomicLong()
    val totalBytes = AtomicLong()

    fun addValue(conferenceSize: Int, packetRatePps: Long, bitrateBps: Long) {
        stats[conferenceSize.coerceAtMost(MAX_SIZE)]?.let {
            val packets = (packetRatePps * periodSeconds).toLong()
            val bytes = (bitrateBps * periodSeconds / 8).toLong()

            it.packets.addAndGet(packets)
            it.bytes.addAndGet(bytes)
            totalPackets.addAndGet(packets)
            totalBytes.addAndGet(bytes)
        }
    }

    fun toJson() = JSONObject().apply {
        val packetRates = JSONArray()
        val bitrates = JSONArray()

        (0..MAX_SIZE).forEach { conferenceSize ->
            stats[conferenceSize]?.let {
                packetRates.add(it.packets.get())
                bitrates.add(it.bytes.get())
            }
        }

        put("packets", packetRates)
        put("bytes", bitrates)
        put("total_packets", totalPackets.get())
        put("total_bytes", totalBytes.get())
    }

    companion object {
        const val MAX_SIZE = 500

        @JvmField
        val stats = ConferencePacketStats()
    }

    private class Stats {
        val packets = AtomicLong()
        val bytes = AtomicLong()
    }
}
