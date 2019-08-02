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

package org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc

import org.jitsi.rtp.extensions.bytearray.put3Bytes
import org.jitsi.rtp.extensions.bytearray.putShort
import org.jitsi.rtp.extensions.unsigned.toPositiveLong
import org.jitsi.rtp.extensions.unsigned.toPositiveShort
import org.jitsi.rtp.rtcp.RtcpHeaderBuilder
import org.jitsi.rtp.rtcp.rtcpfb.RtcpFbPacket
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.TransportLayerRtcpFbPacket
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.RtcpFbTccPacket.Companion.kBaseScaleFactor
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.RtcpFbTccPacket.Companion.kChunkSizeBytes
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.RtcpFbTccPacket.Companion.kDeltaScaleFactor
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.RtcpFbTccPacket.Companion.kMaxReportedPackets
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.RtcpFbTccPacket.Companion.kMaxSizeBytes
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.RtcpFbTccPacket.Companion.kTimeWrapPeriodUs
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.RtcpFbTccPacket.Companion.kTransportFeedbackHeaderSizeBytes
import org.jitsi.rtp.rtp.RtpSequenceNumber
import org.jitsi.rtp.util.BufferPool
import org.jitsi.rtp.util.RtpUtils
import org.jitsi.rtp.util.get3BytesAsInt
import org.jitsi.rtp.util.getByteAsInt
import org.jitsi.rtp.util.getShortAsInt

// Size in bytes of a delta time in rtcp packet.
// Valid values are 0 (packet wasn't received), 1 or 2.
typealias DeltaSize = Int

class ReceivedPacket(val seqNum: Int, val deltaTicks: Short) {
    operator fun component1(): Int = seqNum
    operator fun component2(): Short = deltaTicks
}

/**
 * This class is a port of TransportFeedback in
 * transport_feedback.h/transport_feedback.cc in Chrome
 * https://cs.chromium.org/chromium/src/third_party/webrtc/modules/rtp_rtcp/source/rtcp_packet/transport_feedback.h?l=95&rcl=20393ee9b7ba622f254908646a9c31bf87349fc7
 *
 * Because of this, it explicitly does NOT try to conform
 * to Kotlin style or idioms, instead striving to match the
 * Chrome code as closely as possible in an effort to make
 * future updates easier.
 */
