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

import org.jitsi.rtp.util.BitBuffer
import unsigned.toUInt
import unsigned.toULong
import java.nio.ByteBuffer
import kotlin.properties.Delegates

internal class BitBufferRtpHeader : RtpHeader() {
    override var version: Int by Delegates.notNull()
    override var hasPadding: Boolean by Delegates.notNull()
    override var hasExtension: Boolean by Delegates.notNull()
    override var csrcCount: Int by Delegates.notNull()
    override var marker: Boolean by Delegates.notNull()
    override var payloadType: Int by Delegates.notNull()
    override var sequenceNumber: Int by Delegates.notNull()
    override var timestamp: Long by Delegates.notNull()
    override var ssrc: Long by Delegates.notNull()
    override var csrcs: List<Long> = listOf()
    override var extensions: Map<Int, RtpHeaderExtension> = mapOf()

    companion object {
        fun fromBuffer(buf: ByteBuffer) : RtpHeader {
            val bitBuffer = BitBuffer(buf)
            return with (BitBufferRtpHeader()) {
                version = bitBuffer.getBits(2).toUInt()
                hasPadding = bitBuffer.getBitAsBoolean()
                hasExtension = bitBuffer.getBitAsBoolean()
                csrcCount = bitBuffer.getBits(4).toUInt()
                marker = bitBuffer.getBitAsBoolean()
                payloadType = bitBuffer.getBits(7).toUInt()
                sequenceNumber = buf.getShort().toUInt()
                timestamp = buf.getInt().toULong()
                ssrc = buf.getInt().toULong()
                csrcs = (0 until csrcCount).map {
                    buf.getInt().toULong()
                }
                extensions = if (hasExtension) RtpHeaderExtensions.parse(buf) else mapOf()
                this
            }
        }

        fun fromValues(receiver: BitBufferRtpHeader.() -> Unit): BitBufferRtpHeader {
            return BitBufferRtpHeader().apply(receiver)
        }
    }

    override fun clone(): RtpHeader {
        return BitBufferRtpHeader.fromValues {
            version = this@BitBufferRtpHeader.version
            hasPadding = this@BitBufferRtpHeader.hasPadding
            hasExtension = this@BitBufferRtpHeader.hasExtension
            csrcCount = this@BitBufferRtpHeader.csrcCount
            marker = this@BitBufferRtpHeader.marker
            payloadType = this@BitBufferRtpHeader.payloadType
            sequenceNumber = this@BitBufferRtpHeader.sequenceNumber
            timestamp = this@BitBufferRtpHeader.timestamp
            ssrc = this@BitBufferRtpHeader.ssrc
            csrcs = this@BitBufferRtpHeader.csrcs.toList()
            extensions = this@BitBufferRtpHeader.extensions.toMap()
        }
    }
}
