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

import org.jitsi.rtp.extensions.unsigned.toPositiveInt
import org.jitsi.rtp.rtcp.RtcpHeader
import org.jitsi.rtp.rtp.RtpHeader
import java.util.function.Predicate

//TODO move
typealias PacketPredicate = Predicate<Packet>

abstract class Packet(
    buffer: ByteArray,
    offset: Int,
    length: Int
) : ByteArrayBuffer(buffer, offset, length), Cloneable {

    fun<OtherType : Packet> toOtherType(otherTypeCreator: (ByteArray, Int, Int) -> OtherType): OtherType =
        otherTypeCreator(buffer, offset, length);

    public abstract override fun clone(): Packet

    private fun getPacketType(buf: ByteArray, offset: Int): Int = buf.get(offset + 1).toPositiveInt()
    private val rtcpPacketTypeRange = 200..211

    fun looksLikeRtp(): Boolean {
        if (length < RtpHeader.FIXED_HEADER_SIZE_BYTES) {
            return false
        }
        return getPacketType(buffer, offset) !in rtcpPacketTypeRange
    }

    fun looksLikeRtcp(): Boolean {
        if (length < RtcpHeader.SIZE_BYTES) {
            return false
        }
        return getPacketType(buffer, offset) in rtcpPacketTypeRange
    }
}