class RtcpFbTccPacketBuilder(
    val rtcpHeader: RtcpHeaderBuilder = RtcpHeaderBuilder(),
    var mediaSourceSsrc: Long = -1,
    val feedbackPacketSeqNum: Int = -1
) {
    var base_seq_no_: RtpSequenceNumber = RtpSequenceNumber.INVALID
        private set
    // The reference time, in ticks.  Chrome passes this into BuildFeedbackPacket, but we don't
    // hold the times in the same way, so we'll just assign it the first time we see
    // a packet in AddReceivedPacket
    private var base_time_ticks_: Long = -1
    // The amount of packets_ whose status are represented
    var num_seq_no_ = 0
        private set
    // The current chunk we're 'filling out' as packets
    // are received
    private var last_chunk_ = LastChunk()
    // All but last encoded packet chunks.
    private val encoded_chunks_ = mutableListOf<Chunk>()
    // The size of the entire packet, in bytes
    private var size_bytes_ = kTransportFeedbackHeaderSizeBytes
    private var last_timestamp_us_: Long = 0
    private val packets_ = mutableListOf<ReceivedPacket>()

    fun SetBase(base_sequence: Int, ref_timestamp_us: Long) {
        base_seq_no_ = RtpSequenceNumber(base_sequence)
        base_time_ticks_ = (ref_timestamp_us % kTimeWrapPeriodUs) / kBaseScaleFactor
        last_timestamp_us_ = GetBaseTimeUs()
    }

    fun AddReceivedPacket(seqNum: Int, timestamp_us: Long): Boolean {
        val sequence_number = RtpSequenceNumber(seqNum)
        var delta_full = (timestamp_us - last_timestamp_us_) % kTimeWrapPeriodUs
        if (delta_full > kTimeWrapPeriodUs / 2) {
            delta_full -= kTimeWrapPeriodUs
        }
        delta_full += if (delta_full < 0) -(kDeltaScaleFactor / 2) else kDeltaScaleFactor / 2
        delta_full /= kDeltaScaleFactor

        val delta = delta_full.toShort()
        // If larger than 16bit signed, we can't represent it - need new fb packet.
        if (delta.toLong() != delta_full) {
            println("Delta value too large! ( >= 2^16 ticks )")
            return false
        }
        var next_seq_no = base_seq_no_ + num_seq_no_
        if (sequence_number != next_seq_no) {
            val lastSeqNo = next_seq_no - 1
            if (sequence_number <= lastSeqNo) {
                return false
            }
            while (next_seq_no != sequence_number) {
                if (!AddDeltaSize(0))
                    return false
                next_seq_no += 1
            }
        }
        val delta_size = if (delta >= 0 && delta <= 0xff) 1 else 2
        if (!AddDeltaSize(delta_size))
            return false

        packets_.add(ReceivedPacket(sequence_number.value, delta))
        last_timestamp_us_ += delta * kDeltaScaleFactor
        size_bytes_ += delta_size

        return true
    }

    fun GetBaseTimeUs(): Long =
        base_time_ticks_ * kBaseScaleFactor

    private fun AddDeltaSize(deltaSize: DeltaSize): Boolean {
        if (num_seq_no_ == kMaxReportedPackets)
            return false
        val add_chunk_size = if (last_chunk_.Empty()) kChunkSizeBytes else 0

        if (size_bytes_ + deltaSize + add_chunk_size > kMaxSizeBytes)
            return false

        if (last_chunk_.CanAdd(deltaSize)) {
            size_bytes_ += add_chunk_size
            last_chunk_.Add(deltaSize)
            ++num_seq_no_
            return true
        }

        if (size_bytes_ + deltaSize + kChunkSizeBytes > kMaxSizeBytes)
            return false

        encoded_chunks_.add(last_chunk_.Emit())
        size_bytes_ += kChunkSizeBytes
        last_chunk_.Add(deltaSize)
        ++num_seq_no_
        return true
    }

    fun build(): RtcpFbTccPacket {
        val packetSize = size_bytes_ + RtpUtils.getNumPaddingBytes(size_bytes_)
        val buf = BufferPool.getArray(packetSize)
        writeTo(buf, 0)
        return RtcpFbTccPacket(buf, 0, packetSize)
    }

    fun writeTo(buf: ByteArray, offset: Int) {
        // NOTE: padding is held 'internally' in the TCC FCI, so we don't set
        // the padding bit on the header
        val paddingBytes = RtpUtils.getNumPaddingBytes(size_bytes_)
        rtcpHeader.apply {
            packetType = TransportLayerRtcpFbPacket.PT
            reportCount = RtcpFbTccPacket.FMT
            length = RtpUtils.calculateRtcpLengthFieldValue(size_bytes_ + paddingBytes)
        }.writeTo(buf, offset)

        RtcpFbPacket.setMediaSourceSsrc(buf, offset, mediaSourceSsrc)
        RtcpFbTccPacket.setBaseSeqNum(buf, offset, base_seq_no_.value)
        RtcpFbTccPacket.setPacketStatusCount(buf, offset, num_seq_no_)
        RtcpFbTccPacket.setReferenceTimeTicks(buf, offset, base_time_ticks_.toInt())
        RtcpFbTccPacket.setFeedbackPacketCount(buf, offset, feedbackPacketSeqNum)

        var currOffset = RtcpFbTccPacket.PACKET_CHUNKS_OFFSET
        encoded_chunks_.forEach {
            buf.putShort(currOffset, it.toShort())
            currOffset += kChunkSizeBytes
        }
        if (!last_chunk_.Empty()) {
            val chunk = last_chunk_.EncodeLast()
            buf.putShort(currOffset, chunk.toShort())
            currOffset += kChunkSizeBytes
        }
        packets_.forEach {
            when (it.deltaTicks) {
                in 0..0xFF -> buf[currOffset++] = it.deltaTicks.toByte()
                else -> {
                    buf.putShort(currOffset, it.deltaTicks)
                    currOffset += 2
                }
            }
        }
        repeat(paddingBytes) {
            buf[currOffset++] = 0x00
        }
    }

    fun clear() {
        num_seq_no_ = 0
        size_bytes_ = kTransportFeedbackHeaderSizeBytes
    }
}

