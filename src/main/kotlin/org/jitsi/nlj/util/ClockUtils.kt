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

import java.time.Duration
import java.time.Instant
import org.jitsi.utils.TimeUtils

@JvmField
val NEVER: Instant = Instant.MIN

fun Instant.formatMilli(): String = TimeUtils.formatTimeAsFullMillis(this.epochSecond, this.nano)

fun Duration.formatMilli(): String = TimeUtils.formatTimeAsFullMillis(this.seconds, this.nano)

fun <T> Iterable<T>.sumOf(selector: (T) -> Duration): Duration {
    var sum: Duration = Duration.ZERO
    for (element in this) {
        sum += selector(element)
    }
    return sum
}
