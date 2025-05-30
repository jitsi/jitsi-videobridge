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

import org.jitsi.rtp.rtcp.RtcpHeaderBuilder
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.TransportLayerRtcpFbPacket
import org.jitsi.utils.secs
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
)

class UnreceivedPacketInfo(ssrc: Long = 0, sequenceNumber: Int = 0) : PacketInfo(ssrc, sequenceNumber)

class ReceivedPacketInfo(
    ssrc: Long = 0,
    sequenceNumber: Int = 0,
    val arrivalTimeOffset: Duration,
    val ecn: EcnMarking = EcnMarking.kNotEct
) : PacketInfo(ssrc, sequenceNumber)

class RtcpFbCcfbPacketBuilder(
    val rtcpHeader: RtcpHeaderBuilder = RtcpHeaderBuilder(),
    var mediaSourceSsrc: Long = -1,
    // `Packets` MUST be sorted in sequence_number order per SSRC. There MUST not
    // be missing sequence numbers between `Packets`. `Packets` MUST not include
    // duplicate sequence numbers.
    val packets: List<PacketInfo> = listOf(),
    val report_timestamp_compact_ntp: Long
) {
    fun build(): RtcpFbCcfbPacket {
        TODO()
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
private fun to13BitAto(arrival_time_offset: Duration): Short {
    if (arrival_time_offset.isNegative) {
        return 0x1FFF
    }
    return min((1024 * arrival_time_offset.toDouble()).toInt(), 0x1FFE).toShort()
}

private fun atoToTimeDelta(receiveInfo: Short): Duration {
    val ato = receiveInfo.toInt() and 0x1FFF
    if (ato == 0x1FFE) {
        return maxDuration
    }
    if (ato == 0x1FFF) {
        return minDuration
    }
    return ato.secs / 1024
}

private fun to2BitEcn(receiveInfo: Short): EcnMarking {
    return EcnMarking.fromBits(((receiveInfo.toInt() shr 13) and 0b11).toByte())
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

    override fun clone(): RtcpFbCcfbPacket = RtcpFbCcfbPacket(cloneBuffer(0), 0, length)
    companion object {
        const val kFeedbackMessageType = 11
    }
}

/* TODO: use versions of these from jitsi-utils once they're moved there from updated-bwe branch */
fun Duration.toDouble(): Double {
    return this.seconds.toDouble() + this.nano.toDouble() * 1e-9
}

val minDuration = Duration.ofSeconds(Long.MIN_VALUE, 0)

val maxDuration = Duration.ofSeconds(Long.MAX_VALUE, 999_999_999)

operator fun Duration.div(other: Long): Duration = this.dividedBy(other)
