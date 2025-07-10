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
@file:Suppress("ktlint:standard:property-naming", "ktlint:standard:enum-entry-name-case")

package org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.ccfb

import org.jitsi.rtp.extensions.bytearray.putInt
import org.jitsi.rtp.extensions.bytearray.putShort
import org.jitsi.rtp.rtcp.RtcpHeaderBuilder
import org.jitsi.rtp.rtcp.rtcpfb.RtcpFbPacket
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.TransportLayerRtcpFbPacket
import org.jitsi.rtp.util.BufferPool
import org.jitsi.rtp.util.RtpUtils
import org.jitsi.rtp.util.getIntAsLong
import org.jitsi.rtp.util.getShortAsInt
import org.jitsi.utils.MAX_DURATION
import org.jitsi.utils.MIN_DURATION
import org.jitsi.utils.div
import org.jitsi.utils.secs
import org.jitsi.utils.toDouble
import java.time.Duration
import kotlin.math.min

/**
 * This class is a port of CongestionControlFeedback in
 * congestion_control_feedback.h/congestion_control_feedback.cc in Chrome
 * https://source.chromium.org/chromium/chromium/src/+/main:third_party/webrtc/modules/rtp_rtcp/source/rtcp_packet/congestion_control_feedback.h;rcl=eda3abcd0f44ed2e4c36586d35bdc06885abd25c
 *
 * Because of this, it explicitly does NOT try to conform
 * to Kotlin style or idioms, instead striving to match the
 * Chrome code as closely as possible in an effort to make
 * future updates easier.
 */

enum class EcnMarking(val bits: Byte) {
    kNotEct(0), // Not ECN-Capable Transport
    kEct1(1), // ECN-Capable Transport
    kEct0(2), // Not used by L4s (or webrtc.)
    kCe(3); // Congestion experienced

    companion object {
        fun fromBits(bits: Byte): EcnMarking {
            entries.forEach {
                if (it.bits == bits) {
                    return it
                }
            }
            throw IllegalArgumentException("Invalid ECNMarking value: $bits")
        }
    }
}

sealed class PacketInfo(
    val ssrc: Long = 0,
    val sequenceNumber: Int = 0
) {
    override fun toString(): String {
        return "ssrc=$ssrc, sequenceNumber=$sequenceNumber"
    }
}

class UnreceivedPacketInfo(ssrc: Long = 0, sequenceNumber: Int = 0) : PacketInfo(ssrc, sequenceNumber) {
    override fun toString(): String {
        return "Unreceived: " + super.toString()
    }
}

class ReceivedPacketInfo(
    ssrc: Long = 0,
    sequenceNumber: Int = 0,
    val arrivalTimeOffset: Duration = MIN_DURATION,
    val ecn: EcnMarking = EcnMarking.kNotEct
) : PacketInfo(ssrc, sequenceNumber) {
    override fun toString(): String {
        return "Received: " + super.toString() + ", arrivalTimeOffset=$arrivalTimeOffset, ecn=$ecn"
    }
}

private const val kHeaderPerMediaSsrcLength = 8
private const val kTimestampLength = 4