/**
 * NOTE(brian): This class is a port of the rest of the logic in TransportFeedback
 * not covered by RtcpFbTccPacketBuilder.  Chrome uses a single class for both
 * the 'builder' and the 'parser' but because of the way we define packets
 * (inheriting from the buffer type and therefore always requiring a valid buffer),
 * we separate builders out into their own class.  Because of that, this class
 * and RtcpFbTccPacketBuilder have overlap in their members.
 *
 * https://tools.ietf.org/html/draft-holmer-rmcat-transport-wide-cc-extensions-01#section-3.1
 * 0                   1                   2                   3
 * 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |V=2|P|  FMT=15 |    PT=205     |           length              |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                     SSRC of packet sender                     |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                      SSRC of media source                     |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |      base sequence number     |      packet status count      |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                 reference time                | fb pkt. count |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |          packet chunk         |         packet chunk          |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * .                                                               .
 * .                                                               .
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |         packet chunk          |  recv delta   |  recv delta   |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * .                                                               .
 * .                                                               .
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |           recv delta          |  recv delta   | zero padding  |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *
 * packet status count:  16 bits The number of packets this feedback
 *  contains status for, starting with the packet identified
 *  by the base sequence number.
 *
 * feedback packet count:  8 bits A counter incremented by one for each
 *  feedback packet sent.  Used to detect feedback packet
 *  losses.
 */
