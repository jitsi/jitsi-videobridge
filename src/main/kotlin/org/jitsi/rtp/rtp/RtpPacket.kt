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
import org.jitsi.rtp.util.ByteBufferUtils
import java.nio.ByteBuffer

open class RtpPacket(
    val header: RtpHeader = RtpHeader(),
    private var _payload: ByteBuffer = ByteBufferUtils.EMPTY_BUFFER,
    private var backingBuffer: ByteBuffer? = null
) : Packet() {
    val payload: ByteBuffer get() = _payload.duplicate().asReadOnlyBuffer()

    override val sizeBytes: Int
        get() = header.sizeBytes + _payload.limit()

    private var dirty: Boolean = true

    fun modifyPayload(block: ByteBuffer.() -> Unit) {
        with (_payload) {
            block()
        }
        _payload.rewind()
        dirty = true
    }

    /**
     * Since the mutable [_payload] is not exposed to subclasses, this method
     * allows them to ensure the buffer is large enough for the requested
     * capacity (used to make sure we have enough room to add the auth tag).
     * TODO: should we just override sizeBytes and serializeTo in SrtpPacket
     * instead?
     */
    protected fun growPayloadIfNeeded(requiredCapacity: Int) {
        _payload = ByteBufferUtils.growIfNeeded(_payload, requiredCapacity)
    }

    protected fun cloneMutablePayload(): ByteBuffer = _payload.clone()

    val paddingSize: Int
        get() {
            return if (header.hasPadding) {
                // The last octet of the padding contains a count of how many
                // padding octets should be ignored, including itself.

                // It's an 8-bit unsigned number.
                payload.get(payload.limit() - 1).toInt()
            } else {
                0
            }
        }

    @Suppress("UNCHECKED_CAST")
    fun <OtherType : RtpPacket>toOtherRtpPacketType(factory: (RtpHeader, ByteBuffer, ByteBuffer?) -> RtpPacket): OtherType =
        factory(header, _payload, backingBuffer) as OtherType

    override fun clone(): Packet {
        return RtpPacket(header.clone(), _payload.clone())
    }

    final override fun getBuffer(): ByteBuffer {
        if (dirty || header.dirty) {
            val buf = ByteBufferUtils.ensureCapacity(backingBuffer, sizeBytes)
            serializeTo(buf)

            buf.rewind()
            backingBuffer = buf
            dirty = false
        }
        return backingBuffer!!
    }

    override fun serializeTo(buf: ByteBuffer) {
        header.serializeTo(buf)
        _payload.rewind()
        buf.put(_payload)
    }

    companion object {
        fun fromBuffer(buf: ByteBuffer): RtpPacket {
            val header = RtpHeader.fromBuffer(buf)
            val payload = buf.subBuffer(header.sizeBytes)
            return RtpPacket(header, payload, buf)
        }
    }
}