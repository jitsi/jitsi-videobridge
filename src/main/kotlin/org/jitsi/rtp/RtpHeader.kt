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

import java.nio.ByteBuffer

abstract class RtpHeader {
    abstract val version: Int
    abstract val hasPadding: Boolean
    abstract val hasExtension: Boolean
    abstract val csrcCount: Int
    abstract val marker: Boolean
    abstract val payloadType: Int
    abstract val sequenceNumber: Int
    abstract val timestamp: Long
    abstract val ssrc: Long
    abstract val csrcs: List<Long>

    override fun toString(): String {
        return with (StringBuffer()) {
            append("version: $version\n")
            append("hasPadding: $hasPadding\n")
            append("hasExtension: $hasExtension\n")
            append("csrcCount: $csrcCount\n")
            append("marker: $marker\n")
            append("payloadType: $payloadType\n")
            append("sequenceNumber: $sequenceNumber\n")
            append("timestamp: $timestamp\n")
            append("ssrc: $ssrc\n")
            append("csrcs: $csrcs\n")
            this.toString()
        }
    }
}

class AbsoluteIndexRtpHeader(private val buf: ByteBuffer) : RtpHeader() {
    override val version: Int = buf.get(0).getBits(0, 2).toInt()
    override val hasPadding: Boolean = buf.get(0).getBitAsBool(2)
    override val hasExtension: Boolean = buf.get(0).getBitAsBool(3)
    override val csrcCount: Int = buf.get(0).getBits(4, 4).toInt()
    override val marker: Boolean = buf.get(1).getBitAsBool(0)
    override val payloadType: Int = buf.get(1).getBits(1, 7).toInt()
    override val sequenceNumber: Int = buf.getShort(2).toInt()
    override val timestamp: Long = buf.getInt(4).toLong()
    override val ssrc: Long = buf.getInt(8).toLong()
    override val csrcs: List<Long>
    init {
        val csrcStartByteIndex = 12
        val numBytesInInt = 4
        csrcs = (0 until csrcCount).map { csrcIdx ->
            val currCsrcByteIndex = csrcStartByteIndex + (csrcIdx * numBytesInInt)
            buf.getInt(currCsrcByteIndex).toLong()
        }
    }
}

class BitBufferRtpHeader(buf: ByteBuffer) : RtpHeader() {
    private val bitBuffer = BitBuffer(buf.asReadOnlyBuffer())
    override val version = bitBuffer.getBits(2).toInt()
    override val hasPadding = bitBuffer.getBitAsBoolean()
    override val hasExtension = bitBuffer.getBitAsBoolean()
    override val csrcCount = bitBuffer.getBits(4).toInt()
    override val marker = bitBuffer.getBitAsBoolean()
    override val payloadType = bitBuffer.getBits(7).toInt()
    override val sequenceNumber = bitBuffer.getShort().toInt()
    override val timestamp = bitBuffer.getInt().toLong()
    override val ssrc = bitBuffer.getInt().toLong()
    override val csrcs: List<Long>

    init {
        csrcs = (0 until csrcCount).map {
            bitBuffer.getInt().toLong()
        }
    }
}

class FieldRtpHeader(buf: ByteBuffer) : RtpHeader() {
    override val version = VersionField.parseAsInt(buf)
    override val hasPadding = PaddingField.parseAsBoolean(buf)
    override val hasExtension = ExtensionField.parseAsBoolean(buf)
    override val csrcCount = CsrcCountField.parseAsInt(buf)
    override val marker = MarkerField.parseAsBoolean(buf)
    override val payloadType = PayloadTypeField.parseAsInt(buf)
    override val sequenceNumber = SequenceNumberField.parseAsInt(buf)
    override val timestamp = TimestampField.parseAsLong(buf)
    override val ssrc = SsrcField.parseAsLong(buf)
    override val csrcs = MultiField(sizeBytes = 4, numFields = csrcCount).parseAsLong(buf)
}
