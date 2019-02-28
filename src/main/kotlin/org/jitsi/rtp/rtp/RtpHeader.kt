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

import org.jitsi.rtp.extensions.getBitAsBool
import org.jitsi.rtp.extensions.getBits
import org.jitsi.rtp.extensions.putBitAsBoolean
import org.jitsi.rtp.extensions.putBits
import org.jitsi.rtp.extensions.subBuffer
import org.jitsi.rtp.extensions.unsigned.toPositiveInt
import org.jitsi.rtp.extensions.unsigned.toPositiveLong
import org.jitsi.rtp.Serializable
import org.jitsi.rtp.SerializedField
import org.jitsi.rtp.rtp.header_extensions.RtpHeaderExtension
import org.jitsi.rtp.rtp.header_extensions.RtpHeaderExtensions
import java.nio.ByteBuffer

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
 */
class RtpHeader(
    version: Int = 2,
    hasPadding: Boolean = false,
    marker: Boolean = false,
    payloadType: Int = -1,
    sequenceNumber: Int = -1,
    timestamp: Long = -1,
    ssrc: Long = -1,
    csrcs: List<Long> = listOf(),
    private val extensions: RtpHeaderExtensions = RtpHeaderExtensions.NO_EXTENSIONS
) : Serializable(), Cloneable {
    override val sizeBytes: Int
        get() = FIXED_HEADER_SIZE_BYTES +
                (csrcCount * CSRC_SIZE_BYTES) +
                extensions.sizeBytes

    var dirty: Boolean = true
        private set

    var version: Int by SerializedField(version, ::dirty)
    var hasPadding: Boolean by SerializedField(hasPadding, ::dirty)
    val hasExtension: Boolean
        get() = extensions.isNotEmpty()
    val csrcCount: Int
        get() = csrcs.size
    var marker: Boolean by SerializedField(marker, ::dirty)
    var payloadType: Int by SerializedField(payloadType, ::dirty)
    var sequenceNumber: Int by SerializedField(sequenceNumber, ::dirty)
    var timestamp: Long by SerializedField(timestamp, ::dirty)
    var ssrc: Long by SerializedField(ssrc, ::dirty)

    private var _csrcs: MutableList<Long> = csrcs.toMutableList()
    val csrcs: List<Long>
        get() = _csrcs
    fun modifyCsrcs(block: MutableList<Long>.() -> Unit) {
        with (_csrcs) {
            block()
        }
        dirty = true
    }

    fun getExtension(id: Int): RtpHeaderExtension? = extensions.getExtension(id)
    fun addExtension(id: Int, extension: RtpHeaderExtension) {
        extensions.addExtension(id, extension)
        dirty = true
    }

    override fun serializeTo(buf: ByteBuffer) {
        setVersion(buf, version)
        setPadding(buf, hasPadding)
        setExtension(buf, hasExtension)
        setCsrcCount(buf, csrcCount)
        setMarker(buf, marker)
        setPayloadType(buf, payloadType)
        setSequenceNumber(buf, sequenceNumber)
        setTimestamp(buf, timestamp)
        setSsrc(buf, ssrc)
        setCsrcs(buf, csrcs)
        buf.position(getExtensionsHeaderOffset(csrcCount))
        if (hasExtension) {
            // Write the generic extension header (the cookie and the length)
            setExtensions(buf, extensions)
        }
    }

    public override fun clone(): RtpHeader {
        return RtpHeader(
            version,
            hasPadding,
            marker,
            payloadType,
            sequenceNumber,
            timestamp,
            ssrc,
            csrcs,
            extensions.clone()
        )
    }

    companion object {
        const val FIXED_HEADER_SIZE_BYTES = 12
        const val CSRC_SIZE_BYTES = 4

        fun fromBuffer(buf: ByteBuffer): RtpHeader {
            val version = getVersion(buf)
            val hasPadding = hasPadding(buf)
            val hasExtension = getExtension(buf)
            val csrcCount = getCsrcCount(buf)
            val marker = getMarker(buf)
            val payloadType = getPayloadType(buf)
            val sequenceNumber = getSequenceNumber(buf)
            val timestamp = getTimestamp(buf)
            val ssrc = getSsrc(buf)
            val csrcs = getCsrcs(buf, csrcCount)

            val extensions = if (hasExtension) {
                getExtensions(buf.subBuffer(getExtensionsHeaderOffset(csrcCount)))
            } else {
                RtpHeaderExtensions.NO_EXTENSIONS
            }
            return RtpHeader(
                version, hasPadding, marker, payloadType, sequenceNumber,
                timestamp, ssrc, csrcs, extensions)
        }

        fun getVersion(buf: ByteBuffer): Int = buf.get(0).getBits(0, 2).toPositiveInt()
        fun setVersion(buf: ByteBuffer, version: Int) = buf.putBits(0, 0, version.toByte(), 2)

        fun hasPadding(buf: ByteBuffer): Boolean = buf.get(0).getBitAsBool(2)
        fun setPadding(buf: ByteBuffer, hasPadding: Boolean) = buf.putBitAsBoolean(0, 2, hasPadding)

        fun getExtension(buf: ByteBuffer): Boolean = buf.get(0).getBitAsBool(3)
        fun setExtension(buf: ByteBuffer, hasExtension: Boolean) = buf.putBitAsBoolean(0, 3, hasExtension)

        fun getCsrcCount(buf: ByteBuffer): Int = buf.get(0).getBits(4, 4).toPositiveInt()
        fun setCsrcCount(buf: ByteBuffer, csrcCount: Int) {
            buf.putBits(0, 4, csrcCount.toByte(), 4)
        }

        fun getMarker(buf: ByteBuffer): Boolean = buf.get(1).getBitAsBool(0)
        fun setMarker(buf: ByteBuffer, isSet: Boolean) {
            buf.putBitAsBoolean(1, 0, isSet)
        }

        fun getPayloadType(buf: ByteBuffer): Int = buf.get(1).getBits(1, 7).toPositiveInt()
        fun setPayloadType(buf: ByteBuffer, payloadType: Int) {
            buf.putBits(1, 1, payloadType.toByte(), 7)
        }

        fun getSequenceNumber(buf: ByteBuffer): Int = buf.getShort(2).toPositiveInt()
        fun setSequenceNumber(buf: ByteBuffer, sequenceNumber: Int) {
            buf.putShort(2, sequenceNumber.toShort())
        }

        fun getTimestamp(buf: ByteBuffer): Long = buf.getInt(4).toPositiveLong()
        fun setTimestamp(buf: ByteBuffer, timestamp: Long) {
            buf.putInt(4, timestamp.toInt())
        }

        fun getSsrc(buf: ByteBuffer): Long = buf.getInt(8).toPositiveLong()
        fun setSsrc(buf: ByteBuffer, ssrc: Long) {
            buf.putInt(8, ssrc.toInt())
        }

        fun getCsrcs(buf: ByteBuffer, csrcCount: Int): MutableList<Long> {
            return (0 until csrcCount).map {
                buf.getInt(12 + (it * CSRC_SIZE_BYTES)).toPositiveLong()
            }.toMutableList()
        }
        fun setCsrcs(buf: ByteBuffer, csrcs: List<Long>) {
            csrcs.forEachIndexed { index, csrc ->
                buf.putInt(12 + (index * CSRC_SIZE_BYTES), csrc.toInt())
            }
        }

        /**
         * The offset at which the generic extension header should be placed
         */
        fun getExtensionsHeaderOffset(csrcCount: Int): Int = FIXED_HEADER_SIZE_BYTES + (csrcCount * CSRC_SIZE_BYTES)

        /**
         * Note that the buffer passed to these two methods, unlike in most other helpers, must already
         * begin at the start of the extensions portion of the header.  This method also
         * assumes that the caller has already verified that there *are* extensions present
         * (i.e. the extension bit is set) in the case of 'getExtensions' or that there is space
         * for the extensions in the passed buffer (in the case of 'setExtensionsAndPadding')
         */
        /**
         * The buffer passed here should point to the start of the generic extension header
         */
        fun getExtensions(extensionsBuf: ByteBuffer): RtpHeaderExtensions = RtpHeaderExtensions.fromBuffer(extensionsBuf)

        /**
         * [buf] should point to the start of the generic extension header
         */
        fun setExtensions(buf: ByteBuffer, extensions: RtpHeaderExtensions) {
            extensions.serializeTo(buf)
        }
    }
}

