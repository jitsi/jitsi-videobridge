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
@file:Suppress("ktlint:standard:property-naming")

package org.jitsi.nlj.rtp.bandwidthestimation2

import org.jitsi.nlj.util.DataSize
import org.jitsi.nlj.util.NEVER
import java.time.Instant

/** Common network types used for bandwidth estimation,
 * based on WebRTC api/transport/network_types.{h,cc} in
 * WebRTC 8284f2b4e8670529d039a8b6c73ec5f1d760bd21.
 */

/**
 * Information about a paced packet
 */
data class PacedPacketInfo(
    val probeClusterId: Int = kNotAProbe,
    val probeClusterMinProbes: Int = -1,
    val probeClusterMinBytes: Int = -1
) {
    // TODO(srte): Move probing info to a separate, optional struct.
    var sendBitrateBps = -1
    var probeClusterBytesSent = 0

    override operator fun equals(other: Any?): Boolean {
        if (other !is PacedPacketInfo) {
            return false
        }
        return sendBitrateBps == other.sendBitrateBps &&
            probeClusterId == other.probeClusterId &&
            probeClusterMinProbes == other.probeClusterMinProbes &&
            probeClusterMinBytes == other.probeClusterMinBytes
    }

    companion object {
        const val kNotAProbe = -1
    }
}

/**
 * A sent packet
 */
data class SentPacket(
    var sendTime: Instant = NEVER,
    /** Size of packet with overhead up to IP layer. */
    var size: DataSize = DataSize.ZERO,
    /** Size of preceeding packets that are not part of feedback */
    var priorUnackedData: DataSize = DataSize.ZERO,
    /** Probe cluster id and parameters including bitrate, number of packets and
     number of bytes. */
    var pacingInfo: PacedPacketInfo = PacedPacketInfo(),
    /** True if the packet is an audio packet, false for video, padding, RTX, etc. */
    var audio: Boolean = false,
    /** Transport independent sequence number, any tracked packet should have a
     sequence number that is unique over the whole call and increasing by 1 for
     each packet. */
    var sequenceNumber: Long = 0,
    /** Tracked data in flight when the packet was sent, excluding unacked data. */
    var dataInFlight: DataSize = DataSize.ZERO
)

/**
 * The result of packet feedback
 */
class PacketResult {
    var sentPacket = SentPacket()

    var receiveTime: Instant = NEVER

    fun isReceived() = receiveTime != NEVER
}

/**
 * Summary of transport packets feedback
 */
class TransportPacketsFeedback {
    var feedbackTime: Instant = NEVER
    var firstUnackedSendTime: Instant = NEVER
    var dataInFlight: DataSize = DataSize.ZERO
    var priorInFlight: DataSize = DataSize.ZERO
    var packetFeedbacks: MutableList<PacketResult> = ArrayList()

    /** Arrival times for messages without send times information */
    val sendlessArrivalTimes = ArrayList<Instant>()

    fun receivedWithSendInfo(): List<PacketResult> {
        return packetFeedbacks.filter { it.isReceived() }
    }

    fun lostWithSendInfo(): List<PacketResult> {
        return packetFeedbacks.filterNot { it.isReceived() }
    }

    fun packetsWithFeedback(): List<PacketResult> {
        return packetFeedbacks
    }

    fun sortedByReceiveTime(): List<PacketResult> {
        return receivedWithSendInfo().sortedBy { it.receiveTime }
    }
}
