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

package org.jitsi.rtp

import org.jitsi.rtp.extensions.bytearray.hashCodeOfSegment
import org.jitsi.rtp.util.BufferPool

abstract class Packet(
    buffer: ByteArray,
    offset: Int,
    length: Int
) : ByteArrayBufferImpl(buffer, offset, length), Cloneable {

    inline fun <OtherType : Packet> toOtherType(otherTypeCreator: (ByteArray, Int, Int) -> OtherType): OtherType =
        otherTypeCreator(buffer, offset, length)

    public abstract override fun clone(): Packet

    /**
     * A string used to verify the payload of this packet. The same payload must always produce the same  verification
     * string. This is similar to a hashcode of the payload, but in order to provide more debugging information it also
     * includes the length of the payload (and is thus a string).
     *
     * Different subclasses of [Packet] will have different notions of "payload", and they need to
     */
    open val payloadVerification: String
        get() = "len=$length hashCode=${buffer.hashCodeOfSegment(offset, offset + length)}"

    /**
     * Creates a clone of this [Packet]'s buffer. Allocates a new buffer which is large enough to contain the data of
     * the packet plus additional [BYTES_TO_LEAVE_AT_END_OF_PACKET] bytes at the end and optionally
     * [bytesToLeaveAtStart] bytes at the start.
     *
     * @param bytesToLeaveAtStart How many bytes to leave at the start of the buffer. This is effectively the offset of
     * the new packet.
     */
    protected fun cloneBuffer(bytesToLeaveAtStart: Int): ByteArray =
        BufferPool.getArray(bytesToLeaveAtStart + length + BYTES_TO_LEAVE_AT_END_OF_PACKET).apply {
            System.arraycopy(
                buffer, offset,
                this, bytesToLeaveAtStart,
                length
            )
        }

    companion object {
        /**
         * How much space to leave in the end of a new packet. Having space in the buffer after the payload allows us to
         * implement adding the SRT(C)P auth tag efficiently (without having to allocate a new buffer).
         */
        const val BYTES_TO_LEAVE_AT_END_OF_PACKET = 20
    }
}
