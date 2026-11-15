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

import org.jitsi.nlj.util.RtpSequenceIndexTracker
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.ccfb.EcnMarking
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.ccfb.PacketInfo
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.ccfb.ReceivedPacketInfo
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.ccfb.UnreceivedPacketInfo
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import java.time.Duration
import java.time.Instant

private const val kMaxPacketsPerSsrc = 16384
private const val kMaxPacketsToKeepForReorderCalculation = 64

/**
 * Stats computed from sent RFC 8888 reports.
 * https://github.com/w3c/webrtc-stats/blob/refs/heads/main/l4s-explainer.md#suggested-counters-and-where-to-attach-them
 */
data class SentCongestionControllerFeedbackStats(
    /** Total number of packets reported as lost in a produced feedback. */
    var numPacketsReportedLost: Long = 0,
    /**
     * Total number of packets reported first as lost in a produced feedback, but that were also reported as
     * received in a later feedback.
     */
    var numPacketsReportedRecovered: Long = 0
)

/**
 * CongestionControlFeedbackTracker is responsible for creating and keeping track of feedback sent for a specific
 * SSRC when feedback is sent according to https://datatracker.ietf.org/doc/rfc8888/
 *
 * Based on WebRTC modules/remote_bitrate_estimator/congestion_control_feedback_tracker.{h,cc} in
 * WebRTC 3987dfb7b9 (October 2026), modified to use Jitsi types for objects outside the tracker.
 */
