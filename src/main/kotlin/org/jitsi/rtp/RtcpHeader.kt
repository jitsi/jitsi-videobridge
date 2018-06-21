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

import java.nio.ByteBuffer

// RTCP Header
// 0                   1                   2                   3
// 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
// +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
// |V=2|P|    RC   |   PT=SR=200   |             length            |
// +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
// |                         SSRC of sender                        |
// +=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+

/**
 * [buf] is a buffer whose start is the start of the RTCP header
 */
class RtcpHeader(buf: ByteBuffer) {
    val version: Int = buf.get(0).getBits(0, 1).toInt()
    val hasPadding: Boolean = buf.get(0).getBitAsBool(2)
    val reportCount: Int = buf.get(0).getBits(3, 7).toInt()
    val payloadType: Int = buf.get(1).getBits(1, 7).toInt()
    val length: Int = buf.get(2).getBits(16, 31).toInt()
    val senderSsrc: Long = buf.getInt(4).toLong()

    init {
        parseFields(buf) {
            //version = valueOf(2.bits())
            //padding(1.bit.toBool)
            //reportCount(7.bits())
        }

    }
}



//class Test(buf: ByteBuffer) {
//    val version = Field(2, FieldSizeType.BITS)
//
//    init {
//        version.parse(buf)
//    }
//}


fun parseFields(buf: ByteBuffer, block: RtcpHeader.() -> Unit) {

}
