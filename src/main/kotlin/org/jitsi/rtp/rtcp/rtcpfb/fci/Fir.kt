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

package org.jitsi.rtp.rtcp.rtcpfb.fci

import org.jitsi.rtp.extensions.unsigned.toPositiveInt
import org.jitsi.rtp.extensions.unsigned.toPositiveLong
import java.nio.ByteBuffer

/**
 * https://tools.ietf.org/html/rfc5104#section-4.3.1.1
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                              SSRC                             |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * | Seq nr.       |    Reserved                                   |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *
 * The SSRC field in the FIR FCI block is used to set the media sender
 * SSRC, the media source SSRC field in the RTCPFB header is unsed for FIR packets.
 */
class Fir(
    ssrc: Long = -1,
    seqNum: Int = -1
) : FeedbackControlInformation() {
    override val sizeBytes: Int = SIZE_BYTES

    var ssrc: Long = ssrc
        private set

    var seqNum: Int = seqNum
        private set

    override fun serializeTo(buf: ByteBuffer) {
        buf.putInt(ssrc.toInt())
        buf.put(seqNum.toByte())
        // Add padding for the reserved chunk
        repeat (3) { buf.put(0x00) }
    }

    public override fun clone(): Fir =
        Fir(ssrc, seqNum)

    companion object {
        const val SIZE_BYTES = 8

        fun fromBuffer(buf: ByteBuffer): Fir {
            val ssrc = buf.getInt().toPositiveLong()
            val seqNum = buf.get().toPositiveInt()
            // Parse passed the reserved chunk
            repeat (3) { buf.get() }

            return Fir(ssrc, seqNum)
        }
    }
}