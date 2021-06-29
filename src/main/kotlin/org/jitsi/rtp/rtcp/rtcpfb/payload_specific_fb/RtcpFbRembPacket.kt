/*
 * Copyright @ 2019 - present 8x8, Inc.
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

package org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb

import org.jitsi.rtp.extensions.bytearray.putInt
import org.jitsi.rtp.rtcp.RtcpHeaderBuilder
import org.jitsi.rtp.rtcp.rtcpfb.RtcpFbPacket
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbRembPacket.Companion.BR_LEN
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbRembPacket.Companion.NUM_SSRC_LEN
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbRembPacket.Companion.REMB_LEN
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbRembPacket.Companion.getExpAndMantissa
import org.jitsi.rtp.util.BufferPool
import org.jitsi.rtp.util.RtpUtils
import org.jitsi.rtp.util.getBitsAsInt
import org.jitsi.rtp.util.getByteAsInt
import org.jitsi.rtp.util.getIntAsLong
import org.jitsi.rtp.util.getShortAsInt

/**
 * https://tools.ietf.org/html/draft-alvestrand-rmcat-remb-03
 *
 * 0                   1                   2                   3
 * 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |V=2|P| FMT=15  |   PT=206      |             length            |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                  SSRC of packet sender                        |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                  SSRC of media source                         |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |  Unique identifier 'R' 'E' 'M' 'B'                            |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |  Num SSRC     | BR Exp    |  BR Mantissa                      |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |   SSRC feedback                                               |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |  ...                                                          |
 *
 * @author George Politis
 * @author Boris Grozev
 */
class RtcpFbRembPacket(
    buffer: ByteArray,
    offset: Int,
    length: Int
) : PayloadSpecificRtcpFbPacket(buffer, offset, length) {

    val bitrate: Long = getBitrate(buffer, offset)

    val numSsrc: Int = getNumSsrc(buffer, offset)

    /**
     * one or more SSRC entries which this feedback message applies to.
     */
    val ssrcs: List<Long> by lazy {
        (0 until numSsrc).map {
            getSsrc(buffer, offset, it)
        }.toList()
    }

    override fun clone(): RtcpFbRembPacket {
        return RtcpFbRembPacket(cloneBuffer(0), 0, length)
    }

    companion object {
        const val FMT = 15

        const val REMB_OFF = FCI_OFFSET
        const val REMB_LEN = 4
        const val NUM_SSRC_OFF = REMB_OFF + REMB_LEN
        const val NUM_SSRC_LEN = 1
        const val BR_OFF = NUM_SSRC_OFF + NUM_SSRC_LEN
        const val BR_LEN = 3
        const val SSRCS_OFF = BR_OFF + BR_LEN

        fun getBrExp(buf: ByteArray, baseOffset: Int): Int =
            buf.getBitsAsInt(baseOffset + BR_OFF, 0, 6)
        fun getBrMantissa(buf: ByteArray, baseOffset: Int): Int =
            (buf.getBitsAsInt(baseOffset + BR_OFF, 6, 2) shl 16) + buf.getShortAsInt(baseOffset + BR_OFF + 1)
        fun getBitrate(buf: ByteArray, baseOffset: Int): Long {
            val mantissa = getBrMantissa(buf, baseOffset)
            val exp = getBrExp(buf, baseOffset)
            val brBps = mantissa.toLong() shl exp
            if ((brBps shr exp).toInt() != mantissa) {
                // This block catches a Java long overflow (i.e. the bitrate larger than Long.MAX_VALUE). Although this
                // could potentially indicate a malformed remb or a bug in our code, we chose to interpret as the remote
                // party trying to signal unbounded bandwidth.
                return Long.MAX_VALUE
            }

            return brBps
        }
        fun getNumSsrc(buf: ByteArray, baseOffset: Int): Int =
            buf.getByteAsInt(baseOffset + NUM_SSRC_OFF)
        fun getSsrc(buf: ByteArray, baseOffset: Int, ssrcIndex: Int) =
            buf.getIntAsLong(baseOffset + SSRCS_OFF + ssrcIndex * 4)

        fun getExpAndMantissa(brBps: Long): Pair<Int, Int> {
            // 6 bit Exp
            // 18 bit mantissa
            var exp = 0
            var mantissa = if (brBps > 0) brBps else 0

            // 0x3ffff (262143) is the max value that can be put into an 18-bit unsigned integer
            while (mantissa > 0x3ffff) {
                mantissa = mantissa shr 1
                ++exp
            }

            return Pair(exp, mantissa.toInt())
        }

        fun setRemb(buf: ByteArray, baseOffset: Int) {
            buf[baseOffset + REMB_OFF + 0] = 'R'.toByte()
            buf[baseOffset + REMB_OFF + 1] = 'E'.toByte()
            buf[baseOffset + REMB_OFF + 2] = 'M'.toByte()
            buf[baseOffset + REMB_OFF + 3] = 'B'.toByte()
        }

        fun setNumSsrc(buf: ByteArray, off: Int, value: Int) {
            buf[off + NUM_SSRC_OFF] = value.toByte()
        }

        fun setBrExp(buf: ByteArray, baseOffset: Int, value: Int) {
            buf[baseOffset + BR_OFF] =
                ((buf[baseOffset + BR_OFF].toInt() and 0x03) or (value and 0x3f shl 2)).toByte()
        }

        fun setBrMantissa(buf: ByteArray, baseOffset: Int, value: Int) {
            buf[baseOffset + BR_OFF] =
                ((buf[baseOffset + BR_OFF].toInt() and 0xfc) or ((value shr 16) and 0x03)).toByte()
            buf[baseOffset + BR_OFF + 1] = ((value shr 8) and 0xff).toByte()
            buf[baseOffset + BR_OFF + 2] = (value and 0xff).toByte()
        }

        fun setSsrcs(buf: ByteArray, baseOffset: Int, ssrcs: List<Long>) {
            var ssrcsOff = baseOffset + SSRCS_OFF
            ssrcs.forEach {
                buf.putInt(ssrcsOff, it.toInt())
                ssrcsOff += 4
            }
        }
    }
}

class RtcpFbRembPacketBuilder(
    val rtcpHeader: RtcpHeaderBuilder = RtcpHeaderBuilder(),
    val ssrcs: List<Long> = emptyList(),
    val brBps: Long
) {
    private val sizeBytes: Int = RtcpFbPacket.HEADER_SIZE +
        REMB_LEN + NUM_SSRC_LEN + BR_LEN + ssrcs.size * 4

    fun build(): RtcpFbRembPacket {
        val buf = BufferPool.getArray(sizeBytes)
        writeTo(buf, 0)
        return RtcpFbRembPacket(buf, 0, sizeBytes)
    }

    fun writeTo(buf: ByteArray, offset: Int) {
        rtcpHeader.apply {
            packetType = PayloadSpecificRtcpFbPacket.PT
            reportCount = RtcpFbRembPacket.FMT
            length = RtpUtils.calculateRtcpLengthFieldValue(sizeBytes)
        }
        rtcpHeader.writeTo(buf, offset)
        RtcpFbPacket.setMediaSourceSsrc(buf, offset, 0)
        RtcpFbRembPacket.setRemb(buf, offset)
        RtcpFbRembPacket.setNumSsrc(buf, offset, ssrcs.size)

        val expAndMantissa = getExpAndMantissa(brBps)
        RtcpFbRembPacket.setBrExp(buf, offset, expAndMantissa.first)
        RtcpFbRembPacket.setBrMantissa(buf, offset, expAndMantissa.second)
        RtcpFbRembPacket.setSsrcs(buf, offset, ssrcs)
    }
}
