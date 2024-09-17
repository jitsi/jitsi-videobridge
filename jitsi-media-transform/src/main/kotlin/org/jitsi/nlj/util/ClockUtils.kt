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
import java.time.Clock
import java.time.Duration
import java.time.Instant

@JvmField
val NEVER: Instant = Instant.MIN

fun Instant.formatMilli(): String = TimeUtils.formatTimeAsFullMillis(this.epochSecond, this.nano)

fun Duration.formatMilli(): String = TimeUtils.formatTimeAsFullMillis(this.seconds, this.nano)

/**
 * Like [Instant.toEpochMilli], but rounded to nearest rather than rounded to zero.
 *
 * This is needed to be bit-exact with Google CC unit tests, since libwebrtc clocks round this way
 */
fun Instant.toRoundedEpochMilli(): Long {
    var ret = toEpochMilli()
    val remainder = nano.floorMod(1_000_000)
    if (remainder > 499_999) {
        ret++
    }
    return ret
}

fun Instant.isInfinite(): Boolean = (this == Instant.MAX || this == Instant.MIN)

fun Instant.isFinite(): Boolean = !this.isInfinite()

/**
 * Like [Clock.millis], but rounded to nearest rather than rounded to zero.
 */
fun Clock.roundedMillis() = this.instant().toRoundedEpochMilli()

/**
 * Returns the maximum of two [Instant]s
 */
fun max(a: Instant, b: Instant): Instant {
    return if (a >= b) a else b
}

/**
 * Returns the minimum of two [Instant]s
 */
fun min(a: Instant, b: Instant): Instant {
    return if (a <= b) a else b
}
