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

// RTCP Header
// 0                   1                   2                   3
// 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
// +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
// |V=2|P|    RC   |   PT=SR=200   |             length            |
// +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
// |                         SSRC of sender                        |
// +=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+

abstract class RtcpHeader {
    abstract var version: Int
    abstract var hasPadding: Boolean
    abstract var reportCount: Int
    abstract var payloadType: Int
    abstract var length: Int
    abstract var senderSsrc: Long

    companion object {
        const val SIZE_BYTES = 8
        fun fromBuffer(buf: ByteBuffer): RtcpHeader = BitBufferRtcpHeader.fromBuffer(buf)
        fun fromValues(receiver: RtcpHeader.() -> Unit): RtcpHeader = BitBufferRtcpHeader.fromValues(receiver)
    }

    fun serializeToBuffer(buf: ByteBuffer) {
        with(BitBuffer(buf)) {
            putBits(version.toByte(), 2)
            putBoolean(hasPadding)
            putBits(reportCount.toByte(), 5)
            putBits(payloadType.toByte(), 8)
            buf.putShort(length.toShort())
            buf.putInt(senderSsrc.toUInt())
        }
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
