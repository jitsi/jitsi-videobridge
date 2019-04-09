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

import org.jitsi.rtp.Packet
import org.jitsi.rtp.extensions.bytearray.putShort
import org.jitsi.rtp.rtp.header_extensions.HeaderExtensionHelpers
import org.jitsi.rtp.util.BufferPool
import org.jitsi.rtp.util.getByteAsInt
import kotlin.experimental.or

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
) : Packet(buffer, offset, length) {

    constructor(buffer: ByteArray) : this(buffer, 0, buffer.size)

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

    /**
     * The length of the entire RTP header, including any extensions, in bytes
     */
    var headerLength: Int = RtpHeader.getTotalLength(buffer, offset)
        protected set

    val payloadLength: Int
        get() = length - headerLength

    val payloadOffset: Int
        get() = offset + headerLength

    var paddingSize: Int
        get() {
            if (!hasPadding) {
                return 0
            }
            // The last octet of the padding contains a count of how many
            // padding octets should be ignored, including itself.
            // It's an 8-bit unsigned number.
            return buffer.getByteAsInt(offset + length - 1)
        }
        set(value) {
            hasPadding = true
            buffer[offset + length - 1] = value.toByte()
        }

    private val _headerExtensions: HeaderExtensions = HeaderExtensions()
    val headerExtensions: HeaderExtensions
        get() {
            _headerExtensions.reset()
            return _headerExtensions
        }

    fun getHeaderExtension(extensionId: Int): HeaderExtension? {
        headerExtensions.forEach { ext ->
            if (ext.id == extensionId) {
                return ext
            }
        }
        return null
    }

    /**
     * Adds an RTP header extension with ID [id] and data length [extDataLength] to this
     * packet. The contents of the extension are not set to anything, and the
     * caller of this method is responsible for filling them in via the
     * [HeaderExtension] reference returned.
     *
     * This method MUST NOT be called while iterating over the extensions using
     * {@link #getHeaderExtensions()}, or while manipulating the state of this
     * {@link NewRawPacket}.
     *
     */
    fun addHeaderExtension(id: Int, extDataLength: Int): HeaderExtension {
        if (id !in 1..15 || extDataLength !in 1..16) {
            throw IllegalArgumentException("id=$id len=$extDataLength)")
        }
        // The byte[] of an RtpPacket has the following structure:
        // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
        // | A: unused | B: hdr + ext | C: payload | D: unused |
        // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
        // And the regions have the following sizes:
        // A: this.offset
        // B: this.getHeaderLength()
        // C: this.getPayloadLength()
        // D: this.buffer.length - this.length - this.offset
        // We will try to extend the packet so that it uses A and/or D if
        // possible, in order to avoid allocating new memory.

        // We get this early, before we modify the buffer.
        val currPayloadLength = payloadLength
        val extensionBit = hasExtensions
        val extHeaderOffset = RtpHeader.FIXED_HEADER_SIZE_BYTES + csrcCount * 4

        // This is an upper bound on the required length for the packet after
        // the addition of the new extension. It is easier to calculate than
        // the exact number, and is relatively close (it may be off by a few
        // bytes due to padding)
        val maxRequiredLength = length +
                (if (extensionBit) 0 else RtpHeader.EXT_HEADER_SIZE_BYTES) +
                1 /* the 1-byte header of the extension element */ +
                extDataLength +
                3 /* padding */

        var newPayloadOffset = 0
        val newBuffer = if (buffer.size >= (maxRequiredLength + BYTES_TO_LEAVE_AT_END_OF_PACKET)) {
            // We don't need a new buffer
            if ((offset + headerLength) >= (maxRequiredLength - currPayloadLength)) {
                // Region A (see above) is enough to accommodate the new
                // packet, keep the payload where it is.
                newPayloadOffset = payloadOffset
            } else {
                // We have to use region D, so move the payload all the way to the right
                newPayloadOffset = buffer.size - currPayloadLength - BYTES_TO_LEAVE_AT_END_OF_PACKET
                System.arraycopy(buffer, payloadOffset, buffer, newPayloadOffset, currPayloadLength)
            }
            buffer
        } else {
            // We need a new buffer. We will place the payload almost to the end
            // (leaving room for an SRTP tag)
            BufferPool.getArray(maxRequiredLength + BYTES_TO_LEAVE_AT_END_OF_PACKET).apply {
                newPayloadOffset = size - currPayloadLength - BYTES_TO_LEAVE_AT_END_OF_PACKET
                System.arraycopy(buffer, payloadOffset, this, newPayloadOffset, currPayloadLength)
            }
        }

        // By now we have the payload in a position which leaves enough space
        // for the whole new header.
        // Next, we are going to construct a new header + extensions (including
        // the one we are adding) at offset 0, and once finished, we will move
        // them to the correct offset.

        var newHeaderLength = extHeaderOffset

        // The bytes in the header extensions, excluding the (0xBEDE, length)
        // field and any padding.
        var extensionBytes = 0
        if (hasExtensions) {
            // (0xBEDE, length)
            newHeaderLength += 4

            // We can't find the actual length without an iteration because
            // of padding. It is safe to iterate, because we have not yet
            // modified the header (we only might have moved the offset right)
            headerExtensions.forEach { ext ->
                extensionBytes += ext.totalLengthBytes
            }

            newHeaderLength += extensionBytes
        }

        // Copy the header (and extensions, excluding padding, if there are any) to
        // the very beginning of the buffer
        System.arraycopy(buffer, offset,
            newBuffer, 0,
            newHeaderLength)

        if (!hasExtensions) {
            // If the original packet didn't have any extensions, we need to
            // add the extension header (RFC 5285):
            //  0                   1                   2                   3
            //  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
            // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
            // |       0xBE    |    0xDE       |           length              |
            // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
            newBuffer.putShort(extHeaderOffset, 0xBEDE.toShort())
            // We will set the length field later
            newHeaderLength += 4
        }

        // Finally we get to add our extension
        newBuffer[newHeaderLength++] = ((id and 0x0F) shl 4).toByte() or ((extDataLength - 1) and 0x0F).toByte()
        extensionBytes++

        // This is where the data of the extension that we add begins. We just
        // skip 'len' bytes, and let the caller fill them in. We have to go
        // back one byte, because newHeaderLength already moved.
        val extensionDataOffset = newHeaderLength - 1
        newHeaderLength += extDataLength
        extensionBytes += extDataLength

        val numPaddingBytes = (4 - (extensionBytes % 4)) % 4
        repeat(numPaddingBytes) {
            // Set the padding to 0 (we have to do this because we may be
            // reusing a buffer).
            newBuffer[newHeaderLength++] = 0
        }

        newBuffer.putShort(extHeaderOffset + 2, ((extensionBytes + numPaddingBytes) / 4).toShort())

        // Now we have the new header, with the added header extension and with
        // the correct padding, in newBuffer at offset 0. Lets move it to the
        // correct place (right before the payload).
        val newOffset = newPayloadOffset - newHeaderLength
        if (newOffset != 0) {
            System.arraycopy(newBuffer, 0, newBuffer, newOffset, newHeaderLength)
        }

        // All that is left to do is update the NewRawPacket state.
        val oldBuffer = buffer
        buffer = newBuffer
        // Reference comparison to see if we got a new buffer.  If so, return the old one to the pool
        if (oldBuffer !== newBuffer) {
            BufferPool.returnArray(oldBuffer)
        }
        offset = newOffset
        length = newHeaderLength + currPayloadLength

        // ... and set the extension bit.
        hasExtensions = true

        // Setup the single HeaderExtension instance of this NewRawPacket and
        // return it.
        val newExt = headerExtensions.currHeaderExtension
        newExt.setOffsetLength(offset + extensionDataOffset, extDataLength + 1)

        // Update the header length
        headerLength = RtpHeader.getTotalLength(buffer, offset)

        return newExt
    }

    /**
     * Return the total length of the extensions in this packet, including the extension header
     */
    private val extensionBlockLength: Int
        get() {
            if (!hasExtensions) {
                return 0
            }
            return HeaderExtensionHelpers.getExtensionsTotalLength(
                buffer, offset + RtpHeader.FIXED_HEADER_SIZE_BYTES + csrcCount * 4)
        }

    override fun clone(): RtpPacket = RtpPacket(cloneBuffer(), offset, length)

    override fun toString(): String = with(StringBuilder()) {
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
     * Note that this class requires its offset to be set manually by an outside entity, and that
     * it ALWAYS uses the CURRENT buffer set in [RtpPacket] (though it maintains its own
     * offset and length, which are specific to the current header extension).
     *
     */
    inner class HeaderExtension {
        var currExtOffset: Int = 0
            private set
        var currExtLength: Int = 0
            private set

        val currExtBuffer: ByteArray
            get() = this@RtpPacket.buffer

        fun setOffsetLength(nextHeaderExtOffset: Int, nextHeaderExtLength: Int) {
            currExtOffset = nextHeaderExtOffset
            currExtLength = nextHeaderExtLength
        }

        val id: Int
            get() {
                if (currExtLength <= 0) {
                    return -1
                }
                return HeaderExtensionHelpers.getId(currExtBuffer, currExtOffset)
            }

        val dataLengthBytes: Int
            get() = HeaderExtensionHelpers.getDataLengthBytes(currExtBuffer, currExtOffset)

        val totalLengthBytes: Int
            get() = 1 + dataLengthBytes
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

        val currHeaderExtension: HeaderExtension = HeaderExtension()

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
        internal fun reset() {
            val extLength = extensionBlockLength - HeaderExtensionHelpers.TOP_LEVEL_EXT_HEADER_SIZE_BYTES
            if (extLength <= 0) {
                // No extensions
                nextOffset = -1
                remainingLength = -1
            } else {
                nextOffset = offset +
                        RtpHeader.FIXED_HEADER_SIZE_BYTES +
                        csrcCount * 4 +
                        RtpHeader.EXT_HEADER_SIZE_BYTES

                remainingLength = extLength
            }
        }
    }

    companion object {
        /**
         * The size of the header for individual extensions.  Currently we only
         * support 1 byte header extensions
         */
        const val HEADER_EXT_HEADER_SIZE = 1

        /**
         * In some cases when we add a header extension, it's easier to shift
         * the payload to the end to make room for the (unknown) amount of bytes
         * in the new header extension.  Doing this means we have to grow the
         * buffer every time to add the SRTP auth tag.  This value determines
         * how many bytes away from the end of the buffer we'll shift in that
         * scenario in order to leave room.
         */
        const val BYTES_TO_LEAVE_AT_END_OF_PACKET = 20
    }
}