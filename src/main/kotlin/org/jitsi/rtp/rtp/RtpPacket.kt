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

import org.jitsi.rtp.ByteArrayBuffer
import org.jitsi.rtp.NewRawPacket
import org.jitsi.rtp.rtp.header_extensions.HeaderExtensionHelpers

/**
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
 * |                   payload                                     |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 */
open class RtpPacket(
    buffer: ByteArray,
    offset: Int,
    length: Int
) : NewRawPacket(buffer, offset, length) {

    var version: Int
        get() = RtpHeader.getVersion(buffer, offset)
        set(value) = RtpHeader.setVersion(buffer, offset, value)

    var hasPadding: Boolean
        get() = RtpHeader.hasPadding(buffer, offset)
        set(value) = RtpHeader.setPadding(buffer, offset, value)

    var hasExtensions: Boolean
        get() = RtpHeader.hasExtensions(buffer, offset)
        set(value) = RtpHeader.setHasExtensions(buffer, offset, value)

    val csrcCount: Int
        get() = RtpHeader.getCsrcCount(buffer, offset)

    var isMarked: Boolean
        get() = RtpHeader.getMarker(buffer, offset)
        set(value) = RtpHeader.setMarker(buffer, offset, value)

    var payloadType: Int
        get() = RtpHeader.getPayloadType(buffer, offset)
        set(value) = RtpHeader.setPayloadType(buffer, offset, value)

    var sequenceNumber: Int
        get() = RtpHeader.getSequenceNumber(buffer, offset)
        set(value) = RtpHeader.setSequenceNumber(buffer, offset, value)

    var timestamp: Long
        get() = RtpHeader.getTimestamp(buffer, offset)
        set(value) = RtpHeader.setTimestamp(buffer, offset, value)

    var ssrc: Long
        get() = RtpHeader.getSsrc(buffer, offset)
        set(value) = RtpHeader.setSsrc(buffer, offset, value)

    val csrcs: List<Long>
        get() = RtpHeader.getCsrcs(buffer, offset)

    override fun toString(): String = with (StringBuilder()) {
        append("RtpPacket: ")
        append("PT=$payloadType")
        append(", Ssrc=$ssrc")
        append(", SeqNum=$sequenceNumber")
        append(", M=$isMarked")
        append(", X=$hasExtensions")
        append(", Ts=$timestamp")
        toString()
    }



    /**
     * Represents an RTP header extension with the RFC5285 one-byte header:
     *
     * 0
     * 0 1 2 3 4 5 6 7
     * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     * |  ID   |  len  | data...   |
     * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     *
     * Note that this class requires its offset to be set manually by an outside entity
     *
     */
    inner class HeaderExtension : ByteArrayBuffer(this@RtpPacket.buffer, 0, 0) {
        // TODO: if we can assume the underlying buffer won't change or that changes there invalidate
        // this, then we can make these lazy
        val id: Int
            get() {
                if (length <= 0) {
                    return -1
                }
                return HeaderExtensionHelpers.getId(buffer, offset)
            }

        val dataLengthBytes: Int
            get() = HeaderExtensionHelpers.getDataLengthBytes(buffer, offset)
    }

    inner class HeaderExtensions : Iterator<HeaderExtension> {
        /**
         * The offset of the next extension
         */
        private var nextOffset = 0

        /**
         * The remaining length of the extensions headers.
         */
        private var remainingLength = 0

        private val currHeaderExtension: HeaderExtension by lazy { HeaderExtension() }


        override fun hasNext(): Boolean {
            if (remainingLength <= 0 || nextOffset < 0) {
                return false
            }
            return getNextExtLength() > 0
        }

        override fun next(): HeaderExtension {
            val nextExtLen = getNextExtLength()
            if (nextExtLen <= 0) {
                throw Exception("Invalid extension length.  Did hasNext() return true?")
            }
            currHeaderExtension.setOffsetLength(nextOffset, nextExtLen)
            nextOffset += nextExtLen
            remainingLength -= nextExtLen

            return currHeaderExtension
        }


        /**
         * Return the entire length (including the header), in bytes, of the 'next' RTP extension
         * according to [nextOffset].  If [remainingLength] is less than the minimum size for
         * an extension, or the parsed length is larger than [remainingLength], return -1
         */
        private fun getNextExtLength(): Int {
            if (remainingLength < HeaderExtensionHelpers.MINIMUM_EXT_SIZE_BYTES) {
                return -1
            }
            val extLen = HeaderExtensionHelpers.getEntireLengthBytes(buffer, nextOffset)
            return if (extLen > remainingLength) -1 else extLen
        }

        /**
         * Resets this iterator back to the beginning of the extensions
         */
        private fun reset() {
            val extLength = extensionLength
            if (extLength <= 0) {
                // No extensions
                nextOffset = -1
                remainingLength = -1
            } else {
                nextOffset = offset
                + RtpHeader.FIXED_HEADER_SIZE_BYTES
                + csrcCount * 4
                + RtpHeader.EXT_HEADER_SIZE_BYTES

                remainingLength = extLength
            }
        }
    }
}