class CongestionControlFeedbackTracker(
    private val ssrc: Long,
    parentLogger: Logger
) {
    private val logger = createChildLogger(parentLogger)

    private class TrackedPacket {
        fun received(): Boolean = arrivalTime != null

        var arrivalTime: Instant? = null
        var ecn: EcnMarking = EcnMarking.kNotEct

        // Indicates if packet was reported as lost last time it was reported in a
        // `addPacketsToFeedback`.
        var lastReportedAsLost = false
    }

    private val unwrapper = RtpSequenceIndexTracker()

    // Contains info relevant for producing feedback for a received or missed RTP
    // packet. Entry with index `i` represent information about packet with
    // RTP sequence number `firstSequenceNumberInPackets + i`
    private val packets = ArrayList<TrackedPacket>()

    // Unwrapped RTP sequence number of the first element in `packets`.
    // Mustn't be used when `packets.isEmpty()`
    private var firstSequenceNumberInPackets = -1L

    // Unwrapped RTP sequence number of a packet to start next feedback with.
    // Mustn't be used when `packets.isEmpty()`
    private var nextSequenceNumberInFeedback = -1L

    // Number of packets discarded by `receivedPacket` function since last call
    // to `addPacketsToFeedback`.
    private var numIgnoredPacketsSinceLastFeedback = 0

    private val stats = SentCongestionControllerFeedbackStats()

    fun getStats(): SentCongestionControllerFeedbackStats = stats.copy()

    private fun resize(newSize: Int) {
        while (packets.size < newSize) {
            packets.add(TrackedPacket())
        }
    }

    // Returns a `TrackedPacket` entry for `sequenceNumber`,
    // Returns null if an entry can't be allocated due to `sequenceNumber`
    // being too much out of order of already stored packet infos.
    private fun findOrCreatePacketInfo(sequenceNumber: Long): TrackedPacket? {
        if (packets.isEmpty()) {
            // First packet ever, or sequence number reset.
            // To avoid special logic to distinguish out of order packets at the start
            // of the call, and too old packets in the middle of a call, ensure
            // `packets.size` is always >= `kMaxPacketsToKeepForReorderCalculation`
            // once at least one packet is received.
            resize(kMaxPacketsToKeepForReorderCalculation)
            firstSequenceNumberInPackets = sequenceNumber - kMaxPacketsToKeepForReorderCalculation + 1
            nextSequenceNumberInFeedback = sequenceNumber
            return packets.last()
        }
        if (sequenceNumber < firstSequenceNumberInPackets) {
            logger.debug {
                "Received too old packet ssrc:$ssrc seq:$sequenceNumber. " +
                    "Expected seq >= $firstSequenceNumberInPackets. Ignoring the packet."
            }
            return null
        }
        if (sequenceNumber >= firstSequenceNumberInPackets + packets.size) {
            val newSize = sequenceNumber - firstSequenceNumberInPackets + 1
            if (newSize > kMaxPacketsPerSsrc) {
                logger.debug {
                    "Received too new packet ssrc:$ssrc seq:$sequenceNumber that would increase number of packet " +
                        "to report to $newSize from current ${packets.size}, " +
                        "first seq:$firstSequenceNumberInPackets. Ignoring the packet."
                }
                return null
            }
            resize(newSize.toInt())
            return packets.last()
        }
        // `TrackedPacket` for `sequenceNumber` already exists.
        return packets[(sequenceNumber - firstSequenceNumberInPackets).toInt()]
    }

    fun receivedPacket(rtpSequenceNumber: Int, arrivalTime: Instant, ecn: EcnMarking) {
        val sequenceNumber = unwrapper.update(rtpSequenceNumber)
        val info = findOrCreatePacketInfo(sequenceNumber)
        if (info == null) {
            ++numIgnoredPacketsSinceLastFeedback
            return
        }
        if (info.received()) {
            // Duplicate copy of an RTP packet is received. According to RFC8888:
            // arrival time of the first copy to arrive MUST be reported.
            // If any of the copies of the duplicated packet are ECN-CE marked, then an
            // ECN-CE mark MUST be reported for that packet; otherwise, the ECN mark of
            // the first copy to arrive is reported.
            if (ecn == EcnMarking.kCe && info.ecn != EcnMarking.kCe) {
                info.ecn = EcnMarking.kCe
            } else {
                // No information to report is updated for this packet, so no need to
                // try to report it in the next feedback message.
                return
            }
        } else {
            // Packet was received for the 1st time.
            info.arrivalTime = arrivalTime
            info.ecn = ecn
        }

        // Newly received packet, or new information about an old packet - ensure such
        // new information is included in the next report.
        if (sequenceNumber < nextSequenceNumberInFeedback) {
            logger.debug {
                "Received packet unordered between feedback. SSRC: $ssrc Seq: $rtpSequenceNumber " +
                    "last feedback: $nextSequenceNumberInFeedback"
            }
            nextSequenceNumberInFeedback = sequenceNumber
        }
    }

    // Adds received packets to `packetFeedback`
    // RTP sequence numbers are continuous from the last created feedback unless
    // reordering has occurred between feedback packets. If so, the sequence
    // number range may overlap with previously sent feedback.
    fun addPacketsToFeedback(feedbackTime: Instant, packetFeedback: MutableList<PacketInfo>) {
        if (packets.isEmpty()) {
            // No packets received since last reset.
            return
        }
        check(nextSequenceNumberInFeedback >= firstSequenceNumberInPackets)
        check(nextSequenceNumberInFeedback <= firstSequenceNumberInPackets + packets.size)

        if (nextSequenceNumberInFeedback == firstSequenceNumberInPackets + packets.size) {
            // No packets to report received since last produced feedback.
            if (numIgnoredPacketsSinceLastFeedback > 0) {
                // There were packets received, but all of them were discarded due to
                // reorder. That likely indicates sequence number reset. Reset the state
                // so that next feedback could be produced.
                logger.warn {
                    "$numIgnoredPacketsSinceLastFeedback received packets were discarded while no packets were " +
                        "accepted to produce feedback for SSRC: $ssrc. Assuming sequence numbers were reset, " +
                        "reset state and next sequence number in feedback from $nextSequenceNumberInFeedback"
                }
                // Clear packets, rest of the state will be reset on first packet
                // arrived after that.
                packets.clear()
                numIgnoredPacketsSinceLastFeedback = 0
            }
            return
        }
        numIgnoredPacketsSinceLastFeedback = 0

        var rtpSequenceNumber = (nextSequenceNumberInFeedback and 0xFFFF).toInt()
        for (i in (nextSequenceNumberInFeedback - firstSequenceNumberInPackets).toInt() until packets.size) {
            val info = packets[i]
            val arrivalTime = info.arrivalTime
            if (arrivalTime == null && !info.lastReportedAsLost) {
                ++stats.numPacketsReportedLost
                info.lastReportedAsLost = true
            }
            if (arrivalTime != null && info.lastReportedAsLost) {
                ++stats.numPacketsReportedRecovered
                info.lastReportedAsLost = false
            }
            packetFeedback.add(
                if (arrivalTime != null) {
                    ReceivedPacketInfo(
                        ssrc = ssrc,
                        sequenceNumber = rtpSequenceNumber,
                        arrivalTimeOffset = Duration.between(arrivalTime, feedbackTime),
                        ecn = info.ecn
                    )
                } else {
                    UnreceivedPacketInfo(ssrc = ssrc, sequenceNumber = rtpSequenceNumber)
                }
            )
            rtpSequenceNumber = (rtpSequenceNumber + 1) and 0xFFFF
        }
        nextSequenceNumberInFeedback = firstSequenceNumberInPackets + packets.size

        // Reduce `packets` to store just `kMaxPacketsToKeepForReorderCalculation` latest
        // entries.
        val numElementsToErase = packets.size - kMaxPacketsToKeepForReorderCalculation
        firstSequenceNumberInPackets += numElementsToErase
        packets.subList(0, numElementsToErase).clear()
    }
}