class RtcpFbTccPacket(
    buffer: ByteArray,
    offset: Int,
    length: Int
) : TransportLayerRtcpFbPacket(buffer, offset, length), Iterable<ReceivedPacket> {

    /**
     * Because much of time this packet is one that we built (not one
     * that came in from the network) we don't care about re-parsing all
     * of these fields.  To avoid doing this work, we put them in this
     * data class and make its initialization lazy: they'll only be parsed
     * if we access them (which we do for packets that are received from
     * the network but not for ones we send out).
     */
    private data class TccMemberData(
        val base_seq_no_: Int,
        var base_time_ticks_: Long,
        val encoded_chunks_: MutableList<Chunk>,
        var last_chunk_: LastChunk,
        var num_seq_no_: Int,
        var last_timestamp_us_: Long,
        val packets_: MutableList<ReceivedPacket>
    )

    private val data: TccMemberData by lazy(LazyThreadSafetyMode.NONE) {
        val base_seq_no_ = getBaseSeqNum(buffer, offset)
        val status_count = getPacketStatusCount(buffer, offset)
        val encoded_chunks_ = mutableListOf<Chunk>()
        val last_chunk_ = LastChunk()
        var num_seq_no_: Int = 0
        var last_timestamp_us_: Long = 0
        val packets_ = mutableListOf<ReceivedPacket>()

        val base_time_ticks_ = getReferenceTimeTicks(buffer, offset)
        val delta_sizes = mutableListOf<Int>()
        var index = offset + PACKET_CHUNKS_OFFSET
        val end_index = offset + length
        while (delta_sizes.size < status_count) {
            if (index + kChunkSizeBytes > end_index) {
                throw Exception("Buffer overflow while parsing packet.")
            }
            val chunk = buffer.getShortAsInt(index)
            index += kChunkSizeBytes
            encoded_chunks_.add(chunk)
            last_chunk_.Decode(chunk, status_count - delta_sizes.size)
            last_chunk_.AppendTo(delta_sizes)
        }
        // Last chunk is stored in the |last_chunk_|.
        encoded_chunks_.dropLast(1)
        num_seq_no_ = status_count

        var seq_no = base_seq_no_
        var recv_delta_size = 0
        for (delta_size in delta_sizes) {
            recv_delta_size += delta_size
        }

        // Determine if timestamps, that is, recv_delta are included in the packet.
        if (end_index >= index + recv_delta_size) {
            for (delta_size in delta_sizes) {
                if (index + delta_size > end_index) {
                    throw Exception("Buffer overflow while parsing packet.")
                }
                when (delta_size) {
                    0 -> { /* No-op */ }
                    1 -> {
                        val delta = buffer[index]
                        packets_.add(ReceivedPacket(seq_no, delta.toPositiveShort()))
                        last_timestamp_us_ += delta * kDeltaScaleFactor
                        index += delta_size
                    }
                    2 -> {
                        val delta = buffer.getShortAsInt(index)
                        packets_.add(ReceivedPacket(seq_no, delta.toShort()))
                        last_timestamp_us_ += delta * kDeltaScaleFactor
                        index += delta_size
                    }
                    3 -> {
                        throw Exception("Warning: invalid delta size for seq_no $seq_no")
                    }
                }
                ++seq_no
            }
        } else {
            // The packet does not contain receive deltas
            for (delta_size in delta_sizes) {
                // Use delta sizes to detect if packet was received.
                if (delta_size > 0) {
                    packets_.add(ReceivedPacket(seq_no, 0))
                }
                ++seq_no
            }
        }
        TccMemberData(base_seq_no_, base_time_ticks_, encoded_chunks_, last_chunk_, num_seq_no_, last_timestamp_us_, packets_)
    }

    // All but last encoded packet chunks.
    private val encoded_chunks_: MutableList<Chunk>
        get() = data.encoded_chunks_
    // The current chunk we're 'filling out' as packets
    // are received
    private var last_chunk_: LastChunk
        get() = data.last_chunk_
        set(value) {
            data.last_chunk_ = value
        }
    private val base_seq_no_: Int
        get() = data.base_seq_no_
    private var num_seq_no_: Int
        get() = data.num_seq_no_
        set(value) {
            data.num_seq_no_ = value
        }
    private val packets_: MutableList<ReceivedPacket>
        get() = data.packets_
    private var last_timestamp_us_: Long
        get() = data.last_timestamp_us_
        set(value) {
            data.last_timestamp_us_ = value
        }
    // The reference time, in ticks.
    private var base_time_ticks_: Long
        get() = data.base_time_ticks_
        set(value) {
            data.base_time_ticks_ = value
        }

    val feedbackSeqNum: Int = getFeedbackPacketCount(buffer, offset)

    fun GetBaseTimeUs(): Long =
        base_time_ticks_ * kBaseScaleFactor

    override fun iterator(): Iterator<ReceivedPacket> = packets_.iterator()

    override fun clone(): RtcpFbTccPacket = RtcpFbTccPacket(cloneBuffer(0), 0, length)

    companion object {
        const val FMT = 15
        // Convert to multiples of 0.25ms
        const val kDeltaScaleFactor = 250
        // Maximum number of packets_ (including missing) TransportFeedback can report.
        const val kMaxReportedPackets = 0xFFFF
        const val kChunkSizeBytes = 2
        // Size constraint imposed by RTCP common header: 16bit size field interpreted
        // as number of four byte words minus the first header word.
        const val kMaxSizeBytes = (1 shl 16) * 4
        // Header size:
        // * 4 bytes Common RTCP Packet Header
        // * 8 bytes Common Packet Format for RTCP Feedback Messages
        // * 8 bytes FeedbackPacket header
        const val kTransportFeedbackHeaderSizeBytes = 4 + 8 + 8
        // Used to convert from microseconds to multiples of 64ms
        const val kBaseScaleFactor = kDeltaScaleFactor * (1 shl 8)
        // The reference time field is 24 bits and are represented as multiples of 64ms
        // When the reference time field would need to wrap around
        const val kTimeWrapPeriodUs: Long = (1 shl 24).toLong() * kBaseScaleFactor

        const val BASE_SEQ_NUM_OFFSET = RtcpFbPacket.HEADER_SIZE
        const val PACKET_STATUS_COUNT_OFFSET = RtcpFbPacket.HEADER_SIZE + 2
        const val REFERENCE_TIME_OFFSET = RtcpFbPacket.HEADER_SIZE + 4
        const val FB_PACKET_COUNT_OFFSET = RtcpFbPacket.HEADER_SIZE + 7
        const val PACKET_CHUNKS_OFFSET = RtcpFbPacket.HEADER_SIZE + 8
        // baseOffset in all of these refers to the start of the entire RTCP TCC packet
        fun getBaseSeqNum(buf: ByteArray, baseOffset: Int): Int =
            buf.getShortAsInt(baseOffset + BASE_SEQ_NUM_OFFSET)
        fun setBaseSeqNum(buf: ByteArray, baseOffset: Int, value: Int) =
            buf.putShort(baseOffset + BASE_SEQ_NUM_OFFSET, value.toShort())

        fun getPacketStatusCount(buf: ByteArray, baseOffset: Int): Int =
            buf.getShortAsInt(baseOffset + PACKET_STATUS_COUNT_OFFSET)
        fun setPacketStatusCount(buf: ByteArray, baseOffset: Int, value: Int) =
            buf.putShort(baseOffset + PACKET_STATUS_COUNT_OFFSET, value.toShort())

        fun getReferenceTimeTicks(buf: ByteArray, baseOffset: Int): Long =
            buf.get3BytesAsInt(baseOffset + REFERENCE_TIME_OFFSET).toPositiveLong()
        fun setReferenceTimeTicks(buf: ByteArray, baseOffset: Int, refTimeTicks: Int) =
            buf.put3Bytes(baseOffset + REFERENCE_TIME_OFFSET, refTimeTicks)

        fun getFeedbackPacketCount(buf: ByteArray, baseOffset: Int): Int =
            buf.getByteAsInt(baseOffset + FB_PACKET_COUNT_OFFSET)
        fun setFeedbackPacketCount(buf: ByteArray, baseOffset: Int, value: Int) =
            buf.set(baseOffset + FB_PACKET_COUNT_OFFSET, value.toByte())
    }
}
