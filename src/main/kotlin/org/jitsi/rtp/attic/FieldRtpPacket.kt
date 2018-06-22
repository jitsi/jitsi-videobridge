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
package org.jitsi.rtp.attic

import org.jitsi.rtp.getBitAsBool
import org.jitsi.rtp.getBits
import java.nio.ByteBuffer

enum class FieldSizeType {
    BITS,
    BYTES
}

open class Field(val size: Int, val type: FieldSizeType, val bitOffset: Int? = null) {
    fun parseAsInt(buf: ByteBuffer): Int {
        if (type == FieldSizeType.BITS) {
            if (size > 8) {
                TODO()
            }
            if (bitOffset != null && bitOffset + size > 8) {
                TODO()
            }
            val originalBufferPosition = buf.position()
            val bitStartPos = bitOffset ?: 0
            val result = buf.get().getBits(bitStartPos, size).toInt()
            // Only advance the buffer one byte if we ended at the byte boundary
            //println("Starting at position $bitStartPos and read $size bits, ending at bit ${bitStartPos + size - 1}")
            if ((bitStartPos + size) % 8 != 0) {
                //println("Didn't reach end of byte, resetting")
                buf.position(originalBufferPosition)
            }
            return result
        } else {
            return when (size) {
                1 -> buf.get().toInt()
                2 -> buf.getShort().toInt()
                4 -> buf.getInt()
                8 -> buf.getLong().toInt()
                else -> throw UnsupportedOperationException()
            }
        }
    }
    fun parseAsLong(buf: ByteBuffer): Long {
        if (type == FieldSizeType.BITS) {
            if (size > 8) {
                TODO()
            }
            if (bitOffset != null && bitOffset + size > 8) {
                TODO()
            }
            val originalBufferPosition = buf.position()
            val bitStartPos = bitOffset ?: 0
            val result = buf.get().getBits(bitStartPos, size).toLong()
            // Only advance the buffer one byte if we ended at the byte boundary
            //println("Starting at position $bitStartPos and read $size bits, ending at bit ${bitStartPos + size - 1}")
            if ((bitStartPos + size) % 8 != 0) {
                //println("Didn't reach end of byte, resetting")
                buf.position(originalBufferPosition)
            }
            return result
        } else {
            return when (size) {
                1 -> buf.get().toLong()
                2 -> buf.getShort().toLong()
                4 -> buf.getInt().toLong()
                8 -> buf.getLong()
                else -> throw UnsupportedOperationException()
            }
        }

    }
    fun parseAsBoolean(buf: ByteBuffer): Boolean {
        // Size must equal 1, type must equal BITS
        val originalBufferPosition = buf.position()
        val bitStartPos = bitOffset ?: 0
        val result = buf.get().getBitAsBool(bitStartPos)
        // Only advance the buffer one byte if we ended at the byte boundary
        //println("Starting at position $bitStartPos and read $size bits, ending at bit ${bitStartPos + size - 1}")
        if ((bitStartPos + size) % 8 != 0) {
            //println("Didn't reach end of byte, resetting")
            buf.position(originalBufferPosition)
        }
        return result
    }
}

open class MultiField(val sizeBytes: Int, val numFields: Int) {
    fun parseAsLong(buf: ByteBuffer): List<Long> {
        return (0 until numFields).map {
            when (sizeBytes) {
                1 -> buf.get().toLong()
                2 -> buf.getShort().toLong()
                4 -> buf.getInt().toLong()
                8 -> buf.getLong()
                else -> throw UnsupportedOperationException()
            }
        }

    }
}

object VersionField : Field(2, FieldSizeType.BITS)
object PaddingField : Field(1, FieldSizeType.BITS, 2)
object ExtensionField : Field(1, FieldSizeType.BITS, 3)
object CsrcCountField : Field(4, FieldSizeType.BITS, 4)
object MarkerField : Field(1, FieldSizeType.BITS)
object PayloadTypeField : Field(7, FieldSizeType.BITS, 1)
object SequenceNumberField : Field(2, FieldSizeType.BYTES)
object TimestampField : Field(4, FieldSizeType.BYTES)
object SsrcField : Field(4, FieldSizeType.BYTES)
//object CsrcField : MultiField(sizeBytes = 4, numFields = csrcCount

//class FieldRtpPacket(buf: ByteBuffer) : RtpPacket() {
//    override val header: RtpHeader = FieldRtpHeader(buf)
//}

//class FieldRtpHeader(buf: ByteBuffer) : RtpHeader() {
//    override val version = VersionField.parseAsInt(buf)
//    override val hasPadding = PaddingField.parseAsBoolean(buf)
//    override val hasExtension = ExtensionField.parseAsBoolean(buf)
//    override val csrcCount = CsrcCountField.parseAsInt(buf)
//    override val marker = MarkerField.parseAsBoolean(buf)
//    override val payloadType = PayloadTypeField.parseAsInt(buf)
//    override val sequenceNumber = SequenceNumberField.parseAsInt(buf)
//    override val timestamp = TimestampField.parseAsLong(buf)
//    override val ssrc = SsrcField.parseAsLong(buf)
//    override val csrcs = MultiField(sizeBytes = 4, numFields = csrcCount).parseAsLong(buf)
//}
