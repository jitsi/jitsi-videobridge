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
import org.jitsi.rtp.util.BufferView
import java.nio.ByteBuffer

// https://tools.ietf.org/html/rfc3550#section-5.1
// 0                   1                   2                   3
// 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
// +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
// |V=2|P|X|  CC   |M|     PT      |       sequence number         |
// +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
// |                           timestamp                           |
// +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
// |           synchronization source (SSRC) identifier            |
// +=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+
// |            contributing source (CSRC) identifiers             |
// |                             ....                              |
// +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
abstract class RtpHeader {
    abstract var buffer: BufferView
    abstract var version: Int
    abstract var hasPadding: Boolean
    abstract var hasExtension: Boolean
    abstract var csrcCount: Int
    abstract var marker: Boolean
    abstract var payloadType: Int
    abstract var sequenceNumber: Int
    abstract var timestamp: Long
    abstract var ssrc: Long
    abstract var csrcs: List<Long>
    abstract var extensions: MutableMap<Int, RtpHeaderExtension>
    val size: Int
        get() {
            var size = RtpHeader.FIXED_SIZE_BYTES + (csrcCount * RtpHeader.CSRC_SIZE_BYTES)
            if (hasExtension) {
                size += RtpHeader.EXTENSION_HEADER_SIZE_BYTES
                size += extensions.values.map(RtpHeaderExtension::size).sum()
                // The extensions must be word-aligned, account for any padding
                // here
                if (size % 4 != 0) {
                    size += (4 - (size % 4))
                }
            }
            return size
        }

    companion object {
        const val FIXED_SIZE_BYTES = 12
        const val EXTENSION_HEADER_SIZE_BYTES = 4
        const val CSRC_SIZE_BYTES = 4
        fun fromBuffer(buf: ByteBuffer) = BitBufferRtpHeader.fromBuffer(buf)
        fun fromValues(receiver: RtpHeader.() -> Unit): RtpHeader = BitBufferRtpHeader.fromValues(receiver)
    }

    fun getExtension(id: Int): RtpHeaderExtension? = extensions.getOrDefault(id, null)

    abstract fun clone(): RtpHeader

    fun serializeToBuffer(buf: ByteBuffer) {
        with (BitBuffer(buf)) {
            putBits(version.toByte(), 2)
            // TODO how should hasPadding be set?  can we set it automatically?
            // (not here since we don't have access to the payload) could RtpPacket
            // do it?
            putBoolean(hasPadding)
            // TODO should we make this automatic based on the value of
            // extensions somehow?
            putBoolean(hasExtension)
            // TODO should this be automatic based on csrcs?
            putBits(csrcCount.toByte(), 4)
            putBoolean(marker)
            putBits(payloadType.toByte(), 7)
            buf.putShort(sequenceNumber.toShort())
            buf.putInt(timestamp.toInt())
            buf.putInt(ssrc.toInt())
            csrcs.forEach {
                buf.putInt(it.toInt())
            }
            extensions.values.forEach { it.serializeToBuffer(buf) }
        }
    }

    override fun toString(): String {
        return with (StringBuffer()) {
            appendln("size: $size")
            appendln("version: $version")
            appendln("hasPadding: $hasPadding")
            appendln("hasExtension: $hasExtension")
            appendln("csrcCount: $csrcCount")
            appendln("marker: $marker")
            appendln("payloadType: $payloadType")
            appendln("sequenceNumber: $sequenceNumber")
            appendln("timestamp: $timestamp")
            appendln("ssrc: $ssrc")
            appendln("csrcs: $csrcs")
            appendln("Extensions: $extensions")
            toString()
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other?.javaClass != javaClass) {
            return false
        }
        other as RtpHeader
        return (size == other.size &&
                version == other.version &&
                hasPadding == other.hasPadding &&
                hasExtension == other.hasExtension &&
                csrcCount == other.csrcCount &&
                marker == other.marker &&
                payloadType == other.payloadType &&
                sequenceNumber == other.sequenceNumber &&
                timestamp == other.timestamp &&
                ssrc == other.ssrc &&
                csrcs.equals(other.csrcs) &&
                extensions.equals(other.extensions))
    }

    override fun hashCode(): Int {
        return size.hashCode() + version.hashCode() + hasPadding.hashCode() +
                hasExtension.hashCode() + csrcCount.hashCode() + marker.hashCode() +
                payloadType.hashCode() + sequenceNumber.hashCode() + timestamp.hashCode() +
                ssrc.hashCode() + csrcs.hashCode() + extensions.hashCode()
    }
}
