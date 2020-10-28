/*
 * Copyright @ 2020 - Present, 8x8 Inc
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

import org.jitsi.utils.ms
import org.jitsi.utils.stats.RateTracker
import java.time.Clock
import java.time.Duration

open class BitrateTracker @JvmOverloads constructor(
    private val windowSize: Duration,
    private val bucketSize: Duration = 1.ms,
    private val clock: Clock = Clock.systemUTC()
) {
    // Use composition to expose functions with the data types we want ([DataSize], [Bandwidth]) and not the raw types
    // that RateTracker uses.
    private val tracker = RateTracker(windowSize, bucketSize, clock)
    open fun getRate(nowMs: Long = clock.millis()): Bandwidth = tracker.getRate(nowMs).bps
    val rate: Bandwidth
        get() = getRate()
    fun update(dataSize: DataSize, now: Long = clock.millis()) = tracker.update(dataSize.bits, now)
    fun getAccumulatedSize(now: Long = clock.millis()) = tracker.getAccumulatedCount(now).bps
}
