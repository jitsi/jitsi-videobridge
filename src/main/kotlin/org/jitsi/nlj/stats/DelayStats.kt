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

import java.util.Arrays
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder
import org.jitsi.nlj.PacketInfo

open class DelayStats {
    private val totalDelayMs = LongAdder()
    private val totalCount = LongAdder()
    val averageDelay: Double
        get() = totalDelayMs.sum() / totalCount.sum().toDouble()
    val maxDelayMs = AtomicLong(0)

    /* TODO: make thresholds configurable */
    val thresholds = longArrayOf(2, 5, 20, 50, 200, 500, 1000)
    val thresholdCounts = Array(thresholds.size + 1) { LongAdder() }

    fun addDelay(delayMs: Long) {
        if (delayMs >= 0) {
            totalDelayMs.add(delayMs)
            if (delayMs > maxDelayMs.get()) {
                maxDelayMs.set(delayMs)
            }
            totalCount.increment()

            val searchResult = Arrays.binarySearch(thresholds, delayMs)
            val bucket = if (searchResult < 0) { - searchResult - 1 } else { searchResult }
            thresholdCounts[bucket].increment()
        }
    }
}

class PacketDelayStats : DelayStats() {
    fun addPacket(packetInfo: PacketInfo) {
        val delayMs = if (packetInfo.receivedTime > 0) {
            System.currentTimeMillis() - packetInfo.receivedTime
        } else {
            -1
        }
        addDelay(delayMs)
    }
}
