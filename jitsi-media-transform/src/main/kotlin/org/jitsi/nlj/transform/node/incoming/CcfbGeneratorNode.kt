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
package org.jitsi.nlj.transform.node.incoming

import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.rtp.LossListener
import org.jitsi.nlj.rtp.ccfb.CongestionControlFeedbackGenerator
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.node.ObserverNode
import org.jitsi.nlj.util.BitrateTracker
import org.jitsi.nlj.util.ReadOnlyStreamInformationStore
import org.jitsi.nlj.util.bytes
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.ccfb.EcnMarking
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.ccfb.RtcpFbCcfbPacket
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.utils.ms
import org.jitsi.utils.observableWhenChanged
import org.jitsi.utils.secs
import java.time.Clock
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Generates RTCP congestion control feedback (RFC 8888) for the RTP packets received from an endpoint which has
 * negotiated it ("ack ccfb"): for every packet, whether it arrived, when, and with which ECN marking.
 *
 * Feedback is identified by SSRC and RTP sequence number as they were on the wire, so this node has to see every
 * packet, including retransmissions, padding and duplicates, before anything rewrites or discards them.
 */
class CcfbGeneratorNode(
    private val onCcfbPacketReady: (RtcpPacket) -> Unit = {},
    private val streamInformation: ReadOnlyStreamInformationStore,
    /** Used to send feedback which is due when no packet arrives to trigger it. */
    private val scheduler: ScheduledExecutorService,
    parentLogger: Logger,
    private val clock: Clock = Clock.systemUTC()
) : ObserverNode("CCFB generator") {
    private val logger = createChildLogger(parentLogger)
    private val lock = Any()

    /** Not thread safe: only used with [lock] held. Packets arrive on one thread and the timer runs on another. */
    private val generator = CongestionControlFeedbackGenerator(clock, logger, rtcpSender = this::sendFeedback)

    /** The pending call to [process], if any. One is only scheduled while there are packets to report. */
    private var processTask: ScheduledFuture<*>? = null
    private var running = true

    private val lossListeners = mutableListOf<LossListener>()

    private var numPacketsSinceFeedback = 0

    /** The number of packets received with each ECN marking, indexed by [EcnMarking.bits]. */
    private val numPacketsByEcn = LongArray(4)
    private var numPacketsReportedLost = 0L
    private var numPacketsReportedRecovered = 0L

    private val ccfbFeedbackBitrate = BitrateTracker(1.secs, 10.ms)
    private var numCcfbSent = 0
    private var enabled: Boolean by observableWhenChanged(false) { _, _, newValue ->
        logger.debug("Setting enabled=$newValue")
    }

    init {
        streamInformation.onRtpPayloadTypesChanged {
            enabled = streamInformation.supportsCcfb
        }
    }

    override fun observe(packetInfo: PacketInfo) {
        if (!enabled) return

        val rtpPacket = packetInfo.packetAs<RtpPacket>()
        synchronized(lock) {
            numPacketsSinceFeedback++
            numPacketsByEcn[packetInfo.receivedEcn.bits.toInt()]++
            generator.onReceivedPacket(
                rtpPacket.ssrc,
                rtpPacket.sequenceNumber,
                rtpPacket.isMarked,
                packetInfo.receivedTime ?: clock.instant(),
                packetInfo.receivedEcn
            )
            if (processTask == null) {
                process()
            }
        }
    }

    /**
     * Sends feedback if it is due, and if packets then remain to be reported schedules another call for when their
     * feedback will be. Must be called with [lock] held.
     */
    private fun process() {
        processTask = null
        if (!running) {
            return
        }
        val delay = generator.process(clock.instant())
        if (generator.hasUnreportedPackets()) {
            processTask = scheduler.schedule(
                { synchronized(lock) { process() } },
                delay.toNanos(),
                TimeUnit.NANOSECONDS
            )
        }
    }

    /** Called by [generator], with [lock] held. */
    private fun sendFeedback(packet: RtcpFbCcfbPacket) {
        onCcfbPacketReady(packet)
        numCcfbSent++
        ccfbFeedbackBitrate.update(packet.length.bytes, clock.millis())

        val stats = generator.getTotalStats()
        val numLost = stats.numPacketsReportedLost - numPacketsReportedLost
        val numRecovered = stats.numPacketsReportedRecovered - numPacketsReportedRecovered
        val numReceived = numPacketsSinceFeedback
        numPacketsReportedLost = stats.numPacketsReportedLost
        numPacketsReportedRecovered = stats.numPacketsReportedRecovered
        numPacketsSinceFeedback = 0

        lossListeners.forEach { listener ->
            if (numLost > 0) {
                listener.packetLost(numLost.toInt())
            }
            repeat(numReceived) { i ->
                // A packet reported as lost in earlier feedback and as received in this one.
                listener.packetReceived(i < numRecovered)
            }
        }
    }

    /**
     * Adds a loss listener to be notified about packet arrival and loss reports.
     */
    fun addLossListener(listener: LossListener) {
        synchronized(lock) {
            lossListeners.add(listener)
        }
    }

    fun removeLossListener(listener: LossListener) {
        synchronized(lock) {
            lossListeners.remove(listener)
        }
    }

    override fun stop() {
        super.stop()
        synchronized(lock) {
            running = false
            processTask?.cancel(false)
            processTask = null
        }
    }

    override fun trace(f: () -> Unit) = f.invoke()

    override fun getNodeStats(): NodeStatsBlock = super.getNodeStats().apply {
        synchronized(lock) {
            addNumber("num_ccfb_packets_sent", numCcfbSent)
            addNumber("ccfb_feedback_bitrate_bps", ccfbFeedbackBitrate.rate.bps)
            addNumber("num_packets_reported_lost", numPacketsReportedLost)
            addNumber("num_packets_reported_recovered", numPacketsReportedRecovered)
            EcnMarking.entries.forEach { addNumber("num_packets_${it.statName}", numPacketsByEcn[it.bits.toInt()]) }
            addBoolean("enabled", enabled)
        }
    }

    override fun statsJson() = super.statsJson().apply {
        synchronized(lock) {
            put("num_ccfb_packets_sent", numCcfbSent)
            put("ccfb_feedback_bitrate_bps", ccfbFeedbackBitrate.rate.bps)
            put("num_packets_reported_lost", numPacketsReportedLost)
            put("num_packets_reported_recovered", numPacketsReportedRecovered)
            EcnMarking.entries.forEach { put("num_packets_${it.statName}", numPacketsByEcn[it.bits.toInt()]) }
            put("enabled", enabled)
        }
    }
}

/** The name of an ECN marking in stats: `not_ect`, `ect1`, `ect0` or `ce`. */
private val EcnMarking.statName: String
    get() = when (this) {
        EcnMarking.kNotEct -> "not_ect"
        EcnMarking.kEct1 -> "ect1"
        EcnMarking.kEct0 -> "ect0"
        EcnMarking.kCe -> "ce"
    }
