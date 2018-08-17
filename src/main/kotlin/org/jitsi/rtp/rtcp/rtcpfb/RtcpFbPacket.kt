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

import org.jitsi.rtp.extensions.subBuffer
import org.jitsi.rtp.rtcp.RtcpHeader
import org.jitsi.rtp.rtcp.RtcpPacket
import toUInt
import unsigned.toUInt
import unsigned.toULong
import java.nio.ByteBuffer
import java.util.*

abstract class FeedbackControlInformation {
    abstract val size: Int
    protected abstract var buf: ByteBuffer?
    abstract fun getBuffer(): ByteBuffer
}


/**
 * https://tools.ietf.org/html/rfc4585#section-6.2.1
 */
//class PayloadSpecificFeedbackInformation : FeedbackControlInformation() {
//
//}

/**
 * https://tools.ietf.org/html/rfc4585#section-6.1
 *    0                   1                   2                   3
 *    0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 *    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *    |V=2|P|   FMT   |       PT      |          length               |
 *    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *    |                  SSRC of packet sender                        |
 *    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *    |                  SSRC of media source                         |
 *    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *    :            Feedback Control Information (FCI)                 :
 *    :                                                               :
 */
//TODO: this changes the common RTCP header (the FMT field in place of
// the RC field).  Should the header parse that field, but hold it
// generically?  Should it make it abstract?  Should it ignore it
// altogether?
class RtcpFbPacket : RtcpPacket {
    private var buf: ByteBuffer? = null
    override var header: RtcpHeader
    var mediaSourceSsrc: Long
    var feedbackControlInformation: FeedbackControlInformation
    override val size: Int
        get() = RtcpHeader.SIZE_BYTES + 4 /* mediaSourceSsrc */ + feedbackControlInformation.size

    companion object {
        fun getMediaSourceSsrc(buf: ByteBuffer): Long = buf.getInt(8).toULong()
        fun setMediaSourceSsrc(buf: ByteBuffer, mediaSourceSsrc: Long) { buf.putInt(8, mediaSourceSsrc.toUInt()) }

        /**
         * Note that the buffer passed to these two methods, unlike in most other helpers, must already
         * begin at the start of the FCI portion of the header.
         */
        fun getFeedbackControlInformation(fciBuf: ByteBuffer, payloadType: Int, fmt: Int): FeedbackControlInformation {
            return when (payloadType) {
                205 -> {
                    when (fmt) {
                        1 -> Nack(fciBuf)
                        15 -> TODO("tcc https://tools.ietf.org/html/draft-holmer-rmcat-transport-wide-cc-extensions-01#section-3.1")
                        else -> throw Exception("Unrecognized RTCPFB format: $fmt")
                    }
                }
                206 -> {
                    when (fmt) {
                        1 -> Pli()
                        2 -> TODO("sli")
                        3 -> TODO("rpsi")
                        4 -> TODO("fir")
                        15 -> TODO("afb")
                        else -> throw Exception("Unrecognized RTCPFB format: pt 206, fmt $fmt")
                    }
                }
                else -> throw Exception("Unrecognized RTCPFB pt: $payloadType")
            }
        }
    }

    constructor(buf: ByteBuffer) : super() {
        this.buf = buf
        this.header = RtcpHeader(buf)
        this.mediaSourceSsrc = getMediaSourceSsrc(buf)
        this.feedbackControlInformation =
                getFeedbackControlInformation(
                    buf.subBuffer(12),
                    header.payloadType,
                    header.reportCount
                )
    }

    constructor(
        header: RtcpHeader = RtcpHeader(),
        mediaSourceSsrc: Long = 0,
        feedbackControlInformation: FeedbackControlInformation
    ) : super() {
        this.header = header
        this.mediaSourceSsrc = mediaSourceSsrc
        this.feedbackControlInformation = feedbackControlInformation
    }

    override fun getBuffer(): ByteBuffer {
        if (this.buf == null) {
            this.buf = ByteBuffer.allocate(header.size + 4 + feedbackControlInformation.size)
        }
        this.buf!!.put(header.getBuffer())
        this.buf!!.putInt(mediaSourceSsrc.toUInt())
        this.buf!!.put(feedbackControlInformation.getBuffer())

        return this.buf!!
    }

    override fun toString(): String {
        return with (StringBuffer()) {
            appendln("RTCPFB packet")
            appendln(feedbackControlInformation.toString())
            toString()
        }
    }
}
