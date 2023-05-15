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

import java.util.*
import kotlin.experimental.or

/**
 * Write individual bits, and unaligned sets of bits, to a [ByteArray], with an incrementing offset.
 */
class BitWriter(val buf: ByteArray, val byteOffset: Int = 0, private val byteLength: Int = buf.size) {
    private var offset = byteOffset * 8
    private val byteBound = byteOffset + byteLength

    init {
        Arrays.fill(buf, byteOffset, byteBound, 0)
    }

    fun writeBit(value: Boolean) {
        val byteIdx = offset / 8
        val bitIdx = offset % 8
        check(byteIdx < byteBound) {
            "offset $offset ($byteIdx/$bitIdx) invalid in buffer of length $byteLength after offset $byteOffset"
        }

        if (value) {
            buf[byteIdx] = buf[byteIdx] or (1 shl (7 - bitIdx)).toByte()
        }
        offset++
    }

    fun writeBits(bits: Int, value: Int) {
        check(value < (1 shl bits)) {
            "value $value cannot be represented in $bits bits"
        }
        repeat(bits) { i ->
            writeBit((value and (1 shl (bits - i - 1))) != 0)
        }
    }

    fun writeNs(n: Int, v: Int) {
        if (n == 1) return
        var w = 0
        var x = n
        while (x != 0) {
            x = x shr 1
            w++
        }
        val m = (1 shl w) - n
        if (v < m) {
            writeBits(w - 1, v)
        } else {
            writeBits(w, v + m)
        }
    }

    val remainingBits
        get() = byteBound * 8 - offset
}
