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

    @JvmOverloads
    open fun getRateBps(nowMs: Long = clock.millis()): Long = tracker.getRate(nowMs)

    /**
     * The rate over the full window, that is without the ramp-up which [getRate] applies while the window has not yet
     * filled up. When a stream has had no packets for a whole window the tracker restarts, and [getRate] then divides
     * by the time since it resumed rather than by the window; for a bursty stream that turns the first frame after an
     * idle period into a rate many times the one the stream actually sustains. This under-states the rate of a stream
     * which has genuinely just started, which is the safer direction when deciding whether it can be forwarded.
     */
    fun getRateOverFullWindow(nowMs: Long = clock.millis()): Bandwidth = getAccumulatedSize(nowMs).per(windowSize)

    val rate: Bandwidth
        get() = getRate()
    fun update(dataSize: DataSize, now: Long = clock.millis()) = tracker.update(dataSize.bits, now)
    fun getAccumulatedSize(now: Long = clock.millis()) = tracker.getAccumulatedCount(now).bits
}
