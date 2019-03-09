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

package org.jitsi.rtp.srtcp

import org.jitsi.rtp.extensions.decreaseLimitBy
import org.jitsi.rtp.extensions.increaseLimitBy
import org.jitsi.rtp.extensions.incrementPosition
import org.jitsi.rtp.extensions.put
import org.jitsi.rtp.extensions.subBuffer
import org.jitsi.rtp.rtcp.RtcpHeader
import org.jitsi.rtp.rtcp.RtcpPacket
import java.nio.ByteBuffer

/**
 * An SRTCP packet without the SRTCP index or
 * the auth tag at the end of the payload.
 */
class UnauthenticatedSrtcpPacket(
    header: RtcpHeader = RtcpHeader(),
    backingBuffer: ByteBuffer
) : RtcpPacket(header, backingBuffer) {

    override val payloadDataSize: Int = backingBuffer.limit() - header.sizeBytes

    /**
     * We still trust the original header values for lengthValue
     * and hasPadding here, since this may be a compound packet
     * and those values should only reflect the data in the
     * first RTCP packet of the entire buffer held here.
     */
    override val lengthValue: Int = header.length
    override val hasPadding: Boolean = header.hasPadding

    override fun serializePayloadDataInto(backingBuffer: ByteBuffer) {
        backingBuffer.incrementPosition(payloadDataSize)
    }

    override fun clone(): UnauthenticatedSrtcpPacket = UnauthenticatedSrtcpPacket(header.clone(), cloneBackingBuffer())

    /**
     * Returns an [AuthenticatedSrtcpPacket] instance whose payload contains the given SRTCP index
     * and auth tag.  After this call this instance should be considered invalidated.
     * TODO: is the caller taking care of the encrypted flag as part of the index?
     */
    fun addIndexAndAuthTag(srtcpIndex: Int, authTag: ByteBuffer): AuthenticatedSrtcpPacket {
        return toOtherRtcpPacketType { rtcpHeader, backingBuffer ->
            backingBuffer!!
            val oldEndOfpayload = backingBuffer.limit()
            backingBuffer.increaseLimitBy(4 + authTag.limit())
            backingBuffer.putInt(oldEndOfpayload, srtcpIndex)
            backingBuffer.put(oldEndOfpayload + 4, authTag)
            AuthenticatedSrtcpPacket(rtcpHeader, backingBuffer)
        }
    }

    companion object {
        fun create(buf: ByteBuffer): UnauthenticatedSrtcpPacket {
            val header = RtcpHeader.fromBuffer(buf)

            return UnauthenticatedSrtcpPacket(header, buf.duplicate().rewind() as ByteBuffer)
        }
    }
}

/**
 * An SRTCP packet which has an SRTCP index and auth tag present
 */
class AuthenticatedSrtcpPacket(
    header: RtcpHeader = RtcpHeader(),
    backingBuffer: ByteBuffer
) : RtcpPacket(header, backingBuffer) {

    override val payloadDataSize: Int = backingBuffer.limit() - header.sizeBytes

    /**
     * We trust the original header values for lengthValue
     * and hasPadding here, since those fields should not
     * reflect the srtcp index and auth tag
     */
    override val lengthValue: Int = header.length
    override val hasPadding: Boolean = header.hasPadding

    fun isEncrypted(tagLen: Int): Boolean =
        (payload.getInt(payload.limit() - (4 + tagLen)) and IS_ENCRYPTED_MASK) == IS_ENCRYPTED_MASK

    override fun serializePayloadDataInto(backingBuffer: ByteBuffer) {
        backingBuffer.incrementPosition(payloadDataSize)
    }

    override fun clone(): AuthenticatedSrtcpPacket = AuthenticatedSrtcpPacket(header.clone(), cloneBackingBuffer())

    /**
     * Gets the SRTCP index and auth tag, as well as an [UnauthenticatedSrtcpPacket] instance
     * representing the SRTCP packet without those fields.  After this call this instance should
     * be considered invalidated.
     */
    fun getIndexAndAuthTag(authTagLen: Int): Triple<Int, ByteBuffer, UnauthenticatedSrtcpPacket> {
        val authTag = payload.subBuffer(payload.limit() - authTagLen)
        val srtcpIndex = payload.getInt(payload.limit() - (4 + authTagLen)) and SRTCP_INDEX_MASK
        val unauthenticatedSrtcpPacket = toOtherRtcpPacketType<UnauthenticatedSrtcpPacket> { rtcpHeader, backingBuffer ->
            backingBuffer!!.decreaseLimitBy(authTagLen + 4)
            UnauthenticatedSrtcpPacket(header, backingBuffer)
        }
        return Triple(srtcpIndex, authTag, unauthenticatedSrtcpPacket)
    }

    companion object {
        private const val IS_ENCRYPTED_MASK = 0x80000000.toInt()
        private const val SRTCP_INDEX_MASK = IS_ENCRYPTED_MASK.inv()
        fun create(buf: ByteBuffer): AuthenticatedSrtcpPacket {
            val header = RtcpHeader.fromBuffer(buf)

            return AuthenticatedSrtcpPacket(header, buf.duplicate().rewind() as ByteBuffer)
        }
    }
}