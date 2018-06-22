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
package org.jitsi.rtp.attic

import java.nio.ByteBuffer

//class AbsoluteIndexRtpPacket(private val buf: ByteBuffer) : RtpPacket() {
//    override val header = AbsoluteIndexRtpHeader(buf)
//}

//class AbsoluteIndexRtpHeader(private val buf: ByteBuffer) : RtpHeader() {
//    override val version: Int = buf.get(0).getBits(0, 2).toInt()
//    override val hasPadding: Boolean = buf.get(0).getBitAsBool(2)
//    override val hasExtension: Boolean = buf.get(0).getBitAsBool(3)
//    override val csrcCount: Int = buf.get(0).getBits(4, 4).toInt()
//    override val marker: Boolean = buf.get(1).getBitAsBool(0)
//    override val payloadType: Int = buf.get(1).getBits(1, 7).toInt()
//    override val sequenceNumber: Int = buf.getShort(2).toInt()
//    override val timestamp: Long = buf.getInt(4).toLong()
//    override val ssrc: Long = buf.getInt(8).toLong()
//    override val csrcs: List<Long>
//    init {
//        val csrcStartByteIndex = 12
//        val numBytesInInt = 4
//        csrcs = (0 until csrcCount).map { csrcIdx ->
//            val currCsrcByteIndex = csrcStartByteIndex + (csrcIdx * numBytesInInt)
//            buf.getInt(currCsrcByteIndex).toLong()
//        }
//    }
//}
