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

import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.rtp.LossListener
import org.jitsi.nlj.rtp.RtpExtensionType.TRANSPORT_CC
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.node.ObserverNode
import org.jitsi.nlj.util.BitrateTracker
import org.jitsi.nlj.util.ReadOnlyStreamInformationStore
import org.jitsi.nlj.util.RtpSequenceIndexTracker
import org.jitsi.nlj.util.bytes
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.RtcpFbTccPacket
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.RtcpFbTccPacketBuilder
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.rtp.rtp.header_extensions.TccHeaderExtension
import org.jitsi.utils.NEVER
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.cdebug
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.utils.ms
import org.jitsi.utils.observableWhenChanged
import org.jitsi.utils.secs
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.TreeMap

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
    private val logger = createChildLogger(parentLogger)
    private var tccExtensionId: Int? = null
    private var currTccSeqNum: Int = 0
    private var lastTccSentTime: Instant = NEVER
    private val lock = Any()

    // Tcc seq num -> arrival time in ms
    private val packetArrivalTimes = TreeMap<Long, Instant>()

    // The first sequence number of the current tcc feedback packet
    private var windowStartSeq: Long = -1
    private val tccFeedbackBitrate = BitrateTracker(1.secs, 10.ms)
    private var numTccSent: Int = 0
    private var numMultipleTccPackets = 0
    private var enabled: Boolean by observableWhenChanged(false) {
            _, _, newValue ->
        logger.debug("Setting enabled=$newValue")
    }
    private val rtpSequenceIndexTracker = RtpSequenceIndexTracker()

    private val lossListeners = mutableListOf<LossListener>()

    init {
        streamInformation.onRtpExtensionMapping(TRANSPORT_CC) {
            tccExtensionId = it
        }
        streamInformation.onRtpPayloadTypesChanged {
            enabled = streamInformation.supportsTcc
        }
    }

    override fun observe(packetInfo: PacketInfo) {
        if (!enabled) return

        tccExtensionId?.let { tccExtId ->
            val rtpPacket = packetInfo.packetAs<RtpPacket>()
            rtpPacket.getHeaderExtension(tccExtId)?.let { ext ->
                val tccSeqNum = rtpSequenceIndexTracker.update(TccHeaderExtension.getSequenceNumber(ext))
                addPacket(tccSeqNum, packetInfo.receivedTime, rtpPacket.isMarked, rtpPacket.ssrc)
            }
        }
    }

    /**
     * Adds a loss listener to be notified about packet arrival and loss reports.
     * @param listener
     */
    fun addLossListener(listener: LossListener) {
        synchronized(lock) {
            lossListeners.add(listener)
        }
    }

    /**
     * Removes a loss listener.
     * @param listener
     */
    @Synchronized
    fun removeLossListener(listener: LossListener) {
        synchronized(lock) {
            lossListeners.remove(listener)
        }
    }

    /**
     * @param tccSeqNum the extended sequence number.
     */
    private fun addPacket(tccSeqNum: Long, timestamp: Instant?, isMarked: Boolean, ssrc: Long) {
        synchronized(lock) {
            if (packetArrivalTimes.ceilingKey(windowStartSeq) == null) {
                // Packets in map are all older than the start of the next tcc feedback packet,
                // remove them
                // TODO: Chrome does something more advanced, keeping older sequences to replay on packet reordering.
                packetArrivalTimes.clear()
            }

            timestamp?.run {
                if (packetArrivalTimes.isEmpty() && windowStartSeq == -1L) {
                    lossListeners.forEach {
                        it.packetReceived(false)
                    }
                } else {
                    val oldMax = if (packetArrivalTimes.isNotEmpty()) {
                        packetArrivalTimes.lastKey()
                    } else {
                        windowStartSeq - 1
                    }
                    if (tccSeqNum > oldMax) {
                        val numLost = tccSeqNum - oldMax - 1
                        /* TODO: should we squelch for large tcc jumps? */
                        lossListeners.forEach {
                            if (numLost > 0) {
                                it.packetLost(numLost.toInt())
                            }
                            it.packetReceived(false)
                        }
                    } else if (tccSeqNum < windowStartSeq || !packetArrivalTimes.containsKey(tccSeqNum)) {
                        /* If we've already cleared the arrival info about this packet, assume it was previously
                         * reported as lost - there are some corner cases where this isn't true, but they should be rare.
                         */
                        lossListeners.forEach {
                            it.packetReceived(true)
                        }
                    }
                }

                if (windowStartSeq == -1L || tccSeqNum < windowStartSeq) {
                    windowStartSeq = tccSeqNum
                }

                packetArrivalTimes.putIfAbsent(tccSeqNum, timestamp)
            }
            if (isTccReadyToSend(isMarked)) {
                buildFeedback(ssrc).forEach { sendTcc(it) }
            }
        }
    }

    private fun buildFeedback(mediaSsrc: Long): List<RtcpFbTccPacket> {
        synchronized(lock) {
            // windowStartSeq is the first sequence number to include in the current feedback, but we may not have
            // received it so the base time shall be the time of the first received packet which will be included
            // in this feedback
            val firstEntry = packetArrivalTimes.ceilingEntry(windowStartSeq) ?: return emptyList()

            val tccPackets = mutableListOf<RtcpFbTccPacket>()
            var currentTccPacket = RtcpFbTccPacketBuilder(
                mediaSourceSsrc = mediaSsrc,
                feedbackPacketSeqNum = currTccSeqNum++
            )
            currentTccPacket.SetBase(windowStartSeq.toInt(), firstEntry.value)

            var nextSequenceNumber = windowStartSeq
            val feedbackBlockPackets = packetArrivalTimes.tailMap(windowStartSeq)
            feedbackBlockPackets.forEach { (seq, timestamp) ->
                if (!currentTccPacket.AddReceivedPacket(seq.toInt(), timestamp)) {
                    tccPackets.add(currentTccPacket.build())
                    currentTccPacket = RtcpFbTccPacketBuilder(
                        mediaSourceSsrc = mediaSsrc,
                        feedbackPacketSeqNum = currTccSeqNum++
                    ).apply {
                        SetBase(seq.toInt(), timestamp)
                        AddReceivedPacket(seq.toInt(), timestamp)
                    }
                }
                nextSequenceNumber = seq + 1
            }

            tccPackets.add(currentTccPacket.build())
            if (tccPackets.size > 1) {
                numMultipleTccPackets++
                logger.info(
                    "Sending TCC feedback in ${tccPackets.size} packets " +
                        "(${feedbackBlockPackets.size} media packets)"
                )
            }
            // The next window will start with the sequence number after the last one we included in the previous
            // feedback
            windowStartSeq = nextSequenceNumber

            return tccPackets
        }
    }

    private fun sendTcc(tccPacket: RtcpFbTccPacket) {
        onTccPacketReady(tccPacket)
        logger.cdebug { "sent TCC packet with seq num ${tccPacket.feedbackSeqNum}" }
        numTccSent++
        lastTccSentTime = clock.instant()
        tccFeedbackBitrate.update(tccPacket.length.bytes, clock.millis())
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
        return timeSinceLastTcc >= 100.ms ||
            ((timeSinceLastTcc >= 20.ms) && currentPacketMarked)
    }

    override fun trace(f: () -> Unit) = f.invoke()

    override fun getNodeStats(): NodeStatsBlock {
        return super.getNodeStats().apply {
            addNumber("num_tcc_packets_sent", numTccSent)
            addNumber("tcc_feedback_bitrate_bps", tccFeedbackBitrate.rate.bps)
            addString("tcc_extension_id", tccExtensionId.toString())
            addNumber("num_multiple_tcc_packets", numMultipleTccPackets)
            addBoolean("enabled", enabled)
        }
    }

    override fun statsJson() = super.statsJson().apply {
        this["num_tcc_packets_sent"] = numTccSent
        this["tcc_feedback_bitrate_bps"] = tccFeedbackBitrate.rate.bps
        this["tcc_extension_id"] = tccExtensionId.toString()
        this["num_multiple_tcc_packets"] = numMultipleTccPackets
        this["enabled"] = enabled
    }
}
