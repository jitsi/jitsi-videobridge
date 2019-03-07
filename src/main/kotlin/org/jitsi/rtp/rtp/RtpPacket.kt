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

import org.jitsi.rtp.extensions.clone
import org.jitsi.rtp.extensions.subBuffer
import org.jitsi.rtp.Packet
import org.jitsi.rtp.extensions.put
import org.jitsi.rtp.extensions.unsigned.toPositiveInt
import org.jitsi.rtp.util.ByteBufferUtils
import java.nio.ByteBuffer

/**
 * An [RtpPacket] will always be backed by a backing buffer (a buffer dedicated
 * to this instance).
 * [backingBuffer] MUST be the buffer from which this packet was parsed: it is
 * at least expected that the payload of the RTP packet lives in the range
 * of backingBuffer[header.sizeBytes] to backingBuffer[header.sizeBytes + payloadLength]
 */
//TODO: backing buffer's limit should be set correctly, then there is no
// need for payloadLength
open class RtpPacket(
    val header: RtpHeader = RtpHeader(),
    private var backingBuffer: ByteBuffer = ByteBuffer.allocate(1500)
) : Packet() {
    protected var payloadLength: Int = backingBuffer.limit() - header.sizeBytes
        private set
    private var payloadOffset: Int = header.sizeBytes
    private var lastKnownHeaderSizeBytes: Int = header.sizeBytes

    private val mutablePayload
        get() = backingBuffer.subBuffer(payloadOffset, payloadLength)

    val payload: ByteBuffer get() = mutablePayload.asReadOnlyBuffer()

    override val sizeBytes: Int
        get() = header.sizeBytes + payloadLength

    protected fun addToPayload(data: ByteBuffer) {
        if (backingBuffer.capacity() > backingBuffer.limit() + data.limit()) {
            val currentEndOfPayload = backingBuffer.limit()
            backingBuffer.limit(backingBuffer.limit() + data.limit())
            backingBuffer.put(currentEndOfPayload, data)
            payloadLength += data.limit()
        }
        else {
            // we want to try and avoid this ever happening, so throw for now
            throw Exception("Buffer too small! Buf capacity ${backingBuffer.capacity()}, needed ${backingBuffer.limit() + data.limit()}")
        }
    }

    protected fun shrinkPayload(numBytesToRemoveFromEnd: Int) {
        backingBuffer.limit(backingBuffer.limit() - numBytesToRemoveFromEnd)
        // We don't need to mark dirty here
    }

    protected fun cloneBackingBuffer(): ByteBuffer = backingBuffer.clone()

    val paddingSize: Int
        get() {
            return if (header.hasPadding) {
                // The last octet of the padding contains a count of how many
                // padding octets should be ignored, including itself.

                // It's an 8-bit unsigned number.
                payload.get(payload.limit() - 1).toPositiveInt()
            } else {
                0
            }
        }

    @Suppress("UNCHECKED_CAST")
    fun <OtherType : RtpPacket>toOtherRtpPacketType(
        factory: (RtpHeader, ByteBuffer) -> RtpPacket
    ): OtherType = factory(header, backingBuffer) as OtherType

    override fun clone(): RtpPacket {
        return RtpPacket(header.clone(), backingBuffer.clone())
    }

    final override fun getBuffer(): ByteBuffer {
        //TODO; how to reset this?  we could do it from here, but really
        // it shouldn't be the header managing this at all, since it's only
        // from our perspective that we care about changes (changes since
        // the PACKET last serialized).
        if (header.dirty) {
            if (lastKnownHeaderSizeBytes != header.sizeBytes) {
                // The header has changed in size, we need to move the payload
                backingBuffer.limit(header.sizeBytes + payload.limit())
                backingBuffer.put(header.sizeBytes, payload)
                payloadOffset = header.sizeBytes
                lastKnownHeaderSizeBytes = header.sizeBytes
            }
            backingBuffer.position(0)
            header.serializeTo(backingBuffer)
        }
        return backingBuffer.duplicate()
    }

    final override fun serializeTo(buf: ByteBuffer) {
        header.serializeTo(buf)
        buf.put(payload)
    }

    companion object {
        fun fromBuffer(buf: ByteBuffer): RtpPacket {
            val header = RtpHeader.fromBuffer(buf)
            // We subtract 1 here because the buffer starts at position 0 and
            // 'limit' gives us a size (not a 0 based index of the last position)
            val payloadLength = buf.limit() - header.sizeBytes - 1
            return RtpPacket(header, buf)
        }
    }
}