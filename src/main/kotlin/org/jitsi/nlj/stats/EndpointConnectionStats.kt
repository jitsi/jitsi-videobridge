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

package org.jitsi.nlj.stats

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import org.jitsi.nlj.rtcp.RtcpListener
import org.jitsi.utils.OrderedJsonObject
import org.jitsi.utils.logging2.cdebug
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.nlj.util.toDoubleMillis
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.rtp.rtcp.RtcpReportBlock
import org.jitsi.rtp.rtcp.RtcpRrPacket
import org.jitsi.rtp.rtcp.RtcpSrPacket
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.RtcpFbTccPacket
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.UnreceivedPacketReport
import org.jitsi.utils.LRUCache
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.secs
import org.jitsi.utils.stats.RateTracker

/**
 * The maximum number of SR packets and their timestamps to save.
 */
private const val MAX_SR_TIMESTAMP_HISTORY = 200

private data class SsrcAndTimestamp(val ssrc: Long, val timestamp: Long)
/**
 * Tracks stats which are not necessarily tied to send or receive but the endpoint overall
 */
class EndpointConnectionStats(
    parentLogger: Logger,
    private val clock: Clock = Clock.systemUTC()
) : RtcpListener {
    interface EndpointConnectionStatsListener {
        fun onRttUpdate(newRttMs: Double)
    }
    data class Snapshot(
        val rtt: Double,
        val incomingLossStats: LossStatsSnapshot,
        val outgoingLossStats: LossStatsSnapshot
    ) {
        fun toJson() = OrderedJsonObject().apply {
            put("rtt", rtt)
            put("incoming_loss_stats", incomingLossStats.toJson())
            put("outgoing_loss_stats", outgoingLossStats.toJson())
        }
    }

    data class LossStatsSnapshot(
        val packetsLost: Long,
        val packetsReceived: Long
    ) {
        fun toJson() = OrderedJsonObject().apply {
            put("packets_lost", packetsLost)
            put("packets_received", packetsReceived)
        }
    }

    private val endpointConnectionStatsListeners: MutableList<EndpointConnectionStatsListener> = CopyOnWriteArrayList()

    // Per-SSRC, maps the compacted NTP timestamp found in an SR SenderInfo to
    //  the clock time at which it was transmitted
    private val srSentTimes: MutableMap<SsrcAndTimestamp, Instant> =
        Collections.synchronizedMap(LRUCache(MAX_SR_TIMESTAMP_HISTORY))
    private val logger = createChildLogger(parentLogger)

    private val lock = Object()
    /**
     * The calculated RTT, in milliseconds, between the bridge and the endpoint
     */
    private var rtt: Double = 0.0

    private val incomingLossTracker = LossTracker()
    private val outgoingLossTracker = LossTracker()

    fun addListener(listener: EndpointConnectionStatsListener) {
        endpointConnectionStatsListeners.add(listener)
    }

    fun getSnapshot(): Snapshot {
        return synchronized(lock) {
            Snapshot(
                rtt = rtt,
                incomingLossStats = LossStatsSnapshot(
                    packetsLost = incomingLossTracker.lostPackets.getAccumulatedCount(),
                    packetsReceived = incomingLossTracker.receivedPackets.getAccumulatedCount()
                ),
                outgoingLossStats = LossStatsSnapshot(
                    packetsLost = outgoingLossTracker.lostPackets.getAccumulatedCount(),
                    packetsReceived = outgoingLossTracker.receivedPackets.getAccumulatedCount()
                )
            )
        }
    }

    // TODO: change this flow to pass Instant instead of Long
    override fun rtcpPacketReceived(packet: RtcpPacket, receivedTime: Long) {
        val receivedInstant = Instant.ofEpochMilli(receivedTime)
        when (packet) {
            is RtcpSrPacket -> {
                logger.cdebug { "Received SR packet with ${packet.reportBlocks.size} report blocks" }
                packet.reportBlocks.forEach { reportBlock -> processReportBlock(receivedInstant, reportBlock) }
            }
            is RtcpRrPacket -> {
                logger.cdebug { "Received RR packet with ${packet.reportBlocks.size} report blocks" }
                packet.reportBlocks.forEach { reportBlock -> processReportBlock(receivedInstant, reportBlock) }
            }
            // Received TCC feedback reports loss on packets we *sent*
            is RtcpFbTccPacket -> processTcc(packet, outgoingLossTracker)
        }
    }

    override fun rtcpPacketSent(packet: RtcpPacket) {
        when (packet) {
            is RtcpSrPacket -> {
                logger.cdebug {
                    "Tracking sent SR packet with compacted timestamp " +
                        "${packet.senderInfo.compactedNtpTimestamp}"
                }
                val entry = SsrcAndTimestamp(packet.senderSsrc, packet.senderInfo.compactedNtpTimestamp)
                srSentTimes[entry] = clock.instant()
            }
            // Sent TCC feedback reports loss on packets we *received*
            is RtcpFbTccPacket -> processTcc(packet, incomingLossTracker)
        }
    }

    private fun processReportBlock(receivedTime: Instant, reportBlock: RtcpReportBlock) = synchronized(lock) {
        if (reportBlock.lastSrTimestamp == 0L && reportBlock.delaySinceLastSr == 0L) {
            logger.cdebug {
                "Report block for ssrc ${reportBlock.ssrc} didn't have SR data: " +
                    "lastSrTimestamp was ${reportBlock.lastSrTimestamp}, " +
                    "delaySinceLastSr was ${reportBlock.delaySinceLastSr}"
            }
            return
        }
        // We need to know when we sent the last SR
        srSentTimes[SsrcAndTimestamp(reportBlock.ssrc, reportBlock.lastSrTimestamp)]?.let { srSentTime ->
            // The delaySinceLastSr value is given in 1/65536ths of a second, so divide it by .000065536 to get it
            // in nanoseconds
            val remoteProcessingDelay = Duration.ofNanos((reportBlock.delaySinceLastSr / .000065536).toLong())
            rtt = (Duration.between(srSentTime, receivedTime) - remoteProcessingDelay).toDoubleMillis()
            if (rtt > 7.secs.toMillis()) {
                logger.warn(
                    "Suspiciously high rtt value: $rtt ms, remote processing delay was " +
                        "$remoteProcessingDelay (${reportBlock.delaySinceLastSr}), srSentTime was $srSentTime, " +
                        "received time was $receivedTime"
                )
            } else if (rtt < -1.0) {
                // Allow some small slop here, since receivedTime and srSentTime are only accurate to the nearest
                // millisecond.
                logger.warn(
                    "Negative rtt value: $rtt ms, remote processing delay was " +
                        "$remoteProcessingDelay (${reportBlock.delaySinceLastSr}), srSentTime was $srSentTime, " +
                        "received time was $receivedTime"
                )
            }
            endpointConnectionStatsListeners.forEach { it.onRttUpdate(rtt) }
        } ?: run {
            logger.cdebug {
                "No sent SR found for SSRC ${reportBlock.ssrc} and SR " +
                    "timestamp ${reportBlock.lastSrTimestamp}"
            }
        }
    }

    private fun processTcc(tccPacket: RtcpFbTccPacket, lossTracker: LossTracker) = synchronized(lock) {
        var lost = 0L
        var received = 0L
        for (packetReport in tccPacket) {
            when (packetReport) {
                is UnreceivedPacketReport -> lost++
                else -> received++
            }
        }
        lossTracker.lostPackets.update(lost)
        lossTracker.receivedPackets.update(received)
    }

    private class LossTracker {
        val lostPackets = RateTracker(60.secs, 1.secs)
        val receivedPackets = RateTracker(60.secs, 1.secs)
    }
}
