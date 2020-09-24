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

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.util.OrderedJsonObject
import org.jitsi.utils.maxAssign
import java.lang.IllegalArgumentException

open class DelayStats(thresholdsNoMax: LongArray = defaultThresholds) {
    init {
        if (!thresholdsNoMax.contentEquals(thresholdsNoMax.sortedArray())) {
            throw IllegalArgumentException("Thresholds must be sorted: ${thresholdsNoMax.joinToString()}")
        }
    }
    private val totalDelayMs = LongAdder()
    private val totalCount = LongAdder()
    private val averageDelayMs: Double
        get() = totalDelayMs.sum() / totalCount.sum().toDouble()
    private val maxDelayMs = AtomicLong(0)
    private val buckets = Buckets(thresholdsNoMax)

    fun addDelay(delayMs: Long) {
        if (delayMs >= 0) {
            totalDelayMs.add(delayMs)
            maxDelayMs.maxAssign(delayMs)
            totalCount.increment()
            buckets.addDelay(delayMs)
        }
    }

    fun toJson() = OrderedJsonObject().apply {
        val snapshot = snapshot
        put("average_delay_ms", snapshot.averageDelayMs)
        put("max_delay_ms", snapshot.maxDelayMs)
        put("total_count", snapshot.totalCount)

        put("buckets", snapshot.buckets.toJson())
    }

    val snapshot: Snapshot
        get() = Snapshot(
            averageDelayMs = averageDelayMs,
            maxDelayMs = maxDelayMs.get(),
            totalCount = totalCount.sum(),
            buckets = buckets.snapshot
        )

    data class Snapshot(
        val averageDelayMs: Double,
        val maxDelayMs: Long,
        val totalCount: Long,
        val buckets: Buckets.Snapshot
    )

    companion object {
        val defaultThresholds = longArrayOf(2, 5, 20, 50, 200, 500, 1000)
    }
}

class Buckets(thresholdsNoMax: LongArray) {
    private val thresholds = longArrayOf(*thresholdsNoMax, Long.MAX_VALUE)
    private val thresholdCounts = Array(thresholds.size + 1) { LongAdder() }
    val snapshot: Snapshot
        get() {
            val bucketCounts = Array(thresholds.size) { i -> Pair(thresholds[i], thresholdCounts[i].sum()) }

            var p99 = Long.MAX_VALUE
            var p999 = Long.MAX_VALUE
            var sum: Long = 0
            val totalCount = bucketCounts.map { it.second }.sum()
            bucketCounts.forEach {
                sum += it.second
                if (it.first < p99 && sum > 0.99 * totalCount) p99 = it.first
                if (it.first < p999 && sum > 0.999 * totalCount) p999 = it.first
            }

            // Not enough data
            if (totalCount < 100 || p99 == Long.MAX_VALUE) p99 = -1
            if (totalCount < 1000 || p999 == Long.MAX_VALUE) p999 = -1
            return Snapshot(bucketCounts, p99, p999)
        }

    private fun findBucket(delayMs: Long): LongAdder {
        // The vast majority of values are in the first bucket, so linear search is likely faster than binary.
        for (i in thresholds.indices) {
            if (delayMs <= thresholds[i]) return thresholdCounts[i]
        }
        return thresholdCounts.last()
    }

    fun addDelay(delayMs: Long) = findBucket(delayMs).increment()

    data class Snapshot(
        val buckets: Array<Pair<Long, Long>>,
        val p99bound: Long,
        val p999bound: Long
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Snapshot

            if (!buckets.contentEquals(other.buckets)) return false

            return true
        }

        override fun hashCode(): Int {
            return buckets.contentHashCode()
        }

        fun toJson() = OrderedJsonObject().apply {
            for (i in 0..buckets.size - 2) {
                put("<= ${buckets[i].first} ms", buckets[i].second)
            }

            val indexOfSecondToLast = buckets.size - 2
            put("> ${buckets[indexOfSecondToLast].first} ms", buckets.last().second)

            put("p99<=", p99bound)
            put("p999<=", p999bound)
        }
    }
}

class PacketDelayStats(thresholdsNoMax: LongArray = defaultThresholds) : DelayStats(thresholdsNoMax) {
    fun addPacket(packetInfo: PacketInfo) {
        val delayMs = if (packetInfo.receivedTime > 0) {
            System.currentTimeMillis() - packetInfo.receivedTime
        } else {
            -1
        }
        addDelay(delayMs)
    }
}
