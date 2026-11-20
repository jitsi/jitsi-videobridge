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
package org.jitsi.nlj.rtp.scream

import org.jitsi.nlj.rtp.bandwidthestimation2.PacketResult
import org.jitsi.nlj.rtp.bandwidthestimation2.TransportPacketsFeedback
import org.jitsi.nlj.util.DataSize
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.ccfb.EcnMarking
import org.jitsi.utils.MAX_DURATION
import org.jitsi.utils.MIN_DURATION
import org.jitsi.utils.NEVER
import org.jitsi.utils.isFinite
import org.jitsi.utils.max
import org.jitsi.utils.min
import java.time.Duration
import java.time.Instant

/** Scream feedback,
 * based on WebRTC modules/congestion_controller/scream/scream_feedback.{h,cc} in
 * WebRTC commit 42a497714596d8f726da4dae4fb4f133cbaa15df (main, 2026-11-15).
 */
class ScreamFeedback {
    var feedbackTime: Instant = NEVER
    var dataInFlight: DataSize = DataSize.ZERO

    // General feedback metrics.
    var numReceivedPackets = 0
    var numCeMarkedPackets = 0
    var numLostPackets = 0
    var numRecoveredPackets = 0
    var received: DataSize = DataSize.ZERO

    // Sum of the sizes of all packets in the feedback that are NOT ECN CE-marked.
    // This explicitly includes packets reported as lost (since lost packets do
    // not have ECN marking).
    var ackedNotMarkedSize: DataSize = DataSize.ZERO

    // Metrics that depend on at least one packet with an unambiguous receive
    // time. All fields are guaranteed to be valid and finite when present.
    data class DelayMetrics(
        // Aggregated delay & RTT metrics calculated over the recent burst / tail
        // window.
        val minOneWayDelay: Duration,
        val maxOneWayDelay: Duration,
        // The duration between the receive times of the first and last packets in
        // this feedback, including the last packet's receiver delay
        // (arrival_time_offset).
        val feedbackHoldTime: Duration,
        // The calculated RTT sample of this feedback.
        val rttSample: Duration,
        // Receive timestamp of the latest packet received in this feedback.
        val lastPacketReceiveTime: Instant
    )

    var delayMetrics: DelayMetrics? = null
}

// Free function helper to convert original feedback into the flat struct.
fun parseScreamFeedback(
    msg: TransportPacketsFeedback,
    params: ScreamV2Parameters = ScreamV2Parameters()
): ScreamFeedback {
    check(params.burstWindowMin <= params.burstWindowMax)

    val parsed = ScreamFeedback()
    parsed.feedbackTime = msg.feedbackTime
    parsed.dataInFlight = msg.dataInFlight

    var firstPacket: PacketResult? = null
    var lastPacket: PacketResult? = null
    val order = PacketResult.receiveTimeOrder

    for (packet in msg.packetFeedbacks) {
        // Sum size of all packets in feedback that are not CE marked (includes
        // lost).
        if (packet.ecn != EcnMarking.kCe) {
            parsed.ackedNotMarkedSize += packet.sentPacket.size
        }

        // Record loss and recovery events.
        val isLost = !packet.isReceived() && packet.reportedLostForTheFirstTime
        val isRecovered = packet.reportedRecoveredForTheFirstTime
        if (isLost) {
            parsed.numLostPackets++
        } else if (isRecovered) {
            parsed.numRecoveredPackets++
        }

        // Update received-packet metrics.
        if (packet.isReceived()) {
            parsed.received += packet.sentPacket.size
            parsed.numReceivedPackets++
            if (packet.ecn == EcnMarking.kCe) {
                parsed.numCeMarkedPackets++
            }

            // Replicate exact ReceiveTimeOrder tie-breaking logic to find first &
            // last packets among packets with unambiguous receive times.
            if (!packet.ambiguousReceiveTime) {
                if (firstPacket == null || order.compare(packet, firstPacket) < 0) {
                    firstPacket = packet
                }
                if (lastPacket == null || order.compare(lastPacket, packet) < 0) {
                    lastPacket = packet
                }
            }
        }
    }

    // Calculate min and max delay for packets sent within a window of the
    // latest received packet. Feedback is typically received every ~25ms, but
    // can span significantly longer (e.g. 100-250ms) at low packet rates,
    // such as audio-only or application-limited periods. Earlier packets in
    // such sparse feedback are excluded to prevent natural route jitter over
    // long intervals from being falsely interpreted as burst queueing delay,
    // and to avoid lag in queue delay estimation.
    var latestSendTime = NEVER
    var newerSendTime = NEVER
    var minOneWayDelay = MAX_DURATION
    var maxOneWayDelay = MIN_DURATION

    for (packet in msg.packetFeedbacks.asReversed()) {
        if (!packet.isReceived() || packet.ambiguousReceiveTime) {
            continue
        }

        if (!latestSendTime.isFinite()) {
            latestSendTime = packet.sentPacket.sendTime
            newerSendTime = packet.sentPacket.sendTime
        }

        val deltaFromLatest = Duration.between(packet.sentPacket.sendTime, latestSendTime)
        val gap = Duration.between(packet.sentPacket.sendTime, newerSendTime)

        if (deltaFromLatest <= params.burstWindowMin ||
            (deltaFromLatest <= params.burstWindowMax && gap <= params.burstWindowMaxGap)
        ) {
            val oneWayDelay = Duration.between(packet.sentPacket.sendTime, packet.receiveTime)
            minOneWayDelay = min(minOneWayDelay, oneWayDelay)
            maxOneWayDelay = max(maxOneWayDelay, oneWayDelay)
            newerSendTime = packet.sentPacket.sendTime
        } else {
            break
        }
    }

    if (lastPacket != null) {
        check(firstPacket != null)
        check(minOneWayDelay.isFinite())
        check(maxOneWayDelay.isFinite())
        val lastArrivalTimeOffset = lastPacket.arrivalTimeOffset ?: Duration.ZERO
        val feedbackHoldTime =
            Duration.between(firstPacket.receiveTime, lastPacket.receiveTime + lastArrivalTimeOffset)
        val rttSample = max(
            Duration.between(lastPacket.sentPacket.sendTime, msg.feedbackTime) - lastArrivalTimeOffset,
            Duration.ZERO
        )
        parsed.delayMetrics = ScreamFeedback.DelayMetrics(
            minOneWayDelay = minOneWayDelay,
            maxOneWayDelay = maxOneWayDelay,
            feedbackHoldTime = feedbackHoldTime,
            rttSample = rttSample,
            lastPacketReceiveTime = lastPacket.receiveTime
        )
    }

    return parsed
}
