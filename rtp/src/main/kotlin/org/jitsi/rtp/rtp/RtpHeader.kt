/*
 * Copyright @ 2018 - present 8x8, Inc.
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

package org.jitsi.rtp.rtp

import org.jitsi.rtp.extensions.bytearray.putInt
import org.jitsi.rtp.extensions.bytearray.putShort
import org.jitsi.rtp.extensions.unsigned.toPositiveInt
import org.jitsi.rtp.rtp.header_extensions.HeaderExtensionHelpers
import org.jitsi.rtp.util.getIntAsLong
import org.jitsi.rtp.util.getShortAsInt
import kotlin.experimental.and
import kotlin.experimental.or

/**
 * [RtpHeader] exists only as a set of helper methods to retrieve and set fields inside of
 * a [ByteArray].
 *
 * It covers the RTP header fields until where the variability starts (the CSRCs list)
 *
 *
 * https://tools.ietf.org/html/rfc3550#section-5.1
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |V=2|P|X|  CC   |M|     PT      |       sequence number         |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                           timestamp                           |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |           synchronization source (SSRC) identifier            |
 * +=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+
 * |            contributing source (CSRC) identifiers             |
 * |                             ....                              |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |              ...extensions (if present)...                    |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *
 * @author Brian Baldino
 */
class RtpHeader {
    companion object {
        const val FIXED_HEADER_SIZE_BYTES = 12
        const val CSRCS_OFFSET = 12
        // The size of the RTP Extension header block
        const val EXT_HEADER_SIZE_BYTES = 4
        const val VERSION = 2

        fun getVersion(buf: ByteArray, baseOffset: Int): Int =
            (buf[baseOffset].toInt() and 0xC0) ushr 6

        fun setVersion(buf: ByteArray, baseOffset: Int, version: Int) {
            buf[baseOffset] = ((buf[baseOffset].toInt() and 0xC0.inv()) or ((version shl 6) and 0xC0)).toByte()
        }

        fun hasPadding(buf: ByteArray, baseOffset: Int): Boolean =
            (buf[baseOffset].toInt() and 0x20) == 0x20
        fun setPadding(buf: ByteArray, baseOffset: Int, hasPadding: Boolean) {
            buf[baseOffset] = when (hasPadding) {
                true -> (buf[baseOffset].toInt() or 0x20).toByte()
                false -> (buf[baseOffset].toInt() and 0x20.inv()).toByte()
            }
        }

        fun hasExtensions(buf: ByteArray, baseOffset: Int): Boolean =
            (buf[baseOffset].toInt() and 0x10) == 0x10
        fun setHasExtensions(buf: ByteArray, baseOffset: Int, hasExtension: Boolean) {
            buf[baseOffset] = when (hasExtension) {
                true -> (buf[baseOffset].toInt() or 0x10).toByte()
                false -> (buf[baseOffset].toInt() and 0x10.inv()).toByte()
            }
        }

        fun getCsrcCount(buf: ByteArray, baseOffset: Int): Int =
            buf[baseOffset].toInt() and 0x0F
        fun setCsrcCount(buf: ByteArray, baseOffset: Int, csrcCount: Int) {
            buf[baseOffset] = ((buf[baseOffset].toInt() and 0xF0) or ((csrcCount and 0x0F))).toByte()
        }

        fun getMarker(buf: ByteArray, baseOffset: Int): Boolean =
            (buf[baseOffset + 1].toInt() and 0x80) == 0x80
        fun setMarker(buf: ByteArray, baseOffset: Int, isSet: Boolean) {
            buf[baseOffset + 1] = when (isSet) {
                true -> (buf[baseOffset + 1].toInt() or 0x80).toByte()
                false -> (buf[baseOffset + 1].toInt() and 0x80.inv()).toByte()
            }
        }

        fun getPayloadType(buf: ByteArray, baseOffset: Int): Int =
            (buf[baseOffset + 1].toInt() and 0x7F).toPositiveInt()
        fun setPayloadType(buf: ByteArray, baseOffset: Int, payloadType: Int) {
            buf[baseOffset + 1] = (buf[baseOffset + 1] and 0x80.toByte()) or (payloadType and 0x7F).toByte()
        }

        fun getSequenceNumber(buf: ByteArray, baseOffset: Int): Int =
            buf.getShortAsInt(baseOffset + 2)
        fun setSequenceNumber(buf: ByteArray, baseOffset: Int, sequenceNumber: Int) {
            buf.putShort(baseOffset + 2, sequenceNumber.toShort())
        }

        fun getTimestamp(buf: ByteArray, baseOffset: Int): Long =
            buf.getIntAsLong(baseOffset + 4)
        fun setTimestamp(buf: ByteArray, baseOffset: Int, timestamp: Long) {
            buf.putInt(baseOffset + 4, timestamp.toInt())
        }

        fun getSsrc(buf: ByteArray, baseOffset: Int): Long =
            buf.getIntAsLong(baseOffset + 8)
        fun setSsrc(buf: ByteArray, baseOffset: Int, ssrc: Long) {
            buf.putInt(baseOffset + 8, ssrc.toInt())
        }

        fun getCsrcs(buf: ByteArray, baseOffset: Int): List<Long> {
            val numCsrcs = getCsrcCount(buf, baseOffset)
            return (0 until numCsrcs).map { buf.getIntAsLong(CSRCS_OFFSET + (4 * it)) }.toList()
        }

        /**
         * Assumes there is already proper room for the CSRCS.  Also updates
         * the CSRC count field.
         */
        fun setCsrcs(buf: ByteArray, baseOffset: Int, csrcs: List<Long>) {
            csrcs.forEachIndexed { index, csrc ->
                buf.putInt(CSRCS_OFFSET + (4 * index), csrc.toInt())
            }
            setCsrcCount(buf, baseOffset, csrcs.size)
        }

        /**
         * The length of the entire RTP header, including any extensions, in bytes
         */
        fun getTotalLength(buf: ByteArray, baseOffset: Int): Int {
            val length =
                FIXED_HEADER_SIZE_BYTES + getCsrcCount(buf, baseOffset) * 4

            val extLength = if (hasExtensions(buf, baseOffset)) {
                // Length points to where the ext header would start
                val extHeaderOffset = length
                HeaderExtensionHelpers.getExtensionsTotalLength(buf, baseOffset + extHeaderOffset)
            } else {
                0
            }
            return length + extLength
        }
    }
}
