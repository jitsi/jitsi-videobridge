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

import java.text.DecimalFormat
import java.time.Duration

/**
 * [Bandwidth] models a current bandwidth, represented as a rate
 * of bits per second.
 */
class Bandwidth(bps: Double) : Comparable<Bandwidth> {
    var bps: Double = bps
        private set

    val kbps: Double = bps / 1000
    val mbps: Double = bps / (1000 * 1000)

    operator fun minus(other: Bandwidth): Bandwidth =
        Bandwidth(bps - other.bps)

    operator fun minusAssign(other: Bandwidth) {
        bps -= other.bps
    }

    operator fun plus(other: Bandwidth): Bandwidth =
        Bandwidth(bps + other.bps)

    operator fun plusAssign(other: Bandwidth) {
        bps += other.bps
    }

    /**
     * For multiplication, we support multiplying against
     * a normal number (not another bandwidth).  This allows
     * applying some factor to a given bandwidth, for example
     *
     * currentBandwidth *= *.95
     *
     * to reduce 'currentBandwidth' by 5%
     */
    operator fun times(other: Double): Bandwidth =
        Bandwidth(bps * other)

    operator fun timesAssign(other: Double) {
        bps *= other
    }

    operator fun times(other: Int): Bandwidth =
        Bandwidth(bps * other)

    operator fun timesAssign(other: Int) {
        bps *= other
    }

    /**
     * For division, we support both dividing by
     * a normal number (giving a bandwidth), and dividing
     * by another bandwidth, giving a number
     */
    operator fun div(other: Double): Bandwidth =
        Bandwidth(bps / other)

    operator fun divAssign(other: Double) {
        bps /= other
    }

    operator fun div(other: Int): Bandwidth =
        Bandwidth(bps / other)

    operator fun divAssign(other: Int) {
        bps /= other
    }

    operator fun div(other: Bandwidth): Double =
        bps / other.bps

    override fun compareTo(other: Bandwidth): Int = (bps - other.bps).toInt()

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

    override fun equals(other: Any?): Boolean {
        if (other !is Bandwidth) {
            return false
        }
        return compareTo(other) == 0
    }

    override fun hashCode(): Int = bps.hashCode()

    companion object {
        fun fromString(str: String): Bandwidth {
            val (digits, notDigits) = str.partition { it.isDigit() }
            val amount = digits.toInt()
            val unit = notDigits.trim().toLowerCase()
            return when (unit) {
                "bps" -> amount.bps
                "kbps" -> amount.kbps
                "mbps" -> amount.mbps
                else -> throw IllegalArgumentException("Unrecognized unit $unit")
            }
        }
    }
}

val Int.bps: Bandwidth
    get() = Bandwidth(this.toDouble())
val Int.kbps: Bandwidth
    get() = Bandwidth(this.toDouble() * 1000)
val Int.mbps: Bandwidth
    get() = Bandwidth(this.toDouble() * 1000 * 1000)

val Float.bps: Bandwidth
    get() = Bandwidth(this.toDouble())
val Float.kbps: Bandwidth
    get() = Bandwidth(this.toDouble() * 1000)
val Float.mbps: Bandwidth
    get() = Bandwidth(this.toDouble() * 1000 * 1000)

val Double.bps: Bandwidth
    get() = Bandwidth(this.toDouble())
val Double.kbps: Bandwidth
    get() = Bandwidth(this.toDouble() * 1000)
val Double.mbps: Bandwidth
    get() = Bandwidth(this.toDouble() * 1000 * 1000)

val Long.bps: Bandwidth
    get() = Bandwidth(this.toDouble())
val Long.kbps: Bandwidth
    get() = Bandwidth(this.toDouble() * 1000)
val Long.mbps: Bandwidth
    get() = Bandwidth(this.toDouble() * 1000 * 1000)

/**
 * Create a [Bandwidth] from a [DataSize] over a given time
 */
fun DataSize.per(duration: Duration): Bandwidth {
    return Bandwidth((this.bits / duration.seconds).toDouble())
}
