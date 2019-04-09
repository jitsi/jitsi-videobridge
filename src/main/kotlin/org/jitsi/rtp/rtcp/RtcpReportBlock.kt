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

import org.jitsi.rtp.extensions.unsigned.toPositiveInt
import org.jitsi.rtp.extensions.unsigned.toPositiveLong
import org.jitsi.rtp.extensions.bytearray.put3Bytes
import org.jitsi.rtp.extensions.bytearray.putInt
import org.jitsi.rtp.util.get3BytesAsInt
import org.jitsi.rtp.util.getByteAsInt
import org.jitsi.rtp.util.getIntAsLong

/**
 * +=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+
 * |                 SSRC_1 (SSRC of first source)                 |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * | fraction lost |       cumulative number of packets lost       |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |           extended highest sequence number received           |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                      interarrival jitter                      |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                         last SR (LSR)                         |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                   delay since last SR (DLSR)                  |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 */
class RtcpReportBlock(
    val ssrc: Long,
    val fractionLost: Int,
    val cumulativePacketsLost: Int,
    val extendedHighestSeqNum: Long,
    val interarrivalJitter: Long,
    val lastSrTimestamp: Long,
    val delaySinceLastSr: Long
) {
    constructor(
        ssrc: Long,
        fractionLost: Int,
        cumulativePacketsLost: Int,
        seqNumCycles: Int,
        seqNum: Int,
        interarrivalJitter: Long,
        lastSrTimestamp: Long,
        delaySinceLastSr: Long
    ) : this(ssrc, fractionLost, cumulativePacketsLost,
        ((seqNumCycles shl 16) + seqNum.toShort()).toPositiveLong(),
        interarrivalJitter, lastSrTimestamp, delaySinceLastSr
    )

    val seqNumCycles: Int by lazy {
        (extendedHighestSeqNum ushr 16).toPositiveInt()
    }
    val seqNum: Int by lazy {
        extendedHighestSeqNum.toShort().toPositiveInt()
    }

    fun writeTo(buf: ByteArray, offset: Int) {
        setSsrc(buf, offset, ssrc)
        setFractionLost(buf, offset, fractionLost)
        setCumulativePacketsLost(buf, offset, cumulativePacketsLost)
        setExtendedHighestSeqNum(buf, offset, extendedHighestSeqNum)
        setInterarrivalJitter(buf, offset, interarrivalJitter)
        setLastSrTimestamp(buf, offset, lastSrTimestamp)
        setDelaySinceLastSr(buf, offset, delaySinceLastSr)
    }

    companion object {
        const val SIZE_BYTES = 24
        // Offsets relative to the start of an RTCP Report Block
        const val SSRC_OFFSET = 0
        const val FRACTION_LOST_OFFSET = 4
        const val CUMULATIVE_PACKETS_LOST_OFFSET = 5
        const val EXTENDED_HIGHEST_SEQ_NUM_OFFSET = 8
        const val INTERARRIVAL_JITTER_OFFSET = 12
        const val LAST_SR_TIMESTAMP_OFFSET = 16
        const val DELAY_SINCE_LAST_SR_OFFSET = 20
        fun fromBuffer(buffer: ByteArray, offset: Int): RtcpReportBlock {
            val ssrc = getSsrc(buffer, offset)
            val fractionLost = getFractionLost(buffer, offset)
            val cumulativePacketsLost = getCumulativePacketsLost(buffer, offset)
            val extendedHighestSeqNum = getExtendedHighestSeqNum(buffer, offset)
            val interarrivalJitter = getInterarrivalJitter(buffer, offset)
            val lastSrTimestamp = getLastSrTimestamp(buffer, offset)
            val delaySinceLastSr = getDelaySinceLastSr(buffer, offset)

            return RtcpReportBlock(ssrc, fractionLost, cumulativePacketsLost, extendedHighestSeqNum,
                interarrivalJitter, lastSrTimestamp, delaySinceLastSr)
        }

        fun getSsrc(buffer: ByteArray, offset: Int): Long =
            buffer.getIntAsLong(offset + SSRC_OFFSET)
        fun setSsrc(buf: ByteArray, baseOffset: Int, value: Long) =
            buf.putInt(baseOffset + SSRC_OFFSET, value.toInt())

        fun getFractionLost(buffer: ByteArray, offset: Int): Int =
            buffer.getByteAsInt(offset + FRACTION_LOST_OFFSET)
        fun setFractionLost(buf: ByteArray, baseOffset: Int, value: Int) =
            buf.set(baseOffset + FRACTION_LOST_OFFSET, value.toByte())

        fun getCumulativePacketsLost(buffer: ByteArray, offset: Int): Int =
            buffer.get3BytesAsInt(offset + CUMULATIVE_PACKETS_LOST_OFFSET)
        fun setCumulativePacketsLost(buffer: ByteArray, offset: Int, value: Int) =
            buffer.put3Bytes(offset + CUMULATIVE_PACKETS_LOST_OFFSET, value)

        fun getExtendedHighestSeqNum(buffer: ByteArray, offset: Int): Long =
            buffer.getIntAsLong(offset + EXTENDED_HIGHEST_SEQ_NUM_OFFSET)
        fun setExtendedHighestSeqNum(buf: ByteArray, baseOffset: Int, value: Long) =
            buf.putInt(baseOffset + EXTENDED_HIGHEST_SEQ_NUM_OFFSET, value.toInt())

        fun getInterarrivalJitter(buffer: ByteArray, offset: Int): Long =
            buffer.getIntAsLong(offset + INTERARRIVAL_JITTER_OFFSET)
        fun setInterarrivalJitter(buf: ByteArray, baseOffset: Int, value: Long) =
            buf.putInt(baseOffset + INTERARRIVAL_JITTER_OFFSET, value.toInt())

        fun getLastSrTimestamp(buffer: ByteArray, offset: Int): Long =
            buffer.getIntAsLong(offset + LAST_SR_TIMESTAMP_OFFSET)
        fun setLastSrTimestamp(buf: ByteArray, baseOffset: Int, value: Long) =
            buf.putInt(baseOffset + LAST_SR_TIMESTAMP_OFFSET, value.toInt())

        fun getDelaySinceLastSr(buffer: ByteArray, offset: Int): Long =
            buffer.getIntAsLong(offset + DELAY_SINCE_LAST_SR_OFFSET)
        fun setDelaySinceLastSr(buf: ByteArray, baseOffset: Int, value: Long) =
            buf.putInt(baseOffset + DELAY_SINCE_LAST_SR_OFFSET, value.toInt())
    }
}