class RtcpFbCcfbPacketBuilder(
    val rtcpHeader: RtcpHeaderBuilder = RtcpHeaderBuilder(),
    var mediaSourceSsrc: Long = -1,
    // `Packets` MUST be sorted in sequence_number order per SSRC. There MUST not
    // be missing sequence numbers between `Packets`. `Packets` MUST not include
    // duplicate sequence numbers.
    val packets: List<PacketInfo> = listOf(),
    val reportTimestampCompactNtp: Long
) {
    private val groupedPackets = packets.groupBy { it.ssrc }

    private fun blockLength(): Int {
        var totalSize = RtcpFbPacket.HEADER_SIZE + kTimestampLength

        groupedPackets.forEach { (ssrc, packets) ->
            val blockSize = packets.size * 2
            totalSize += kHeaderPerMediaSsrcLength + blockSize + RtpUtils.getNumPaddingBytes(blockSize)
        }

        return totalSize
    }

    fun writeTo(buf: ByteArray, offset: Int) {
        val blockLength = blockLength()
        assert(blockLength % 4 == 0) // Will never need padding
        val packetSize = blockLength
        require(buf.size - offset >= packetSize) {
            "Buffer of size ${buf.size} with offset $offset too small for CCFB packet of size $packetSize"
        }
        rtcpHeader.apply {
            packetType = TransportLayerRtcpFbPacket.PT
            reportCount = RtcpFbCcfbPacket.FMT
            length = RtpUtils.calculateRtcpLengthFieldValue(blockLength)
        }.writeTo(buf, offset)

        RtcpFbPacket.setMediaSourceSsrc(buf, offset, mediaSourceSsrc)
        var position = offset + RtcpFbPacket.HEADER_SIZE

        groupedPackets.forEach { (ssrc, packets) ->
            check(packets.size <= 16384) {
                "Unexpected number of reports: ${packets.size}"
            }

            buf.putInt(position, ssrc.toInt())
            position += 4

            val beginSeq = packets.first().sequenceNumber
            val numReports = packets.size

            buf.putShort(position, beginSeq.toShort())
            position += 2
            buf.putShort(position, numReports.toShort())
            position += 2

            packets.forEachIndexed { i, packet ->
                val expectedSeq = RtpUtils.applySequenceNumberDelta(beginSeq, i)
                check(packet.sequenceNumber == expectedSeq) {
                    "Sequence number for report $i of $ssrc is wrong " +
                        "(expected $expectedSeq == $beginSeq + $i, got ${packet.sequenceNumber})"
                }
                val packetInfo = if (packet is ReceivedPacketInfo) {
                    0x8000 or to2BitEcn(packet.ecn) or to13BitAto(packet.arrivalTimeOffset)
                } else {
                    0
                }
                buf.putShort(position, packetInfo.toShort())
                position += 2
            }
            repeat(RtpUtils.getNumPaddingBytes(2 * numReports)) {
                buf[position++] = 0x00
            }
        }

        buf.putInt(position, reportTimestampCompactNtp.toInt())
        position += 4
        assert(position - offset == packetSize)
    }

    fun build(): RtcpFbCcfbPacket {
        val blockLength = blockLength()
        val packetSize = blockLength + RtpUtils.getNumPaddingBytes(blockLength)
        val buf = BufferPool.getArray(packetSize)
        writeTo(buf, 0)
        return RtcpFbCcfbPacket(buf, 0, packetSize)
    }
}

// Arrival time offset (ATO, 13 bits):
// The arrival time of the RTP packet at the receiver, as an offset before the
// time represented by the Report Timestamp (RTS) field of this RTCP congestion
// control feedback report. The ATO field is in units of 1/1024 seconds (this
// unit is chosen to give exact offsets from the RTS field) so, for example, an
// ATO value of 512 indicates that the corresponding RTP packet arrived exactly
// half a second before the time instant represented by the RTS field. If the
// measured value is greater than 8189/1024 seconds (the value that would be
// coded as 0x1FFD), the value 0x1FFE MUST be reported to indicate an over-range
// measurement. If the measurement is unavailable or if the arrival time of the
// RTP packet is after the time represented by the RTS field, then an ATO value
// of 0x1FFF MUST be reported for the packet.
private fun to13BitAto(arrivalTimeOffset: Duration): Int {
    if (arrivalTimeOffset.isNegative) {
        return 0x1FFF
    }
    return min((1024 * arrivalTimeOffset.toDouble()).toInt(), 0x1FFE)
}

private fun atoToTimeDelta(receiveInfo: Int): Duration {
    val ato = receiveInfo and 0x1FFF
    if (ato == 0x1FFE) {
        return MAX_DURATION
    }
    if (ato == 0x1FFF) {
        return MIN_DURATION
    }
    return ato.secs / 1024
}

