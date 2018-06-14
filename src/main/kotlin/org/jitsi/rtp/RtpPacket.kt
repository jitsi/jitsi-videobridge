/*
 * Copyright @ 2018 Atlassian Pty Ltd
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
package org.jitsi.rtp

import java.lang.Math.min
import java.nio.ByteBuffer
import kotlin.experimental.and

// https://github.com/kotlin-graphics/kotlin-unsigned ?

// 0                   1                   2                   3
// 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
// +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
// |V=2|P|X|  CC   |M|     PT      |       sequence number         |
// +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
// |                           timestamp                           |
// +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
// |           synchronization source (SSRC) identifier            |
// +=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+
// |            contributing source (CSRC) identifiers             |
// |                             ....                              |
// +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+

class RtpPacket(private val buf: ByteBuffer) {
    val version: Int = buf.get(0).getBits(0, 1)
    val hasPadding: Boolean = buf.get(0).getBitAsBool(2)
    val hasExtension: Boolean = buf.get(0).getBitAsBool(3)
    val csrcCount: Int = buf.get(0).getBits(4, 7)
    val marker: Boolean = buf.get(1).getBitAsBool(0)
    val payloadType: Int = buf.get(1).getBits(1, 7)
    val sequenceNumber: Int = buf.getShort(2).toInt()
    val timestamp: Long = buf.getInt(4).toLong() and 0xFFFF_FFFF
    val ssrc: Long = buf.getInt(8).toLong()
    val csrcs: List<Long>
        get() {
            val csrcs = mutableListOf<Long>()
            for (i in 0 until csrcCount) {
                csrcs.add(buf.getInt(12 + (i * 4)).toLong())
            }
            return csrcs
        }
}
