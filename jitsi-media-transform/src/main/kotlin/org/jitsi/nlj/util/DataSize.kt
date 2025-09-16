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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings
import java.text.DecimalFormat
import kotlin.math.roundToLong

/**
 * Model an amount of data, internally represented as a number of bits.
 */
@SuppressFBWarnings(
    value = ["SING_SINGLETON_HAS_NONPRIVATE_CONSTRUCTOR"],
    justification = "Spotbugs thinks this is a singleton, but it isn't"
)
class DataSize(
    bits: Long
) : Comparable<DataSize> {

    var bits: Long = bits
        private set

    val bytes: Double = bits / 8.0
    val kiloBytes: Double = bytes / 1000.0
    val megaBytes: Double = kiloBytes / 1000.0

    operator fun minus(other: DataSize): DataSize = DataSize(bits - other.bits)

    operator fun plus(other: DataSize): DataSize = DataSize(bits + other.bits)

    operator fun times(other: Int): DataSize = DataSize(bits * other)

    operator fun times(other: Double): DataSize = DataSize((bits * other).roundToLong())

    operator fun div(other: Double): DataSize = DataSize((bits / other).roundToLong())

    operator fun div(other: DataSize): Double = bits.toDouble() / other.bits.toDouble()

    override fun toString(): String {
        // To determine which unit we'll print in,
        // find the biggest one which has a value
        // in the ones place
        val format = DecimalFormat("0.##")
        return when {
            megaBytes >= 1 -> "${format.format(megaBytes)} MB"
            kiloBytes >= 1 -> "${format.format(kiloBytes)} KB"
            bytes >= 1 -> "${format.format(bytes)} B"
            else -> "${format.format(bits)} bits"
        }
    }

    override fun compareTo(other: DataSize): Int = when {
        bits < other.bits -> -1
        bits > other.bits -> 1
        else -> 0
    }

    override fun equals(other: Any?): Boolean {
        if (other !is DataSize) {
            return false
        }
        return compareTo(other) == 0
    }

    override fun hashCode(): Int = bits.hashCode()

    fun toWholeBytes(): DataSize {
        return (bits / 8.0).roundToLong().bytes
    }

    companion object {
        val ZERO = DataSize(0)
        val INFINITY = DataSize(Long.MAX_VALUE)
    }
}

operator fun Int.times(other: DataSize): DataSize = other * this

operator fun Double.times(other: DataSize): DataSize = other * this

val Int.bits: DataSize
    get() = DataSize(this.toLong())
val Int.bytes: DataSize
    get() = DataSize(this.toLong() * 8)
val Int.kilobytes: DataSize
    get() = DataSize(this.toLong() * 1000 * 8)
val Int.megabytes: DataSize
    get() = DataSize(this.toLong() * 1000 * 1000 * 8)

val Long.bits: DataSize
    get() = DataSize(this)
val Long.bytes: DataSize
    get() = DataSize(this * 8)
val Long.kilobytes: DataSize
    get() = DataSize(this * 1000 * 8)
val Long.megabytes: DataSize
    get() = DataSize(this * 1000 * 1000 * 8)

/**
 * Returns the maximum of two [DataSize]s
 */
fun max(a: DataSize, b: DataSize): DataSize {
    return if (a >= b) a else b
}

/**
 * Returns the minimum of two [DataSize]s
 */
fun min(a: DataSize, b: DataSize): DataSize {
    return if (a <= b) a else b
}
