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

package org.jitsi.nlj.util

import org.jitsi.utils.toDouble
import org.jitsi.utils.toRoundedMicros
import java.text.DecimalFormat
import java.time.Duration
import kotlin.math.round

/**
 * [Bandwidth] models a current bandwidth, represented as a rate of bits per second.
 */
@JvmInline
value class Bandwidth(val bps: Long) : Comparable<Bandwidth> {

    constructor(bps: Double) : this(bps.toLong())
    val bytesPerSec: Double
        get() = bps.toDouble() / 8
    val kbps: Double
        get() = bps.toDouble() / 1000
    val mbps: Double
        get() = bps.toDouble() / (1000 * 1000)

    operator fun minus(other: Bandwidth): Bandwidth = Bandwidth(bps - other.bps)

    operator fun plus(other: Bandwidth): Bandwidth = Bandwidth(bps + other.bps)

    /**
     * For multiplication, we support multiplying against
     * a normal number (not another bandwidth).  This allows
     * applying some factor to a given bandwidth, for example
     *
     * currentBandwidth *= 0.95
     *
     * to reduce 'currentBandwidth' by 5%
     */
    operator fun times(other: Double): Bandwidth = Bandwidth(round(bps.toDouble() * other))

    operator fun times(other: Int): Bandwidth = Bandwidth(bps * other)

    /**
     * For division, we support both dividing by
     * a normal number (giving a bandwidth), and dividing
     * by another bandwidth, giving a number
     */
    operator fun div(other: Double): Bandwidth = Bandwidth(round(bps / other))

    operator fun div(other: Int): Bandwidth = Bandwidth(bps / other)

    operator fun div(other: Bandwidth): Double = bps.toDouble() / other.bps.toDouble()

    override fun compareTo(other: Bandwidth): Int = bps.compareTo(other.bps)

    override fun toString(): String {
        // To determine which unit we'll print in,
        // find the biggest one which has a value
        // in the ones place
        val format = DecimalFormat("0.##")
        return when {
            mbps >= 1 -> "${format.format(mbps)} mbps"
            kbps >= 1 -> "${format.format(kbps)} kbps"
            else -> "${format.format(bps)} bps"
        }
    }

    fun isInfinite() = (this == INFINITY || this == MINUS_INFINITY)

    fun isFinite() = !isInfinite()

    companion object {
        fun fromString(str: String): Bandwidth {
            val (digits, notDigits) = str.partition { it.isDigit() }
            val amount = digits.toInt()
            return when (val unit = notDigits.trim().lowercase()) {
                "bps" -> amount.bps
                "kbps" -> amount.kbps
                "mbps" -> amount.mbps
                else -> throw IllegalArgumentException("Unrecognized unit $unit")
            }
        }

        val INFINITY = Bandwidth(Long.MAX_VALUE)
        val ZERO = Bandwidth(0.0)
        val MINUS_INFINITY = Bandwidth(Long.MIN_VALUE)
    }
}

operator fun Double.times(other: Bandwidth): Bandwidth = Bandwidth(round(this * other.bps))
operator fun Int.times(other: Bandwidth): Bandwidth = Bandwidth(this * other.bps)

val Int.bps: Bandwidth
    get() = Bandwidth(this.toLong())
val Int.bytesPerSec: Bandwidth
    get() = Bandwidth(this * 8L)
val Int.kbps: Bandwidth
    get() = Bandwidth(this * 1000L)
val Int.mbps: Bandwidth
    get() = Bandwidth(this * 1000L * 1000L)

val Float.bps: Bandwidth
    get() = Bandwidth(this.toLong())
val Float.bytesPerSec: Bandwidth // Bytes per second
    get() = Bandwidth(this.toDouble() * 8)
val Float.kbps: Bandwidth
    get() = Bandwidth(this.toDouble() * 1000)
val Float.mbps: Bandwidth
    get() = Bandwidth(this.toDouble() * 1000 * 1000)

val Double.bps: Bandwidth
    get() = Bandwidth(this)
val Double.bytesPerSec: Bandwidth // Bytes per second
    get() = Bandwidth(this * 8)
val Double.kbps: Bandwidth
    get() = Bandwidth(this * 1000)
val Double.mbps: Bandwidth
    get() = Bandwidth(this * 1000 * 1000)

val Long.bps: Bandwidth
    get() = Bandwidth(this)
val Long.bytesPerSec: Bandwidth // Bytes per second
    get() = Bandwidth(this * 8)
val Long.kbps: Bandwidth
    get() = Bandwidth(this * 1000)
val Long.mbps: Bandwidth
    get() = Bandwidth(this * 1000 * 1000)

/**
 * Create a [Bandwidth] from a [DataSize] over a given time
 */
fun DataSize.per(duration: Duration): Bandwidth {
    return Bandwidth((this.bits * 1_000_000) / duration.toRoundedMicros())
}

/**
 * Create a [Duration] from a [DataSize] per [Bandwidth]
 */
operator fun DataSize.div(bandwidth: Bandwidth): Duration {
    return Duration.ofNanos(round((this.bits.toDouble() / bandwidth.bps * 1e9)).toLong())
}

/**
 * create a [DataSize] from a [Bandwidth] times [Duration]
 */
operator fun Bandwidth.times(duration: Duration): DataSize {
    return round(bps * duration.toDouble()).toLong().bits
}

/**
 * Returns the sum of all elements in the collection.
 */
fun Iterable<Bandwidth>.sum(): Bandwidth = reduce(Bandwidth::plus)

/**
 * Returns the maximum of two [Bandwidth]s
 */
fun max(a: Bandwidth, b: Bandwidth): Bandwidth {
    return if (a >= b) a else b
}

/**
 * Returns the minimum of two [Bandwidth]s
 */
fun min(a: Bandwidth, b: Bandwidth): Bandwidth {
    return if (a <= b) a else b
}

/**
 * Ensures that this value is not greater than the specified [maximumValue].
 *
 * @return this value if it's less than or equal to the [maximumValue] or the [maximumValue] otherwise.
 */
public fun Bandwidth.coerceAtMost(maximumValue: Bandwidth): Bandwidth {
    return if (this > maximumValue) maximumValue else this
}
