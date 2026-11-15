/*
 * Copyright @ 2026 - present 8x8, Inc.
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

package org.jitsi.nlj.rtp.ccfb

import org.jitsi.nlj.util.Bandwidth
import org.jitsi.nlj.util.DataSize
import org.jitsi.nlj.util.bytes
import org.jitsi.nlj.util.div
import org.jitsi.nlj.util.kbps
import org.jitsi.nlj.util.times
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.ccfb.EcnMarking
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.ccfb.PacketInfo
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.ccfb.RtcpFbCcfbPacket
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.ccfb.RtcpFbCcfbPacketBuilder
import org.jitsi.utils.NEVER
import org.jitsi.utils.TimeUtils
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.max
import org.jitsi.utils.ms
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.TreeMap

private val kMaxFeedbackRate = 500.kbps
private val kTransportOverhead = 42.bytes

/**
 * The class is responsible for generating RTCP feedback packets based on
 * incoming media packets. Feedback format will comply with RFC 8888.
 * https://datatracker.ietf.org/doc/rfc8888/
 *
 * Min and max duration between feedback is configurable, but per default, min is 25ms and max is 250ms.
 *
 * RTCP should not use more than 5% of the uplink link capacity.
 * However, there is no good way for a feedback sender to know the
 * link capacity unless media is sent in both directions. By default, we assume
 * that the link capacity is 10 Mbit/s or more and allow sending 500 kbit/s of
 * feedback packets. This allows an approximate receive rate of 200
 * Mbit/s with feedback every 25ms. (200 Mbit/s with average size of 800 bytes =
 * 31250 packets/s => 40 feedback packets/s with feedback of 780 packets each)
 *
 * The feedback capacity limit can be configured to be a fraction of the send
 * BWE when the link is bandwidth limited, using [maxFeedbackFraction].
 *
 * If possible, given the other constraints, feedback will be sent when a packet
 * with marker bit is received in order to provide feedback as soon as possible
 * after receiving a complete video frame. If no packet with marker bit is
 * received, feedback can be delayed up to 25ms after the first packet since the
 * last sent feedback. On good networks, this means that a sender may receive
 * feedback for every sent frame.
 *
 * Based on WebRTC modules/remote_bitrate_estimator/congestion_control_feedback_generator.{h,cc} in
 * WebRTC 3987dfb7b9 (October 2026), modified to use Jitsi types for objects outside the generator, and taking the
 * parameters WebRTC reads from the WebRTC-RFC8888CongestionControlFeedback field trial as constructor arguments.
 *
 * Like the original, this class is not thread safe.
 */
