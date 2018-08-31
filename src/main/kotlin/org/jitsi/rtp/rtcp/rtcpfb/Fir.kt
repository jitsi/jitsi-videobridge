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

import toUInt
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
 * The SSRC field in the FIR FCI block is used to set the media sender
 * SSRC, the media source SSRC field in the RTCPFB header is unsed for FIR packets.
 */
class Fir : FeedbackControlInformation {
    var seqNum: Int
    var ssrc: Long
    override val size: Int = SIZE_BYTES
    override var buf: ByteBuffer? = null
    override val fmt: Int = 4

    companion object {
        const val SIZE_BYTES = 8
        fun getSsrc(buf: ByteBuffer): Long = buf.getInt(0).toLong()
        fun setSsrc(buf: ByteBuffer, ssrc: Long) = buf.putInt(0, ssrc.toUInt())

        fun getSeqNum(buf: ByteBuffer) = buf.get(4).toUInt()
        fun setSeqNum(buf: ByteBuffer, seqNum: Int) {
            buf.put(4, seqNum.toByte())
        }
    }

    constructor(buf: ByteBuffer) {
        this.buf = buf.slice()
        this.ssrc = getSsrc(this.buf!!)
        this.seqNum = getSeqNum(this.buf!!)
    }

    constructor(
        ssrc: Long = 0,
        seqNum: Int = 0
    ) {
        this.ssrc = ssrc
        this.seqNum = seqNum
    }

    override fun getBuffer(): ByteBuffer {
        if (this.buf == null) {
            this.buf = ByteBuffer.allocate(Fir.SIZE_BYTES)
        }
        setSsrc(buf!!, this.ssrc)
        setSeqNum(buf!!, seqNum)

        return this.buf!!
    }
}
