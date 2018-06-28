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

internal class BitBufferRtpHeader(private val buf: ByteBuffer) : RtpHeader() {
    private val bitBuffer = BitBuffer(buf)
    override var version: Int = bitBuffer.getBits(2).toUInt()
    override var hasPadding: Boolean = bitBuffer.getBitAsBoolean()
    override var hasExtension: Boolean = bitBuffer.getBitAsBoolean()
    override var csrcCount: Int = bitBuffer.getBits(4).toUInt()
    override var marker: Boolean = bitBuffer.getBitAsBoolean()
    override var payloadType: Int = bitBuffer.getBits(7).toUInt()
    override var sequenceNumber: Int = buf.getShort().toUInt()
    override var timestamp: Long = buf.getInt().toULong()
    override var ssrc: Long = buf.getInt().toULong()
    override var csrcs: List<Long> = listOf()
    override var extensions: Map<Int, RtpHeaderExtension> = mapOf()

    init {
        csrcs = (0 until csrcCount).map {
            buf.getInt().toLong()
        }
        extensions = if (hasExtension) RtpHeaderExtensions.parse(buf) else mapOf()
    }

    override fun clone(): RtpHeader {
        val clone = BitBufferRtpHeader(buf.duplicate().rewind() as ByteBuffer)
        // The above creation will have the clone read all values from the buffer,
        // so we need to apply any overrides
        clone.version = version
        clone.hasPadding = hasPadding
        clone.hasExtension = hasExtension
        clone.csrcCount = csrcCount
        clone.marker = marker
        clone.payloadType = payloadType
        clone.sequenceNumber = sequenceNumber
        clone.timestamp = timestamp
        clone.ssrc = ssrc
        clone.csrcs = csrcs.toList()
        clone.extensions = extensions.toMap()

        return clone
    }
}
