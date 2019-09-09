/*
 * Copyright @ 2018 - Present, 8x8 Inc
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
package org.jitsi.nlj.transform.node.incoming

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.TreeMap
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.rtp.RtpExtensionType.TRANSPORT_CC
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.node.ObserverNode
import org.jitsi.nlj.util.NEVER
import org.jitsi.nlj.util.ReadOnlyStreamInformationStore
import org.jitsi.nlj.util.cdebug
import org.jitsi.nlj.util.createChildLogger
import org.jitsi.nlj.util.milliseconds
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.RtcpFbTccPacket
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.RtcpFbTccPacketBuilder
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.rtp.rtp.header_extensions.TccHeaderExtension
import org.jitsi.rtp.util.RtpUtils
import org.jitsi.rtp.util.isOlderThan
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.stats.RateStatistics

/**
 * Extract the TCC sequence numbers from each passing packet and generate
 * a TCC packet to send transmit to the sender.
 */
class TccGeneratorNode(
    private val onTccPacketReady: (RtcpPacket) -> Unit = {},
    private val streamInformation: ReadOnlyStreamInformationStore,
    parentLogger: Logger,
    private val clock: Clock = Clock.systemDefaultZone()
) : ObserverNode("TCC generator") {
    private val logger = parentLogger.createChildLogger(TccGeneratorNode::class)
    private var tccExtensionId: Int? = null
    private var currTccSeqNum: Int = 0
    private var lastTccSentTime: Instant = NEVER
    private val lock = Any()
    // Tcc seq num -> arrival time in ms
    private val packetArrivalTimes =
        TreeMap<Int, Long>(Comparator<Int> { o1, o2 -> RtpUtils.getSequenceNumberDelta(o1, o2) })
    // The first sequence number of the current tcc feedback packet
    private var windowStartSeq: Int = -1
    private var running = true
    private val tccFeedbackBitrate = RateStatistics(1000)
    private var numTccSent: Int = 0

    init {
        streamInformation.onRtpExtensionMapping(TRANSPORT_CC) {
            tccExtensionId = it
        }
    }

    override fun observe(packetInfo: PacketInfo) {
        tccExtensionId?.let { tccExtId ->
            val rtpPacket = packetInfo.packetAs<RtpPacket>()
            rtpPacket.getHeaderExtension(tccExtId)?.let { ext ->
                val tccSeqNum = TccHeaderExtension.getSequenceNumber(ext)
                addPacket(tccSeqNum, packetInfo.receivedTime, rtpPacket.isMarked)
            }
        }
    }

    private fun addPacket(tccSeqNum: Int, timestamp: Long, isMarked: Boolean) {
        synchronized(lock) {
            if (packetArrivalTimes.ceilingKey(windowStartSeq) == null) {
                // Packets in map are all older than the start of the next tcc feedback packet,
                // remove them
                // TODO: chrome does something more advanced. is this good enough?
                packetArrivalTimes.clear()
            }
            if (windowStartSeq == -1) {
                windowStartSeq = tccSeqNum
            } else if (tccSeqNum isOlderThan windowStartSeq) {
                windowStartSeq = tccSeqNum
            }
            packetArrivalTimes.putIfAbsent(tccSeqNum, timestamp)
            if (isTccReadyToSend(isMarked)) {
                buildFeedback()?.let { sendTcc(it) }
            }
        }
    }

    private fun buildFeedback(): RtcpFbTccPacket? {
        val tccBuilder = RtcpFbTccPacketBuilder(
            mediaSourceSsrc = streamInformation.primaryMediaSsrcs.firstOrNull() ?: -1L,
            feedbackPacketSeqNum = currTccSeqNum++
        )
        logger.cdebug { "building TCC packet with media ssrc ${tccBuilder.mediaSourceSsrc} and" +
            " seq num ${tccBuilder.feedbackPacketSeqNum}" }
        synchronized(lock) {
            // windowStartSeq is the first sequence number to include in the current feedback, but we may not have
            // received it so the base time shall be the time of the first received packet which will be included
            // in this feedback
            val firstEntry = packetArrivalTimes.ceilingEntry(windowStartSeq) ?: return null
            tccBuilder.SetBase(windowStartSeq, firstEntry.value * 1000)

            var nextSequenceNumber = windowStartSeq
            val feedbackBlockPackets = packetArrivalTimes.tailMap(windowStartSeq)
            for ((seqNum, timestampMs) in feedbackBlockPackets) {
                if (!tccBuilder.AddReceivedPacket(seqNum, timestampMs * 1000)) {
                    break
                }
                nextSequenceNumber = (seqNum + 1) and 0xFFFF
            }

            // The next window will start with the sequence number after the last one we included in the previous
            // feedback
            windowStartSeq = nextSequenceNumber
        }

        logger.cdebug { "built TCC packet with ${tccBuilder.num_seq_no_} packets represented" }

        return tccBuilder.build()
    }

    private fun sendTcc(tccPacket: RtcpFbTccPacket) {
        onTccPacketReady(tccPacket)
        logger.cdebug { "sent TCC packet with seq num ${tccPacket.feedbackSeqNum}" }
        numTccSent++
        lastTccSentTime = clock.instant()
        tccFeedbackBitrate.update(tccPacket.length, clock.millis())
    }

    private fun isTccReadyToSend(currentPacketMarked: Boolean): Boolean {
        val now = clock.instant()
        // We don't want to send TCC the very first time we check (which would
        // be after the first packet was added).  So the first time we check,
        // set the last sent time to now to delay sending TCC by at least one 'interval'
        if (lastTccSentTime == NEVER) {
            lastTccSentTime = now
            return false
        }

        val timeSinceLastTcc = Duration.between(lastTccSentTime, now)
        return timeSinceLastTcc >= 100.milliseconds() ||
            ((timeSinceLastTcc >= 20.milliseconds()) && currentPacketMarked)
    }

    override fun stop() {
        running = false
    }

    override fun getNodeStats(): NodeStatsBlock {
        return super.getNodeStats().apply {
            addNumber("num_tcc_packets_sent", numTccSent)
            addNumber("tcc_feedback_bitrate_bps", tccFeedbackBitrate.rate)
            addString("tcc_extension_id", tccExtensionId.toString())
        }
    }
}
