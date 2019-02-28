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

package org.jitsi.rtp.srtp

import org.jitsi.rtp.extensions.subBuffer
import org.jitsi.rtp.Packet
import org.jitsi.rtp.rtp.RtpHeader
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.rtp.util.ByteBufferUtils
import java.nio.ByteBuffer

class SrtpPacket(
    header: RtpHeader = RtpHeader(),
    payload: ByteBuffer = ByteBufferUtils.EMPTY_BUFFER,
    backingBuffer: ByteBuffer? = null
) : RtpPacket(header, payload, backingBuffer) {

    fun getAuthTag(tagLen: Int): ByteBuffer =
        payload.subBuffer(payload.limit() - tagLen)

    fun removeAuthTag(tagLen: Int) {
        modifyPayload {
           limit(limit() - tagLen)
        }
    }

    fun addAuthTag(authTag: ByteBuffer) {
        growPayloadIfNeeded(payload.limit() + authTag.limit())
        modifyPayload {
            position(limit() - authTag.limit())
            put(authTag)
        }
    }

    override fun clone(): Packet {
        return SrtpPacket(header.clone(), cloneMutablePayload())
    }

    companion object {
        fun create(buf: ByteBuffer): SrtpPacket {
            val header = RtpHeader.fromBuffer(buf)
            val payload = buf.subBuffer(header.sizeBytes)
            return SrtpPacket(header, payload, buf)
        }
    }
}