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
@file:Suppress("ktlint:standard:enum-entry-name-case")

package org.jitsi.nlj.rtp.pacing

import org.jitsi.nlj.PacketInfo
import java.time.Duration

/** The kind of a packet that is given to the pacer,
 * based on RtpPacketMediaType in WebRTC modules/rtp_rtcp/include/rtp_rtcp_defines.h in
 * WebRTC commit 42a497714596d8f726da4dae4fb4f133cbaa15df (main, 2026-11-15).
 */
enum class RtpPacketMediaType {
    kAudio, // Audio media packets.
    kVideo, // Video media packets.
    kRetransmission, // Retransmisions, sent as response to NACK.
    kForwardErrorCorrection, // FEC packets.
    kPadding; // RTX or plain padding sent to maintain BWE.

    companion object {
        val kNumMediaTypes = entries.size
    }
}

/**
 * Jitsi local: a packet as the pacer sees it. This class stands in for RtpPacketToSend in WebRTC
 * (modules/rtp_rtcp/source/rtp_packet_to_send.h), and has the members of it that the code in modules/pacing uses.
 *
 * The pacer does not read or change the packet itself, which is [packetInfo].
 */
class RtpPacketToSend(
    val packetType: RtpPacketMediaType,
    val ssrc: Long,
    val sequenceNumber: Int,
    /** The size of the RTP header with its extensions, in bytes. */
    val headersSize: Int,
    /** The size of the payload without padding, in bytes. */
    val payloadSize: Int,
    /** The size of the padding, in bytes. */
    val paddingSize: Int = 0,
    /**
     * The type that a packet of type [RtpPacketMediaType.kRetransmission] had before it was retransmitted. In WebRTC
     * the original type does not change when the packet type is changed to kRetransmission.
     */
    val originalPacketType: OriginalType? = null,
    val isKeyFrame: Boolean = false,
    val isFirstPacketOfFrame: Boolean = false,
    /** The packet that is sent. */
    val packetInfo: PacketInfo? = null
) {
    enum class OriginalType { kAudio, kVideo }

    /** The size of the whole packet, in bytes. */
    fun size(): Int = headersSize + payloadSize + paddingSize

    var allowSending: Boolean = true

    var timeInSendQueue: Duration? = null
}
