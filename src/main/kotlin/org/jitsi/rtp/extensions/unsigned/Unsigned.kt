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

package org.jitsi.rtp.extensions.unsigned

import kotlin.experimental.and

/**
 * Many times fields in packets are defined as unsigned.  Since
 * we don't have unsigned types (and attempts at using Kotlin's
 * unsigned types have not proven to be very usable), we store
 * those types as larger than they actually are ([Byte] and
 * [Short] held as [Int] and [Int] held as [Long]).  These
 * helpers are to do the conversion correctly such that
 * we get a properly 'unsigned' version of the parsed field.
 * No helper is needed for the reverse operation (the use
 * of toInt, toShort and toByte is sufficient).
 */
fun Byte.toPositiveInt(): Int = toInt() and 0xFF
fun Short.toPositiveInt(): Int = toInt() and 0xFFFF
fun Int.toPositiveLong(): Long = toLong() and 0xFFFFFFFF

//TODO: i think these should be able to make the above functions obsolete
fun Number.toPositiveShort(): Short {
    return when (this) {
        is Byte -> this.toShort() and 0xFF
        else -> this.toShort()
    }
}
fun Number.toPositiveInt(): Int {
    return when (this) {
        is Byte -> this.toInt() and 0xFF
        is Short -> this.toInt() and 0xFFFF
        else -> this.toInt()
    }
}

fun Number.toPositiveLong(): Long {
    return when (this) {
        is Byte -> this.toLong() and 0xFF
        is Short -> this.toLong() and 0xFFFF
        is Int -> this.toLong() and 0xFFFFFFFF
        else -> this.toLong()
    }
}
