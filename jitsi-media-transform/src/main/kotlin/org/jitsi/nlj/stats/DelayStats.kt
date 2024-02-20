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

import org.jitsi.utils.OrderedJsonObject
import org.jitsi.utils.stats.BucketStats
import java.util.concurrent.atomic.LongAdder

open class DelayStats(thresholds: List<Long> = defaultThresholds) :
    BucketStats(thresholds, "_delay_ms", "_ms") {

    fun addDelay(delayMs: Long) = addValue(delayMs)

    companion object {
        val defaultThresholds = listOf(0, 5, 50, 500, Long.MAX_VALUE)
    }
}

class PacketDelayStats(thresholds: List<Long> = defaultThresholds) : DelayStats(thresholds) {
    private val numPacketsWithoutTimestamps = LongAdder()

    fun addUnknown() = numPacketsWithoutTimestamps.increment()

    override fun toJson(format: Format): OrderedJsonObject {
        return super.toJson(format).apply {
            put("packets_without_timestamps", numPacketsWithoutTimestamps.sum())
        }
    }
}
