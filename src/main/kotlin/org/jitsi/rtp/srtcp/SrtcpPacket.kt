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

import org.jitsi.rtp.extensions.clone
import org.jitsi.rtp.extensions.put
import org.jitsi.rtp.extensions.subBuffer
import org.jitsi.rtp.Packet
import org.jitsi.rtp.rtcp.RtcpHeader
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.rtp.util.ByteBufferUtils
import java.nio.ByteBuffer

class SrtcpPacket(
    header: RtcpHeader = RtcpHeader(),
    private var srtcpPayload: ByteBuffer = ByteBufferUtils.EMPTY_BUFFER,
    backingBuffer: ByteBuffer? = null
) : RtcpPacket(header, backingBuffer) {

    override val sizeBytes: Int
        get() = header.sizeBytes + srtcpPayload.limit()

    fun getAuthTag(tagLen: Int): ByteBuffer =
        srtcpPayload.subBuffer(srtcpPayload.limit() - tagLen)

    fun getSrtcpIndex(tagLen: Int): Int =
        srtcpPayload.getInt(srtcpPayload.limit() - (4 + tagLen)) and SRTCP_INDEX_MASK

    fun isEncrypted(tagLen: Int): Boolean =
        (srtcpPayload.getInt(srtcpPayload.limit() - (4 + tagLen)) and IS_ENCRYPTED_MASK) == IS_ENCRYPTED_MASK

    fun removeAuthTagAndSrtcpIndex(tagLen: Int) {
        srtcpPayload.limit(srtcpPayload.limit() - (4 + tagLen))
        payloadModified()
    }

    // NOTE: both the SRTCP index and the auth tag will be added to
    // the end of the payload, meaning that they must be added in
    // the proper order (SRTCP index first then auth tag)
    // TODO: have one method to add both?
    fun addAuthTag(authTag: ByteBuffer) {
        val buf = ByteBufferUtils.growIfNeeded(srtcpPayload, srtcpPayload.limit() + authTag.limit())
        buf.put(buf.limit() - authTag.limit(), authTag)
        srtcpPayload = buf
        payloadModified()
    }

    fun addSrtcpIndex(srtcpIndex: Int) {
        val buf = ByteBufferUtils.growIfNeeded(srtcpPayload, srtcpPayload.limit() + 4)
        buf.putInt(buf.limit() - 4, srtcpIndex)
        srtcpPayload = buf
        payloadModified()
    }

    override fun clone(): Packet =
        SrtcpPacket(header.clone(), srtcpPayload.clone())

    override fun shouldUpdateHeaderAndAddPadding(): Boolean = false

    override fun serializeTo(buf: ByteBuffer) {
        header.serializeTo(buf)
        srtcpPayload.rewind()
        buf.put(srtcpPayload.duplicate())
    }

    companion object {
        private const val IS_ENCRYPTED_MASK = 0x80000000.toInt()
        private const val SRTCP_INDEX_MASK = IS_ENCRYPTED_MASK.inv()
        fun create(buf: ByteBuffer): SrtcpPacket {
            val header = RtcpHeader.fromBuffer(buf)
            val payload = buf.subBuffer(header.sizeBytes)

            return SrtcpPacket(header, payload, buf)
        }
    }
}