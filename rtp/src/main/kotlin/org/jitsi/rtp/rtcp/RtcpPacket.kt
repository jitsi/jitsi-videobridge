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

package org.jitsi.rtp.rtcp

import org.jitsi.rtp.Packet
import org.jitsi.rtp.extensions.RTCP_PACKET_TYPE_RANGE
import org.jitsi.rtp.extensions.bytearray.toHex
import org.jitsi.rtp.rtcp.rtcpfb.RtcpFbPacket

/**
 * Models the RTCP header as defined in https://tools.ietf.org/html/rfc3550#section-6.1
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |V=2|P|    RC   |   PT=SR=200   |             length            |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                         SSRC of sender                        |
 * +=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+
 *
 * length: 16 bits
 *   The length of this RTCP packet in 32-bit words minus one,
 *   including the header and any padding.  (The offset of one makes
 *   zero a valid length and avoids a possible infinite loop in
 *   scanning a compound RTCP packet, while counting 32-bit words
 *   avoids a validity check for a multiple of 4.)
 */
abstract class RtcpPacket(
    buffer: ByteArray,
    offset: Int,
    length: Int
) : Packet(buffer, offset, length) {

    var version: Int
        get() = RtcpHeader.getVersion(buffer, offset)
        set(value) = RtcpHeader.setVersion(buffer, offset, value)

    val hasPadding: Boolean
        get() = RtcpHeader.hasPadding(buffer, offset)

    val reportCount: Int
        get() = RtcpHeader.getReportCount(buffer, offset)

    val packetType: Int
        get() = RtcpHeader.getPacketType(buffer, offset)

    val lengthField: Int
        get() = RtcpHeader.getLength(buffer, offset)

    var senderSsrc: Long
        get() = RtcpHeader.getSenderSsrc(buffer, offset)
        set(value) = RtcpHeader.setSenderSsrc(buffer, offset, value)

    val packetLength: Int = (lengthField + 1) * 4

    /**
     * Effectively disable the payload verification for RTCP packets, since in practice we change them very often
     */
    override val payloadVerification = "rtcp"

    abstract override fun clone(): RtcpPacket

    companion object {
        fun parse(buf: ByteArray, offset: Int, bytesRemaining: Int): RtcpPacket {
            val packetType = RtcpHeader.getPacketType(buf, offset)
            val packetLengthBytes = (RtcpHeader.getLength(buf, offset) + 1) * 4
            if (packetLengthBytes > bytesRemaining) {
                throw InvalidRtcpException(buf, offset, "length $packetLengthBytes > available $bytesRemaining")
            }
            return when (packetType) {
                RtcpByePacket.PT -> RtcpByePacket(buf, offset, packetLengthBytes)
                RtcpRrPacket.PT -> RtcpRrPacket(buf, offset, packetLengthBytes)
                RtcpSrPacket.PT -> RtcpSrPacket(buf, offset, packetLengthBytes)
                RtcpSdesPacket.PT -> RtcpSdesPacket(buf, offset, packetLengthBytes)
                in RtcpFbPacket.PACKET_TYPES -> RtcpFbPacket.parse(buf, offset, packetLengthBytes)
                RtcpXrPacket.PT -> RtcpXrPacket(buf, offset, packetLengthBytes)
                else -> {
                    return when (packetType) {
                        in RTCP_PACKET_TYPE_RANGE -> UnsupportedRtcpPacket(buf, offset, packetLengthBytes)
                        else -> throw InvalidRtcpException(buf, offset, "type $packetType")
                    }
                }
            }
        }
    }
}

class InvalidRtcpException(buf: ByteArray, offset: Int, val reason: String) :
    Exception("Invalid RTCP packet: $reason: ${buf.toHex(offset, RtcpHeader.SIZE_BYTES)}")
