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
package org.jitsi.rtp.rtcp

import org.jitsi.rtp.util.BitBuffer
import toUInt
import java.nio.ByteBuffer

// https://tools.ietf.org/html/rfc3550#section-6.1
// 0                   1                   2                   3
// 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
// +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
// |V=2|P|    RC   |   PT=SR=200   |             length            |
// +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
// |                         SSRC of sender                        |
// +=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+

open class RtcpHeader {
    private val buf: ByteBuffer
    var version: Int
        get() = RtcpHeaderUtils.getVersion(buf)
        set(version) = RtcpHeaderUtils.setVersion(buf, version)

    var hasPadding: Boolean
        get() = RtcpHeaderUtils.hasPadding(buf)
        set(hasPadding) = RtcpHeaderUtils.setPadding(buf, hasPadding)

    var reportCount: Int
        get() = RtcpHeaderUtils.getReportCount(buf)
        set(reportCount) = RtcpHeaderUtils.setReportCount(buf, reportCount)

    var payloadType: Int
        get() = RtcpHeaderUtils.getPayloadType(buf)
        set(payloadType) = RtcpHeaderUtils.setPayloadType(buf, payloadType)

    var length: Int
        get() = RtcpHeaderUtils.getLength(buf)
        set(length) = RtcpHeaderUtils.setLength(buf, length)

    var senderSsrc: Long
        get() = RtcpHeaderUtils.getSenderSsrc(buf)
        set(senderSsrc) = RtcpHeaderUtils.setSenderSsrc(buf, senderSsrc)

    constructor(buf: ByteBuffer) : super() {
        //TODO duplicate it?
        this.buf = buf
    }

    constructor(
        version: Int = 2,
        hasPadding: Boolean = false,
        reportCount: Int = 0,
        payloadType: Int = 0,
        length: Int = 0,
        senderSsrc: Long = 0
    ) : super() {
        this.buf = ByteBuffer.allocate(RtcpHeader.SIZE_BYTES)
        this.version = version
        this.hasPadding = hasPadding
        this.reportCount = reportCount
        this.payloadType = payloadType
        this.length = length
        this.senderSsrc = senderSsrc
    }

    companion object {
        const val SIZE_BYTES = 8
//        fun fromBuffer(buf: ByteBuffer): RtcpHeader = BitBufferRtcpHeader.fromBuffer(buf)
//        fun fromValues(receiver: RtcpHeader.() -> Unit): RtcpHeader = BitBufferRtcpHeader.fromValues(receiver)
    }


    fun serializeToBuffer(buf: ByteBuffer) {
        buf.put(this.buf)
    }

    override fun toString(): String {
        return with(StringBuffer()) {
            appendln("version: $version")
            appendln("hasPadding: $hasPadding")
            appendln("reportCount: $reportCount")
            appendln("payloadType: $payloadType")
            appendln("length: ${this@RtcpHeader.length}")
            appendln("senderSsrc: $senderSsrc")
            this.toString()
        }
    }
}
