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

@file:JvmName("ClockUtils")

package org.jitsi.nlj.util

import org.jitsi.utils.TimeUtils
import java.time.Duration
import java.time.Instant

@JvmField
val NEVER: Instant = Instant.MIN

fun Instant.formatMilli(): String = TimeUtils.formatTimeAsFullMillis(this.epochSecond, this.nano)

fun Duration.formatMilli(): String = TimeUtils.formatTimeAsFullMillis(this.seconds, this.nano)

/**
 * Converts this instant to the number of microseconds from the epoch
 * of 1970-01-01T00:00:00Z.
 *
 * If this instant represents a point on the time-line too far in the future
 * or past to fit in a [Long] microseconds, then an exception is thrown.
 *
 * If this instant has greater than microsecond precision, then the conversion
 * will drop any excess precision information as though the amount in nanoseconds
 * was subject to integer division by one thousand.
 *
 * @return the number of microseconds since the epoch of 1970-01-01T00:00:00Z
 * @throws ArithmeticException if numeric overflow occurs
 */
fun Instant.toEpochMicro(): Long {
    return if (this.epochSecond < 0 && this.nano > 0) {
        val micros = Math.multiplyExact(this.epochSecond + 1, 1000_000)
        val adjustment: Long = (this.nano / 1000 - 1000_000).toLong()
        Math.addExact(micros, adjustment)
    } else {
        val micros = Math.multiplyExact(this.epochSecond, 1000_000)
        Math.addExact(micros, (this.nano / 1000).toLong())
    }
}

/**
 * Obtains an instance of [Instant] using microseconds from the
 * epoch of 1970-01-01T00:00:00Z.
 * <p>
 * The seconds and nanoseconds are extracted from the specified milliseconds.
 *
 * @param epochMicro the number of microseconds from 1970-01-01T00:00:00Z
 * @return an instant, not null
 * @throws DateTimeException if the instant exceeds the maximum or minimum instant
 */
fun instantOfEpochMicro(epochMicro: Long): Instant {
    val secs = Math.floorDiv(epochMicro, 1000_000)
    val micros = Math.floorMod(epochMicro, 1000_000)
    return Instant.ofEpochSecond(secs, micros * 1000L)
}

fun <T> Iterable<T>.sumOf(selector: (T) -> Duration): Duration {
    var sum: Duration = Duration.ZERO
    for (element in this) {
        sum += selector(element)
    }
    return sum
}
