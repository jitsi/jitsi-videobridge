/*
 * Copyright @ 2019 - present 8x8, Inc.
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
package org.jitsi.nlj.rtp.scream

import org.jitsi.nlj.util.Bandwidth
import org.jitsi.nlj.util.DataSize
import org.jitsi.nlj.util.bps
import org.jitsi.nlj.util.bytes
import org.jitsi.utils.MAX_DURATION
import org.jitsi.utils.MIN_DURATION
import org.jitsi.utils.toMicros
import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.math.truncate

/*
 * Jitsi local: arithmetic on Duration, DataSize and Bandwidth which rounds the way the unit types of WebRTC do
 * (TimeDelta, DataSize and DataRate in api/units, and rtc_base/units/unit_base.h), in
 * WebRTC tag branch-heads/8078 (Chromium 156).
 *
 * A TimeDelta is a whole number of microseconds, a DataSize a whole number of bytes, and a DataRate a whole number
 * of bits per second. Multiplying or dividing one by a floating point number rounds to the nearest whole number,
 * with a half rounding away from zero. The operators in jitsi-utils and in org.jitsi.nlj.util round differently,
 * and have a different resolution. The SCReAM port uses the functions here, so that it calculates the same values
 * as WebRTC does from the same input.
 */

/** Rounds to the nearest integer, with a half rounding away from zero, as std::llround does. */
internal fun llround(x: Double): Long {
    val truncated = truncate(x)
    return if (abs(x - truncated) >= 0.5) {
        (truncated + if (x < 0) -1.0 else 1.0).toLong()
    } else {
        truncated.toLong()
    }
}

/** The duration which is this number of microseconds. */
internal fun microsToDuration(micros: Long): Duration =
    Duration.ofSeconds(Math.floorDiv(micros, 1_000_000L), Math.floorMod(micros, 1_000_000L) * 1_000L)

/** `TimeDelta * double` in WebRTC. The duration has to be finite. */
internal fun Duration.scaledBy(scalar: Double): Duration = microsToDuration(llround(toMicros() * scalar))

/**
 * `TimeDelta / TimeDelta` in WebRTC. The divisor has to be finite. An infinite dividend gives an infinite
 * result.
 */
internal fun Duration.ratioTo(other: Duration): Double = when (this) {
    MAX_DURATION -> Double.POSITIVE_INFINITY
    MIN_DURATION -> Double.NEGATIVE_INFINITY
    else -> toMicros().toDouble()
} / other.toMicros().toDouble()

/** `TimeDelta::seconds<double>()` in WebRTC. The duration has to be finite. */
internal fun Duration.secondsAsDouble(): Double = toMicros().toDouble() * (1.0 / 1_000_000.0)

/** `Timestamp - Timestamp` in WebRTC, where either timestamp can be infinite. */
internal fun timeBetween(earlier: Instant, later: Instant): Duration = when {
    later == Instant.MAX || earlier == Instant.MIN -> MAX_DURATION
    later == Instant.MIN || earlier == Instant.MAX -> MIN_DURATION
    else -> Duration.between(earlier, later)
}

/** `Timestamp + TimeDelta` in WebRTC, where the timestamp can be infinite. */
internal fun Instant.plusFinite(duration: Duration): Instant =
    if (this == Instant.MAX || this == Instant.MIN) this else this + duration

/** The number of whole bytes. A DataSize in WebRTC is always a whole number of bytes. */
internal fun DataSize.wholeBytes(): Long = bits / 8

/** `DataSize * double` in WebRTC. */
internal fun DataSize.scaledBy(scalar: Double): DataSize = llround(wholeBytes() * scalar).bytes

/** `DataSize / DataSize` in WebRTC. */
internal fun DataSize.ratioTo(other: DataSize): Double = wholeBytes().toDouble() / other.wholeBytes().toDouble()

/** `DataRate * double` in WebRTC. The rate has to be finite. */
internal fun Bandwidth.scaledBy(scalar: Double): Bandwidth = llround(bps * scalar).bps

/** `DataRate * TimeDelta` in WebRTC. Both have to be finite. */
internal fun dataSentAt(rate: Bandwidth, duration: Duration): DataSize {
    val microbits = rate.bps * duration.toMicros()
    return ((microbits + 4_000_000) / 8_000_000).bytes
}

/** `DataSize / TimeDelta` in WebRTC. Both have to be finite. */
internal fun rateOf(size: DataSize, duration: Duration): Bandwidth =
    (size.wholeBytes() * 8_000_000 / duration.toMicros()).bps

/** `DataSize / DataRate` in WebRTC. Both have to be finite. */
internal fun timeToSend(size: DataSize, rate: Bandwidth): Duration =
    microsToDuration(size.wholeBytes() * 8_000_000 / rate.bps)