private fun toEcnMarking(receiveInfo: Int): EcnMarking {
    return EcnMarking.fromBits(((receiveInfo shr 13) and 0b11).toByte())
}

private fun to2BitEcn(ecnMarking: EcnMarking): Int {
    return ecnMarking.bits.toInt() shl 13
}

/*
     +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     |V=2|P| FMT=11  |   PT = 205    |          length               |
     +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     |                 SSRC of RTCP packet sender                    |
     +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     |                   SSRC of 1st RTP Stream                      |
     +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     |          begin_seq            |          num_reports          |
     +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     |R|ECN|  Arrival time offset    | ...                           .
     +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     .                                                               .
     .                                                               .
     .                                                               .
     +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     |                   SSRC of nth RTP Stream                      |
     +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     |          begin_seq            |          num_reports          |
     +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     |R|ECN|  Arrival time offset    | ...                           |
     .                                                               .
     .                                                               .
     +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     |                 Report Timestamp (32 bits)                    |
     +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
*/

class RtcpFbCcfbPacket(
    buffer: ByteArray,
    offset: Int,
    length: Int
) : TransportLayerRtcpFbPacket(buffer, offset, length) {
    /**
     * Because much of time this packet is one that we built (not one
     * that came in from the network) we don't care about re-parsing all
     * of these fields.  To avoid doing this work, we put them in this
     * data class and make its initialization lazy: they'll only be parsed
     * if we access them (which we do for packets that are received from
     * the network but not for ones we send out).
     */
    private data class CcfbData(
        val packets: List<PacketInfo>,
        val reportTimestampCompactNtp: Long
    )

    private val data: CcfbData by lazy(LazyThreadSafetyMode.NONE) {
        val packets = mutableListOf<PacketInfo>()

        require(packetLength - FCI_OFFSET >= kTimestampLength) {
            "CCFB packet is too small for timestamp"
        }
        val reportTimestampCompactNtp = buffer.getIntAsLong(packetLength - kTimestampLength)

        var position = offset + FCI_OFFSET
        val end = offset + packetLength - kTimestampLength

        while (position + kHeaderPerMediaSsrcLength < end) {
            val ssrc = buffer.getIntAsLong(position)
            position += 4
            val baseSeqno = buffer.getShortAsInt(position)
            position += 2
            val numReports = buffer.getShortAsInt(position)
            position += 2

            require(position + 2 * numReports <= end) {
                "Reports would extend past end of CCFB packet"
            }

            for (i in 0 until numReports) {
                val packetInfo = buffer.getShortAsInt(position)
                position += 2
                val seqNo = RtpUtils.applySequenceNumberDelta(baseSeqno, i)
                val received = (packetInfo and 0x8000) != 0
                packets.add(
                    if (received) {
                        ReceivedPacketInfo(
                            ssrc = ssrc,
                            sequenceNumber = seqNo,
                            arrivalTimeOffset = atoToTimeDelta(packetInfo),
                            ecn = toEcnMarking(packetInfo)
                        )
                    } else {
                        UnreceivedPacketInfo(
                            ssrc = ssrc,
                            sequenceNumber = seqNo
                        )
                    }
                )
            }
            if ((numReports % 2) != 0) {
                // 2 bytes padding
                position += 2
            }
        }
        require(position == end) {
            "CCFB packet size was incorrect"
        }

        CcfbData(packets, reportTimestampCompactNtp)
    }

    val packets: List<PacketInfo>
        get() = data.packets

    val reportTimestampCompactNtp: Long
        get() = data.reportTimestampCompactNtp

    override fun clone(): RtcpFbCcfbPacket = RtcpFbCcfbPacket(cloneBuffer(0), 0, length)
    companion object {
        const val FMT = 11
    }
}
