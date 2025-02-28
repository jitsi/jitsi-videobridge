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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings
import org.jitsi.rtp.Packet
import org.jitsi.rtp.extensions.bytearray.hashCodeOfSegment
import org.jitsi.rtp.extensions.bytearray.putShort
import org.jitsi.rtp.rtp.header_extensions.HeaderExtensionHelpers
import org.jitsi.rtp.rtp.header_extensions.HeaderExtensionParser
import org.jitsi.rtp.rtp.header_extensions.OneByteHeaderExtensionParser
import org.jitsi.rtp.rtp.header_extensions.TwoByteHeaderExtensionParser
import org.jitsi.rtp.util.BufferPool
import org.jitsi.rtp.util.RtpUtils
import org.jitsi.rtp.util.getByteAsInt
import org.jitsi.rtp.util.isPadding

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
@SuppressFBWarnings(
    value = ["EI_EXPOSE_REP2"],
    justification = "We intentionally pass a reference to our buffer when using observableWhenChanged."
)
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

    private var hasEncodedExtensions: Boolean
        get() = RtpHeader.hasExtensions(buffer, offset)
        set(value) = RtpHeader.setHasExtensions(buffer, offset, value)

    val csrcCount: Int
        get() = RtpHeader.getCsrcCount(buffer, offset)

    var isMarked: Boolean
        get() = RtpHeader.getMarker(buffer, offset)
        set(value) = RtpHeader.setMarker(buffer, offset, value)

    /* The four values below (payloadType, sequenceNumber, timestamp, and ssrc)
     * are very frequently accessed in our pipeline; store their values in
     * delegated properties, rather than re-reading them from the buffer every time.
     */

    private var _payloadType: Int = RtpHeader.getPayloadType(buffer, offset)
    var payloadType: Int
        get() = _payloadType
        set(newValue) {
            if (newValue != _payloadType) {
                RtpHeader.setPayloadType(this.buffer, this.offset, newValue)
                _payloadType = newValue
            }
        }

    private var _sequenceNumber: Int = RtpHeader.getSequenceNumber(buffer, offset)
    var sequenceNumber: Int
        get() = _sequenceNumber
        set(newValue) {
            if (newValue != _sequenceNumber) {
                RtpHeader.setSequenceNumber(this.buffer, this.offset, newValue)
                _sequenceNumber = newValue
            }
        }

    private var _timestamp: Long = RtpHeader.getTimestamp(buffer, offset)
    var timestamp: Long
        get() = _timestamp
        set(newValue) {
            if (newValue != _timestamp) {
                RtpHeader.setTimestamp(this.buffer, this.offset, newValue)
                _timestamp = newValue
            }
        }

    private var _ssrc: Long = RtpHeader.getSsrc(buffer, offset)
    var ssrc: Long
        get() = _ssrc
        set(newValue) {
            if (newValue != _ssrc) {
                RtpHeader.setSsrc(this.buffer, this.offset, newValue)
                _ssrc = newValue
            }
        }

    val csrcs: List<Long>
        get() = RtpHeader.getCsrcs(buffer, offset)

    val extensionsProfileType: Int
        get() = RtpHeader.getExtensionsProfileType(buffer, offset)

    /**
     * The length of the entire RTP header, including any extensions, in bytes
     */
    var headerLength: Int = RtpHeader.getTotalLength(buffer, offset)
        protected set
    init {
        if (headerLength > length) {
            throw IllegalArgumentException("RTP packet header length $headerLength > length $length")
        }
    }

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
            if (value > 0) {
                hasPadding = true
                buffer[offset + length - 1] = value.toByte()
            } else {
                hasPadding = false
            }
        }

    /**
     * The parser to use for header extensions, depending on the type of header extensions in this packet.
     */
    private val headerExtensionParser
        get() = HeaderExtensionHelpers.getHeaderExtensionParser(extensionsProfileType)

    @field:Suppress("ktlint:standard:backing-property-naming")
    private val _encodedHeaderExtensions: EncodedHeaderExtensions = EncodedHeaderExtensions()
    private val encodedHeaderExtensions: EncodedHeaderExtensions
        get() {
            _encodedHeaderExtensions.reset()
            return _encodedHeaderExtensions
        }

    /**
     * For [RtpPacket] the payload is everything after the RTP Header.
     */
    override val payloadVerification: String
        get() = "type=RtpPacket len=$payloadLength " +
            "hashCode=${buffer.hashCodeOfSegment(payloadOffset, payloadOffset + payloadLength)}"

    private var pendingHeaderExtensions: MutableList<PendingHeaderExtension>? = null

    private fun getEncodedHeaderExtension(extensionId: Int): HeaderExtension? {
        if (!hasEncodedExtensions) return null

        encodedHeaderExtensions.forEach { ext ->
            if (ext.id == extensionId) {
                return ext
            }
        }
        return null
    }

    var hasExtensions: Boolean
        get() = pendingHeaderExtensions?.isNotEmpty() ?: hasEncodedExtensions
        set(value) {
            val p = pendingHeaderExtensions
            if (p != null) {
                if (value && p.isEmpty()) {
                    throw java.lang.IllegalStateException(
                        "Cannot set hasExtensions to true with empty pending extensions"
                    )
                }
                if (!value) {
                    p.clear()
                }
            } else {
                hasEncodedExtensions = value
            }
        }

    fun getHeaderExtension(extensionId: Int): HeaderExtension? {
        val activeHeaderExtensions = pendingHeaderExtensions?.iterator() ?: encodedHeaderExtensions

        activeHeaderExtensions.forEach { ext ->
            if (ext.id == extensionId) {
                return ext
            }
        }
        return null
    }

    private fun createPendingHeaderExtensions(removeIf: ((HeaderExtension) -> Boolean)?) {
        if (pendingHeaderExtensions != null) {
            return
        }
        check(!hasEncodedExtensions || headerExtensionParser != null) {
            "Cannot modify header extensions for header extension type ${Integer.toHexString(extensionsProfileType)}"
        }
        pendingHeaderExtensions = ArrayList<PendingHeaderExtension>().also { l ->
            encodedHeaderExtensions.forEach {
                if (removeIf == null || !removeIf(it)) {
                    l.add(PendingHeaderExtension(it))
                }
            }
        }
    }

    /**
     * Removes the header extension (or all header extensions) with the given ID.
     */
    fun removeHeaderExtension(id: Int) {
        pendingHeaderExtensions?.removeIf { h -> h.id == id }
            ?: createPendingHeaderExtensions { h -> h.id == id }
    }

    /**
     * Removes all header extensions except those with ID values in [retain]
     */
    fun removeHeaderExtensionsExcept(retain: Set<Int>) {
        pendingHeaderExtensions?.removeIf { h -> !retain.contains(h.id) }
            ?: createPendingHeaderExtensions { h -> !retain.contains(h.id) }
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
        require(id in 1..255) {
            "Invalid header extension ID $id"
        }
        require(extDataLength in 0..255) {
            "Invalid header extension length $extDataLength"
        }

        val newHeader = PendingHeaderExtension(id, extDataLength)

        if (pendingHeaderExtensions == null) {
            createPendingHeaderExtensions(null)
        }

        pendingHeaderExtensions!!.add(newHeader)

        return newHeader
    }

    private fun pickParserForEncodedHeaders(
        pendingHeaderExtensions: List<PendingHeaderExtension>
    ): HeaderExtensionParser {
        pendingHeaderExtensions.forEach {
            if (it.id >= 15 || it.dataLengthBytes == 0 || it.dataLengthBytes > 16) {
                return TwoByteHeaderExtensionParser
            }
        }
        return OneByteHeaderExtensionParser
    }

    fun encodeHeaderExtensions() {
        val pendingHeaderExtensions = this.pendingHeaderExtensions ?: return

        val newParser = pickParserForEncodedHeaders(pendingHeaderExtensions)

        // The byte[] of an RtpPacket has the following structure:
        // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
        // | A: unused | B: hdr + ext | C: payload | D: unused |
        // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
        // And the regions have the following sizes:
        // A: this.offset
        // B: this.getHeaderLength()
        // C: this.getPayloadLength()
        // D: this.buffer.length - this.length - this.offset

        // If the newly-encoded header extensions block results in a header
        // longer than the current one,
        // we will try to extend the packet so that it uses A and/or D if
        // possible, in order to avoid allocating new memory.

        // We get this early, before we modify the buffer.
        val currHeaderLength = headerLength
        val currPayloadLength = payloadLength
        val baseHeaderLength = RtpHeader.FIXED_HEADER_SIZE_BYTES + csrcCount * 4

        val newExtHeaderLength = if (pendingHeaderExtensions.isEmpty()) {
            0
        } else {
            val rawHeaderLength = RtpHeader.EXT_HEADER_SIZE_BYTES +
                pendingHeaderExtensions.sumOf { h -> newParser.extHeaderSizeBytes + h.dataLengthBytes }
            rawHeaderLength + RtpUtils.getNumPaddingBytes(rawHeaderLength)
        }

        val newHeaderLength = baseHeaderLength + newExtHeaderLength
        val newPacketLength = newHeaderLength + currPayloadLength

        val newPayloadOffset: Int
        val newBuffer = if (buffer.size >= (newPacketLength + BYTES_TO_LEAVE_AT_END_OF_PACKET)) {
            // We don't need a new buffer
            if ((offset + currHeaderLength) >= (newPacketLength - currPayloadLength)) {
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
            BufferPool.getArray(newPacketLength + BYTES_TO_LEAVE_AT_END_OF_PACKET).apply {
                newPayloadOffset = size - currPayloadLength - BYTES_TO_LEAVE_AT_END_OF_PACKET
                System.arraycopy(buffer, payloadOffset, this, newPayloadOffset, currPayloadLength)
            }
        }
        val newOffset = newPayloadOffset - newHeaderLength

        if (buffer !== newBuffer || offset != newOffset) {
            // Copy the base header into place.
            System.arraycopy(buffer, offset, newBuffer, newOffset, baseHeaderLength)
        }

        if (pendingHeaderExtensions.isNotEmpty()) {
            var off = newOffset + baseHeaderLength
            // Write the header extension
            newBuffer.putShort(off, newParser.headerExtensionLabel.toShort())
            newBuffer.putShort(off + 2, ((newExtHeaderLength - RtpHeader.EXT_HEADER_SIZE_BYTES) / 4).toShort())
            off += 4
            // Write pending header extension elements
            pendingHeaderExtensions.forEach { h ->
                val len = h.writeToBuffer(newBuffer, off, newParser)
                off += len
            }
            // Write padding
            while (off < newOffset + newHeaderLength) {
                newBuffer[off] = 0
                off++
            }
        }

        val oldBuffer = buffer
        buffer = newBuffer
        // Reference comparison to see if we got a new buffer.  If so, return the old one to the pool
        if (oldBuffer !== newBuffer) {
            BufferPool.returnArray(oldBuffer)
        }
        offset = newOffset
        length = newPacketLength
        headerLength = newHeaderLength

        // ... and set the extension bit.
        hasEncodedExtensions = pendingHeaderExtensions.isNotEmpty()

        // Clear pending extensions.
        this.pendingHeaderExtensions = null
    }

    override fun clone(): RtpPacket {
        return RtpPacket(
            cloneBuffer(BYTES_TO_LEAVE_AT_START_OF_PACKET),
            BYTES_TO_LEAVE_AT_START_OF_PACKET,
            length
        ).also { postClone(it) }
    }

    /** Extra operations that need to be done after [clone].  All subclasses overriding [clone]
     * must call this method on the newly-created clone. */
    protected fun postClone(clone: RtpPacket) {
        pendingHeaderExtensions?.let { clone.pendingHeaderExtensions = ArrayList(it) }
    }

    override fun toString(): String = buildString {
        append("${this@RtpPacket::class.java.simpleName}: ")
        append("PT=$payloadType")
        append(", Ssrc=$ssrc")
        append(", SeqNum=$sequenceNumber")
        append(", M=$isMarked")
        append(", X=$hasEncodedExtensions")
        append(", Ts=$timestamp")
    }

    /**
     * Represents an RTP header extension.
     */
    interface HeaderExtension {
        val buffer: ByteArray
        val dataOffset: Int

        var id: Int

        val dataLengthBytes: Int

        val totalLengthBytes: Int

        fun clone(): HeaderExtension = StandaloneHeaderExtension(this)
    }

    @SuppressFBWarnings("CN_IMPLEMENTS_CLONE_BUT_NOT_CLONEABLE")
    class StandaloneHeaderExtension(ext: HeaderExtension) : HeaderExtension {
        override val buffer: ByteArray = ByteArray(ext.dataLengthBytes).also {
            System.arraycopy(ext.buffer, ext.dataOffset, it, 0, ext.dataLengthBytes)
        }
        override val dataOffset = 0
        override var id = ext.id
        override val dataLengthBytes: Int
            get() = buffer.size
        override val totalLengthBytes: Int
            get() = buffer.size
    }

    @SuppressFBWarnings("CN_IMPLEMENTS_CLONE_BUT_NOT_CLONEABLE")
    inner class EncodedHeaderExtension : HeaderExtension {
        private var currExtOffset: Int = 0
        private var currExtLength: Int = 0

        override val dataLengthBytes: Int
            get() = headerExtensionParser!!.getDataLengthBytes(buffer, currExtOffset)

        override val buffer: ByteArray
            get() = this@RtpPacket.buffer

        override var id: Int
            get() {
                if (currExtLength <= 0) {
                    return -1
                }
                return headerExtensionParser!!.getId(buffer, currExtOffset)
            }
            set(newId) {
                if (currExtLength < headerExtensionParser!!.minimumExtSizeBytes) {
                    throw IllegalStateException("Can't set ID: Header extension too short")
                }
                headerExtensionParser!!.writeIdAndLength(newId, dataLengthBytes, buffer, currExtOffset)
            }

        override val dataOffset: Int
            get() = headerExtensionParser!!.extHeaderSizeBytes + currExtOffset

        override val totalLengthBytes: Int
            get() = headerExtensionParser!!.extHeaderSizeBytes + dataLengthBytes

        fun setOffsetLength(nextHeaderExtOffset: Int, nextHeaderExtLength: Int) {
            currExtOffset = nextHeaderExtOffset
            currExtLength = nextHeaderExtLength
        }
    }

    @SuppressFBWarnings(
        value = ["EI_EXPOSE_REP", "CN_IMPLEMENTS_CLONE_BUT_NOT_CLONEABLE"],
        justification = "We intentionally expose the internal buffer."
    )
    inner class PendingHeaderExtension(override var id: Int, override val dataLengthBytes: Int) : HeaderExtension {
        override val buffer = ByteArray(dataLengthBytes)
        override val dataOffset = 0

        override val totalLengthBytes: Int
            get() = dataLengthBytes + headerExtensionParser!!.extHeaderSizeBytes

        constructor(other: HeaderExtension) : this(other.id, other.dataLengthBytes) {
            System.arraycopy(other.buffer, other.dataOffset, buffer, 0, dataLengthBytes)
        }

        fun writeToBuffer(buffer: ByteArray, offset: Int, parser: HeaderExtensionParser): Int {
            parser.writeIdAndLength(id, dataLengthBytes, buffer, offset)
            System.arraycopy(
                this.buffer,
                dataOffset,
                buffer,
                offset + parser.extHeaderSizeBytes,
                dataLengthBytes
            )
            return parser.extHeaderSizeBytes + dataLengthBytes
        }
    }

    inner class EncodedHeaderExtensions : Iterator<HeaderExtension> {
        /**
         * The offset of the next extension
         */
        private var nextOffset = 0

        /**
         * The remaining length of the extensions headers.
         */
        private var remainingLength = 0

        private val currHeaderExtension = EncodedHeaderExtension()

        override fun hasNext(): Boolean {
            if (headerExtensionParser == null) {
                return false
            }
            // Consume any padding
            while (remainingLength > 0 && buffer[nextOffset].isPadding()) {
                nextOffset++
                remainingLength--
            }
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
            headerExtensionParser?.let { parser ->
                if (remainingLength < parser.minimumExtSizeBytes) {
                    return -1
                }
                val extLen = parser.getEntireLengthBytes(buffer, nextOffset)
                return if (extLen > remainingLength) -1 else extLen
            } ?: run { return -1 }
        }

        /**
         * Resets this iterator back to the beginning of the extensions
         */
        internal fun reset() {
            val extLength =
                // This treats unknown header extension types as no extensions, which is what we want.
                if (headerExtensionParser != null) {
                    val extensionBlockLength = HeaderExtensionHelpers.getExtensionsTotalLength(
                        buffer,
                        offset + RtpHeader.FIXED_HEADER_SIZE_BYTES + csrcCount * 4
                    )

                    extensionBlockLength - HeaderExtensionHelpers.TOP_LEVEL_EXT_HEADER_SIZE_BYTES
                } else {
                    0
                }

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
         * How much space to leave in the beginning of new RTP packets. Having space in the beginning allows us to
         * implement adding RTP header extensions efficiently (by keeping the RTP payload in place and shifting the
         * header left).
         */
        const val BYTES_TO_LEAVE_AT_START_OF_PACKET = 10
    }
}