class CongestionControlFeedbackGenerator(
    private val clock: Clock,
    private val parentLogger: Logger,
    private val minTimeBetweenFeedback: Duration = 25.ms,
    private val maxFeedbackFraction: Double = 0.0,
    private val maxTimeToWaitForPacketWithMarker: Duration = 25.ms,
    private val maxTimeBetweenFeedback: Duration = 250.ms,
    private val rtcpSender: (RtcpFbCcfbPacket) -> Unit
) {
    private var sendBandwidthEstimate: Bandwidth? = null
    private var isBandwidthLimited = true
    private var transportOverhead: DataSize? = null

    private var sendRateDebt = 0.bytes

    private val feedbackTrackers = TreeMap<Long, CongestionControlFeedbackTracker>()

    private var lastFeedbackSentTime = NEVER
    private var firstArrivalTimeSinceFeedback: Instant? = null
    private var markerBitSeen = false
    private var nextPossibleFeedbackSendTime = Instant.EPOCH

    fun onSendBandwidthEstimateChanged(estimate: Bandwidth, isBandwidthLimited: Boolean, transportOverhead: DataSize?) {
        sendBandwidthEstimate = estimate
        this.isBandwidthLimited = isBandwidthLimited
        this.transportOverhead = transportOverhead
    }

    fun onReceivedPacket(ssrc: Long, rtpSequenceNumber: Int, marker: Boolean, arrivalTime: Instant, ecn: EcnMarking) {
        markerBitSeen = markerBitSeen || marker
        val now = clock.instant()
        if (firstArrivalTimeSinceFeedback == null) {
            firstArrivalTimeSinceFeedback = now
        }
        feedbackTrackers.computeIfAbsent(ssrc) { CongestionControlFeedbackTracker(ssrc, parentLogger) }
            .receivedPacket(rtpSequenceNumber, arrivalTime, ecn)
        if (nextFeedbackTime() < now) {
            sendFeedback(now)
        }
    }

    private fun nextFeedbackTime(): Instant {
        val firstArrivalTimeSinceFeedback = this.firstArrivalTimeSinceFeedback
        if (firstArrivalTimeSinceFeedback == null) {
            return max(clock.instant() + minTimeBetweenFeedback, nextPossibleFeedbackSendTime)
        }

        if (!markerBitSeen) {
            return max(
                nextPossibleFeedbackSendTime,
                firstArrivalTimeSinceFeedback + maxTimeToWaitForPacketWithMarker
            )
        }
        return nextPossibleFeedbackSendTime
    }

    /**
     * Sends feedback if it is due.
     *
     * @return the time until this should be called again.
     */
    fun process(now: Instant): Duration {
        if (nextFeedbackTime() <= now) {
            sendFeedback(now)
        }
        return Duration.between(now, nextFeedbackTime())
    }

    /**
     * Jitsi local: whether packets have been received since feedback was last sent, i.e. whether [process] needs to
     * be called for feedback to be sent even if no more packets arrive.
     */
    fun hasUnreportedPackets(): Boolean = firstArrivalTimeSinceFeedback != null

    private fun sendFeedback(now: Instant) {
        check(now >= nextPossibleFeedbackSendTime)
        val compactNtp = TimeUtils.toNtpShortFormat(TimeUtils.toNtpTime(now.toEpochMilli()))
        val rtcpPacketInfo = ArrayList<PacketInfo>()
        for (tracker in feedbackTrackers.values) {
            tracker.addPacketsToFeedback(now, rtcpPacketInfo)
        }
        markerBitSeen = false
        firstArrivalTimeSinceFeedback = null

        val feedback = RtcpFbCcfbPacketBuilder(packets = rtcpPacketInfo, reportTimestampCompactNtp = compactNtp).build()
        val overhead = transportOverhead ?: kTransportOverhead
        calculateNextPossibleSendTime(feedback.length.bytes + overhead, now)

        rtcpSender(feedback)
    }

    private fun calculateNextPossibleSendTime(feedbackSize: DataSize, now: Instant) {
        val timeSinceLastSent = if (lastFeedbackSentTime != NEVER) {
            Duration.between(lastFeedbackSentTime, now)
        } else {
            Duration.ZERO
        }
        var maxFeedbackRate = kMaxFeedbackRate
        val sendBandwidthEstimate = this.sendBandwidthEstimate
        if (isBandwidthLimited && maxFeedbackFraction > 0.0 &&
            sendBandwidthEstimate != null && sendBandwidthEstimate.bps > 0
        ) {
            maxFeedbackRate = sendBandwidthEstimate * maxFeedbackFraction
        }
        val debtPayed = maxFeedbackRate * timeSinceLastSent
        sendRateDebt = if (debtPayed > sendRateDebt) 0.bytes else sendRateDebt - debtPayed
        sendRateDebt += feedbackSize
        lastFeedbackSentTime = now
        nextPossibleFeedbackSendTime =
            now + (sendRateDebt / maxFeedbackRate).coerceIn(minTimeBetweenFeedback, maxTimeBetweenFeedback)
    }

    fun getStatsPerSsrc(): Map<Long, SentCongestionControllerFeedbackStats> =
        feedbackTrackers.mapValues { it.value.getStats() }

    /** Jitsi local: the stats of all SSRCs added up. */
    fun getTotalStats(): SentCongestionControllerFeedbackStats {
        val total = SentCongestionControllerFeedbackStats()
        for (tracker in feedbackTrackers.values) {
            val stats = tracker.getStats()
            total.numPacketsReportedLost += stats.numPacketsReportedLost
            total.numPacketsReportedRecovered += stats.numPacketsReportedRecovered
        }
        return total
    }
}
