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

import org.jitsi.nlj.Event
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.ReceiveSsrcAddedEvent
import org.jitsi.nlj.ReceiveSsrcRemovedEvent
import org.jitsi.nlj.rtp.RtpExtensionType.TRANSPORT_CC
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.node.ObserverNode
import org.jitsi.nlj.util.StreamInformationStore
import org.jitsi.nlj.util.cdebug
import org.jitsi.nlj.util.isOlderThan
import org.jitsi.rtp.extensions.unsigned.toPositiveLong
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.RtcpFbTccPacket
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.RtcpFbTccPacketBuilder
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.rtp.rtp.header_extensions.TccHeaderExtension
import org.jitsi.rtp.util.RtpUtils
import org.jitsi.utils.stats.RateStatistics
import java.util.TreeMap
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Extract the TCC sequence numbers from each passing packet and generate
 * a TCC packet to send transmit to the sender.
 */
class TccGeneratorNode(
    private val onTccPacketReady: (RtcpPacket) -> Unit = {},
    private val scheduler: ScheduledExecutorService,
    private val getSendBitrate: () -> Long,
    streamInformation: StreamInformationStore
) : ObserverNode("TCC generator") {
    private var tccExtensionId: Int? = null
    private var currTccSeqNum: Int = 0
    private var lastTccSentTime: Long = 0
    private val lock = Any()
    // Tcc seq num -> arrival time in ms
    private val packetArrivalTimes =
        TreeMap<Int, Long>(Comparator<Int> { o1, o2 -> RtpUtils.getSequenceNumberDelta(o1, o2) })
    // The first sequence number of the current tcc feedback packet
    private var windowStartSeq: Int = -1
    private var sendIntervalMs: Long = 0
    private var running = true
    private var periodicFeedbacks = false
    private val tccFeedbackBitrate = RateStatistics(1000)
    /**
     * SSRCs we've been told this endpoint will transmit on.  We'll use an
     * SSRC from this list for the RTCPFB mediaSourceSsrc field in the
     * TCC packets we generate
     */
    private var mediaSsrcs: MutableSet<Long> = mutableSetOf()
    private var numTccSent: Int = 0

    init {
        streamInformation.onRtpExtensionMapping(TRANSPORT_CC) {
            tccExtensionId = it
        }
        reschedule()
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
            if (!periodicFeedbacks && isTccReadyToSend(isMarked)) {
                buildFeedback()?.let { sendTcc(it) }
            }
        }
    }

    private fun sendPeriodicFeedbacks() {
        try {
            logger.cdebug { "${System.identityHashCode(this)} sending periodic feedback at " +
                    "${System.currentTimeMillis()}, window start seq is $windowStartSeq" }
            buildFeedback()?.let {
                sendTcc(it)
            }
        } catch (t: Throwable) {
            logger.error("Error sending feedback", t)
        } finally {
            reschedule()
        }
    }

    private fun buildFeedback(): RtcpFbTccPacket? {
        val tccBuilder = RtcpFbTccPacketBuilder(
            mediaSourceSsrc = mediaSsrcs.firstOrNull() ?: -1L,
            feedbackPacketSeqNum = currTccSeqNum++
        )
        synchronized(lock) {
            // windowStartSeq is the first sequence number to include in the current feedback, but we may not have
            // received it so the base time shall be the time of the first received packet which will be included
            // in this feedback
            val firstEntry = packetArrivalTimes.ceilingEntry(windowStartSeq)
            if (firstEntry == null) {
                reschedule()
                return null
            }
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

        return tccBuilder.build()
    }

    private fun sendTcc(tccPacket: RtcpFbTccPacket) {
        onTccPacketReady(tccPacket)
        numTccSent++
        recalculateSendInterval(getSendBitrate())
        lastTccSentTime = System.currentTimeMillis()
        tccFeedbackBitrate.update(tccPacket.length, lastTccSentTime)
    }

    private fun reschedule() {
        if (running && periodicFeedbacks) {
            scheduler.schedule(::sendPeriodicFeedbacks, sendIntervalMs, TimeUnit.MILLISECONDS)
        }
    }

    private fun isTccReadyToSend(currentPacketMarked: Boolean): Boolean {
        if (lastTccSentTime <= 0) {
            lastTccSentTime = System.currentTimeMillis()
            return false
        }

        val timeSinceLastTcc = System.currentTimeMillis() - lastTccSentTime
        return timeSinceLastTcc >= 100 ||
            ((timeSinceLastTcc >= 20) && currentPacketMarked)
    }

    private fun recalculateSendInterval(sendBitrateBps: Long) {
        synchronized(lock) {
            // Let TWCC reports occupy 5% of the total sending bitrate. See
            // https://cs.chromium.org/chromium/src/third_party/webrtc/modules/congestion_controller/include/receive_side_congestion_controller.h?type=cs&g=0&l=52
            sendIntervalMs = (.5 + kTwccReportSize * 8.0 * 1000.0 /
                    (.05 * sendBitrateBps).coerceIn(kMinTwccRate, kMaxTwccRate)).toPositiveLong()
            logger.cdebug { "Send bitrate is now $sendBitrateBps, tcc send interval is $sendIntervalMs ms" }
        }
    }

    override fun stop() {
        running = false
    }

    override fun handleEvent(event: Event) {
        when (event) {
            is ReceiveSsrcAddedEvent -> mediaSsrcs.add(event.ssrc)
            is ReceiveSsrcRemovedEvent -> mediaSsrcs.remove(event.ssrc)
        }
    }

    override fun getNodeStats(): NodeStatsBlock {
        return super.getNodeStats().apply {
            addNumber("num_tcc_packets_sent", numTccSent)
            addNumber("tcc_feedback_bitrate_bps", tccFeedbackBitrate.rate)
            addString("tcc_extension_id", tccExtensionId.toString())
        }
    }

    companion object {
        private const val kMaxSendIntervalMs = 250
        private const val kMinSendIntervalMs = 50
        // TwccReportSize = Ipv4(20B) + UDP(8B) + SRTP(10B) +
        // AverageTwccReport(30B)
        // TwccReport size at 50ms interval is 24 byte.
        // TwccReport size at 250ms interval is 36 byte.
        // AverageTwccReport = (TwccReport(50ms) + TwccReport(250ms)) / 2
        private const val kTwccReportSize = 20 + 8 + 10 + 30
        private const val kMinTwccRate = kTwccReportSize * 8.0 * 1000.0 / kMaxSendIntervalMs
        private const val kMaxTwccRate = kTwccReportSize * 8.0 * 1000.0 / kMinSendIntervalMs
    }
}
