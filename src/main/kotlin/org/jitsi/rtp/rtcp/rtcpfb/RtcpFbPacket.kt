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
import org.jitsi.rtp.extensions.toHex
import org.jitsi.rtp.rtcp.RtcpHeader
import org.jitsi.rtp.rtcp.RtcpPacket
import toUInt
import unsigned.toUInt
import unsigned.toULong
import java.nio.ByteBuffer
import java.util.*

abstract class FeedbackControlInformation {
    abstract val size: Int
    abstract val fmt: Int
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
open class RtcpFbPacket : RtcpPacket {
    private var buf: ByteBuffer? = null
    override var header: RtcpHeader
    var mediaSourceSsrc: Long
    var feedbackControlInformation: FeedbackControlInformation
    override val size: Int
        get() = RtcpHeader.SIZE_BYTES + 4 /* mediaSourceSsrc */ + feedbackControlInformation.size

    companion object {
        fun getMediaSourceSsrc(buf: ByteBuffer): Long = buf.getInt(8).toULong()
        fun setMediaSourceSsrc(buf: ByteBuffer, mediaSourceSsrc: Long) { buf.putInt(8, mediaSourceSsrc.toUInt()) }

        fun getFeedbackControlInformation(buf: ByteBuffer, payloadType: Int, fmt: Int): FeedbackControlInformation {
            val fciBuf = buf.subBuffer(12)
            return when (payloadType) {
                205 -> {
                    when (fmt) {
                        Nack.FMT -> Nack(fciBuf)
                        Tcc.FMT -> Tcc(fciBuf)
                        else -> throw Exception("Unrecognized RTCPFB format: $fmt")
                    }
                }
                206 -> {
                    when (fmt) {
                        1 -> Pli()
                        2 -> TODO("sli")
                        3 -> TODO("rpsi")
                        4 -> Fir(fciBuf)
                        15 -> TODO("afb")
                        else -> throw Exception("Unrecognized RTCPFB format: pt 206, fmt $fmt")
                    }
                }
                else -> throw Exception("Unrecognized RTCPFB pt: $payloadType")
            }
        }

        fun setFeedbackControlInformation(buf: ByteBuffer, fci: FeedbackControlInformation) {
            val fciBuf = buf.subBuffer(12)
            fciBuf.put(fci.getBuffer())
        }
    }

    constructor(buf: ByteBuffer) : super() {
        this.buf = buf.slice()
        this.header = RtcpHeader(buf)
        this.mediaSourceSsrc = getMediaSourceSsrc(buf)
        this.feedbackControlInformation =
                getFeedbackControlInformation(
                    buf,
                    header.payloadType,
                    header.reportCount
                )
    }

    @JvmOverloads
    constructor(
        header: RtcpHeader = RtcpHeader(),
        mediaSourceSsrc: Long = 0,
        feedbackControlInformation: FeedbackControlInformation
    ) : super() {
        this.header = header
        // TODO: hard code this here? or elsewhere?
        this.header.payloadType = 205
        this.mediaSourceSsrc = mediaSourceSsrc
        this.feedbackControlInformation = feedbackControlInformation
    }

    override fun getBuffer(): ByteBuffer {
        val neededSize = header.size + 4 + feedbackControlInformation.size
        if (this.buf == null || this.buf!!.capacity() < neededSize) {
            this.buf = ByteBuffer.allocate(neededSize)
        }
        buf!!.rewind()
        // We need to update the length in the header to match the current content
        // of the packet (which may have changed)
        header.length = ((neededSize + 3) / 4 - 1)
        header.reportCount = feedbackControlInformation.fmt
        //TODO: we should also not do padding anywhere else (except for in 'internal'
        // fields which need it) and handle it here (add any padding, set the padding bit)
        //TODO: should have explicit setters for these instead of using relative positions
        this.buf!!.put(header.getBuffer())
        setMediaSourceSsrc(this.buf!!, mediaSourceSsrc)
        val bufPositionBefore = buf!!.position()
        try {
            setFeedbackControlInformation(buf!!, feedbackControlInformation)
        } catch (e: Exception) {
            println("exception serializing fci, buf position was $bufPositionBefore, " +
                    "capacity is ${buf!!.capacity()}, fci size is ${feedbackControlInformation.size} ")
            throw e
        }

        this.buf!!.rewind()

        return this.buf!!
    }

    override fun toString(): String {
        return with (StringBuffer()) {
            appendln("RTCPFB packet")
            // TODO: the header may not have been "sync'd" at this point (e.g. length, fmt not set)
            append(header.toString())
            appendln("media source ssrc: $mediaSourceSsrc")
            appendln(feedbackControlInformation.toString())
            toString()
        }
    }
}
