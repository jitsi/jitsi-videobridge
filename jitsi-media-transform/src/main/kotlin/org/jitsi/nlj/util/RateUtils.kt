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

import java.time.Duration

/**
 * This method isn't technically necessary, but provides better readability, i.e.:
 *
 * val time = howLongToSend(1.megabytes()) atRate 1.mbps()
 *
 * as opposed to
 *
 * val time = 1.megabytes() atRate 1.mbps()
 */
fun howLongToSend(size: DataSize): DataSize = size

infix fun DataSize.atRate(bw: Bandwidth): Duration {
    val bitsPerNano = bw.bps / 1e9
    return Duration.ofNanos((this.bits / bitsPerNano).toLong())
}

fun howMuchCanISendAtRate(bw: Bandwidth): Bandwidth = bw

infix fun Bandwidth.`in`(time: Duration): DataSize {
    return DataSize((bps * (time.seconds + time.nano / 1e9)).toLong())
}
