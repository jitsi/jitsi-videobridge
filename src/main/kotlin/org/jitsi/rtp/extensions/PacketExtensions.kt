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
package org.jitsi.rtp.extensions

import org.jitsi.rtp.Packet
import org.jitsi.rtp.extensions.unsigned.toPositiveInt
import org.jitsi.rtp.rtcp.RtcpHeader
import org.jitsi.rtp.rtp.RtpHeader

/**
 * "The process for demultiplexing a packet is as follows.  The receiver
 * looks at the first byte of the packet."
 *
 * +----------------+
 * |        [0..3] -+--> forward to STUN
 * |                |
 * |      [16..19] -+--> forward to ZRTP
 * |                |
 * |      [20..63] -+--> forward to DTLS
 * |                |
 * |      [64..79] -+--> forward to TURN Channel
 * |                |
 * |    [128..191] -+--> forward to RTP/RTCP
 * +----------------+
 *
 * See [https://tools.ietf.org/html/rfc7983#section-7]
 *
 *
 * RTP/RTCP are further demultiplexed based on the packet type (second byte)
 *
 * Note: Kotlin's ranges are implemented efficiently.
 */
private val RTCP_PACKET_TYPE_RANGE = 200..211
private val DTLS_RANGE = 20..63
private val RTP_RTCP_RANGE = 128..191

fun Packet.looksLikeRtp(): Boolean {
    if (length < RtpHeader.FIXED_HEADER_SIZE_BYTES) {
        return false
    }

    val b0 = buffer[offset].toPositiveInt()
    val b1 = buffer[offset + 1].toPositiveInt()
    return b0 in RTP_RTCP_RANGE && b1 !in RTCP_PACKET_TYPE_RANGE
}

fun Packet.looksLikeRtcp(): Boolean {
    if (length < RtcpHeader.SIZE_BYTES) {
        return false
    }
    val b0 = buffer[offset].toPositiveInt()
    val b1 = buffer[offset + 1].toPositiveInt()
    return b0 in RTP_RTCP_RANGE && b1 in RTCP_PACKET_TYPE_RANGE
}

fun Packet.looksLikeDtls(): Boolean {
    if (length < 1) {
        return false
    }
    return buffer[offset].toPositiveInt() in DTLS_RANGE
}
