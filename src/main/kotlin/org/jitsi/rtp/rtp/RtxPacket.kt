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
import org.jitsi.rtp.extensions.unsigned.toPositiveInt
import org.jitsi.rtp.util.ByteBufferUtils
import java.nio.ByteBuffer

/**
 * There are 3 different paths for creating an [RtxPacket]:
 * 1) Encapsulating an existing RTP packet in RTX to be sent
 * out as a retransmission
 * 2) Converting an [RtpPacket] instance which was discovered to
 * actually be an RTX packet.
 * 3) Parsing an incoming RTX packet from the network (such that
 * the original RTP packet can be extracted).
 *
 * An instance of [RtxPacket] will have a header corresponding
 * to the RTX version of this packet, and a payload which contains
 * the original RTP payload (NOT the original sequence number).
 * The original sequence number will be added when [serializeTo]
 * is called.
 */
class RtxPacket internal constructor(
    header: RtpHeader = RtpHeader(),
    payload: ByteBuffer = ByteBufferUtils.EMPTY_BUFFER,
    val originalSequenceNumber: Int = 0,
    backingBuffer: ByteBuffer? = null
) : RtpPacket(header, payload, backingBuffer) {

    override val sizeBytes: Int
        get() = super.sizeBytes + 2

    override fun serializeTo(buf: ByteBuffer) {
        header.serializeTo(buf)
        buf.putShort(originalSequenceNumber.toShort())
        buf.put(payload)
    }

    companion object {
        // Create an RTX packet (to be sent out) from a previously-sent
        // RTP packet.  NOTE: we do NOT want to modify the given RTP
        // packet, as it could be cached and we may want to do other
        // things with it in the future (including, for example, creating
        // another RTX packet from it)
        // TODO(brian): this is a good use case for being able to mark a
        // packet as immutable (at least to throw at runtime)
        fun fromRtpPacket(rtpPacket: RtpPacket): RtxPacket {
            return rtpPacket.toOtherRtpPacketType { rtpHeader, payload, backingBuffer ->
                RtxPacket(rtpHeader.clone(), payload.clone(), rtpPacket.header.sequenceNumber)
            }
        }
        // Convert a packet, currently parsed as an RTP packet, to an RTX packet
        // (i.e., an incoming packet, previously parsed as an RTP packet, has been
        // determined to actually be an RTX packet, so re-parse it as such
        fun parseAsRtx(rtpPacket: RtpPacket): RtxPacket {
            return rtpPacket.toOtherRtpPacketType { rtpHeader, payload, backingBuffer ->
                val originalSequenceNumber = payload.getShort().toPositiveInt()
                RtxPacket(rtpHeader, payload.subBuffer(payload.position()), originalSequenceNumber, backingBuffer)
            }
        }
        // Parse a buffer as an RTX packet
        fun fromBuffer(buf: ByteBuffer): RtxPacket {
            val header = RtpHeader.fromBuffer(buf)
            val originalSequenceNumber = buf.getShort(header.sizeBytes).toPositiveInt()
            val payload = buf.subBuffer(header.sizeBytes + 2)

            return RtxPacket(header, payload, originalSequenceNumber, buf)
        }
    }
}