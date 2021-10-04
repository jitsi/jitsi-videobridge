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
package org.jitsi.videobridge.transport.ice

import org.jitsi.utils.OrderedJsonObject
import org.jitsi.utils.stats.BucketStats
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.DoubleAdder

/**
 * Keep ICE-related statistics (currently only RTT).
 */
class IceStatistics {
    /** Stats for all harvesters */
    private val combinedStats = Stats()

    /** Stats by harvester name. */
    private val statsByHarvesterName = ConcurrentHashMap<String, Stats>()

    /** Add a round-trip-time measurement for a specific harvester */
    fun add(harvesterName: String, rttMs: Double) {
        combinedStats.add(rttMs = rttMs)
        statsByHarvesterName.computeIfAbsent(harvesterName) { Stats() }.add(rttMs = rttMs)
    }

    fun toJson() = OrderedJsonObject().apply {
        put("all", combinedStats.toJson())
        statsByHarvesterName.forEach { (harvesterName, stats) ->
            put(harvesterName, stats.toJson())
        }
    }

    companion object {
        val stats = IceStatistics()
    }

    private class Stats {
        /** Histogram of RTTs */
        val buckets = BucketStats(listOf(10L, 20, 40, 60, 80, 100, 150, 200, 250, 300, 500, 1000).toLongArray())
        var sum = DoubleAdder()
        var count = AtomicInteger()

        fun add(rttMs: Double) {
            sum.add(rttMs)
            count.incrementAndGet()
            buckets.addValue((rttMs + 0.5).toLong())
        }

        fun toJson() = OrderedJsonObject().apply {
            put("average", sum.sum() / count.toDouble())
            put("buckets", buckets.toJson())
        }
    }
}
