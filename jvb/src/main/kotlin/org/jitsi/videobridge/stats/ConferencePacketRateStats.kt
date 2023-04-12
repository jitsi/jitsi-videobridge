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

import org.json.simple.JSONArray
import java.util.concurrent.atomic.AtomicLong

class ConferencePacketRateStats private constructor() {

    private val stats: Map<Int, AtomicLong> = mutableMapOf<Int, AtomicLong>().apply {
        (0..MAX_SIZE).forEach {
            this[it] = AtomicLong()
        }
    }

    fun addValue(conferenceSize: Int, packetRate: Long) {
        stats[conferenceSize.coerceAtMost(MAX_SIZE)]?.addAndGet(packetRate)
    }

    fun toJson() = JSONArray().apply {
        (0..MAX_SIZE).forEach { conferenceSize ->
            stats[conferenceSize]?.let {
                add(it.get())
            }
        }
    }

    companion object {
        const val MAX_SIZE = 500

        @JvmField
        val stats = ConferencePacketRateStats()
    }
}
