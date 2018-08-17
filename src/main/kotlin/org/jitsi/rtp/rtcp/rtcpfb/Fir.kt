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
package org.jitsi.rtp.rtcp.rtcpfb

import unsigned.toUInt
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
 * TODO: although the media ssrc is depicted in the FCI above, I believe
 * this is actually the media ssrc from the common RTCPFB header, so
 * the FCI for FIR contains just the sequence number
 */
class Fir : FeedbackControlInformation {
    var seqNum: Int
    override val size: Int = 4
    override var buf: ByteBuffer? = null

    companion object {
        const val SIZE_BYTES = 4
        fun getSeqNum(buf: ByteBuffer) = buf.get(0).toUInt()
        fun setSeqNum(buf: ByteBuffer, seqNum: Int) {
            buf.put(0, seqNum.toByte())
        }
    }

    constructor(buf: ByteBuffer) {
        this.buf = buf.slice()
        this.seqNum = getSeqNum(buf)
    }

    constructor(seqNum: Int = 0) {
        this.seqNum = seqNum
    }

    override fun getBuffer(): ByteBuffer {
        if (this.buf == null) {
            this.buf = ByteBuffer.allocate(Fir.SIZE_BYTES)
        }
        setSeqNum(buf!!, seqNum)

        return this.buf!!
    }
}
