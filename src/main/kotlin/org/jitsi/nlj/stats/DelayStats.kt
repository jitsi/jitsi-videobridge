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

import org.jitsi.nlj.PacketInfo
import org.jitsi.utils.stats.BucketStats

open class DelayStats(thresholdsNoMax: LongArray = defaultThresholds) :
    BucketStats(thresholdsNoMax, "_delay_ms", " ms") {

    fun addDelay(delayMs: Long) = addValue(delayMs)

    companion object {
        val defaultThresholds = longArrayOf(2, 5, 20, 50, 200, 500, 1000)
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
