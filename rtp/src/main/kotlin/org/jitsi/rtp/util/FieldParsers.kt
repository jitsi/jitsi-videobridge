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

package org.jitsi.rtp.util

import org.jitsi.rtp.extensions.bytearray.get3Bytes
import org.jitsi.rtp.extensions.bytearray.getInt
import org.jitsi.rtp.extensions.bytearray.getShort
import org.jitsi.rtp.extensions.bytearray.putBits
import org.jitsi.rtp.extensions.getBits
import org.jitsi.rtp.extensions.unsigned.toPositiveInt
import org.jitsi.rtp.extensions.unsigned.toPositiveLong

// TODO: could do some kind of 'Offset' inline class? or 'field' which described the offset
// and size?

fun ByteArray.getBitsAsInt(byteOffset: Int, bitStartPos: Int, numBits: Int): Int =
    get(byteOffset).getBits(bitStartPos, numBits).toPositiveInt()

fun ByteArray.putNumberAsBits(byteOffset: Int, bitOffset: Int, numBits: Int, value: Number) {
    putBits(byteOffset, bitOffset, value.toByte(), numBits)
}
fun ByteArray.getByteAsInt(offset: Int): Int =
    get(offset).toPositiveInt()

fun ByteArray.getShortAsInt(offset: Int): Int =
    getShort(offset).toPositiveInt()

fun ByteArray.get3BytesAsInt(offset: Int): Int =
    get3Bytes(offset).toPositiveInt()

fun ByteArray.getIntAsLong(offset: Int): Long =
    getInt(offset).toPositiveLong()
