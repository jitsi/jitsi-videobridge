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
package org.jitsi.nlj.transform.node.outgoing

import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.PacketOrigin
import org.jitsi.nlj.rtp.AudioRtpPacket
import org.jitsi.nlj.rtp.bandwidthestimation2.PacedPacketInfo
import org.jitsi.nlj.rtp.bandwidthestimation2.PacerConfig
import org.jitsi.nlj.rtp.bandwidthestimation2.ProbeClusterConfig
import org.jitsi.nlj.rtp.pacing.PacerControl
import org.jitsi.nlj.rtp.pacing.PacingController
import org.jitsi.nlj.rtp.pacing.RtpPacketMediaType
import org.jitsi.nlj.rtp.pacing.RtpPacketToSend
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.node.MultipleOutputTransformerNode
import org.jitsi.nlj.util.Bandwidth
import org.jitsi.nlj.util.DataSize
import org.jitsi.nlj.util.MonotonicClock
import org.jitsi.nlj.util.wholeBytes
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.utils.ms
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Paces the RTP packets that are sent to an endpoint: it holds a packet until the pacing rate allows it to be sent,
 * and sends nothing but audio while the data in flight has reached the congestion window. The decisions are made by
 * a [PacingController], which is a port of WebRTC's. This class does what TaskQueuePacedSender and PacketRouter do
 * around the controller in WebRTC: it decides when the controller runs, and it sends the packets that the
 * controller releases.
 *
 * The node sits where retransmissions and probing join the pipeline of the sender, so they are paced as well. A
 * packet gets its transport sequence number and its send record after it leaves this node, in the order in which
 * the packets are sent.
 *
 * Until [setPacerConfig] has been called the node passes every packet on unchanged. A node that no network
 * controller is attached to therefore does nothing.
 *
 * Threads: a packet that the pacing rate allows is passed on by the thread that brought it here. A packet that has
 * to wait is passed on by a task on [executor], which [timer] submits at the time the controller asks for. The timer
 * thread does no other work. One lock guards the controller, and it is held while packets are passed on, so the nodes
 * after this one see one packet at a time. The methods of [PacerControl] do not take that lock, see there.
 */
class PacedSender(
    parentLogger: Logger,
    /** Runs the tasks that pass on packets which had to wait. */
    private val executor: ExecutorService,
    /** Sends padding of about the given size, in bytes, through this node. Called with the lock of the pacer held. */
    private val sendPadding: (Int) -> Unit,
    /** The SSRC that retransmissions of the given SSRC are sent with, if any. */
    private val getRtxSsrc: (Long) -> Long? = { null },
    private val timer: ScheduledExecutorService = defaultTimer,
    private val clock: Clock = MonotonicClock(),
    fieldTrials: PacingController.FieldTrials = PacingController.FieldTrials()
) : MultipleOutputTransformerNode("Paced sender"), PacerControl {
    private val logger = createChildLogger(parentLogger)

    /** Guards [controller] and the fields below it. */
    private val lock = Any()

    private val controller = PacingController(logger, clock, Sender(), fieldTrials)

    /** Whether the controller has a pacing rate. Packets are paced from then on. */
    private var configured = false

    private var paddingRate = Bandwidth.ZERO

    private var congested = false

    /** The packets of the padding that is being generated, see [Sender.generatePadding]. */
    private var paddingBeingGenerated: MutableList<PacketInfo>? = null

    /** The SSRCs of the packets that have been in the queue, to remove them when the node stops. */
    private val queuedSsrcs = HashSet<Long>()

    /** The time that [timerTask] is scheduled for, or null if it is not scheduled. */
    private var scheduledProcessTime: Instant? = null
    private var timerTask: ScheduledFuture<*>? = null

    private var stopped = false

    // Updates from the code that runs the network controller. They are applied with the lock held, the next time
    // the pacer runs.
    private val pendingPacerConfig = AtomicReference<PacerConfig?>(null)
    private val pendingCongested = AtomicReference<Boolean?>(null)
    private val pendingProbeClusters = ConcurrentLinkedQueue<ProbeClusterConfig>()
    private val processRequested = AtomicBoolean(false)

    @Volatile
    private var queueSize = DataSize.ZERO

    // Statistics
    private var numPacketsPaced = 0L
    private var numPacketsPassedThrough = 0L
    private var numPaddingPacketsGenerated = 0L
    private var numPacketsDropped = 0L
    private var numTimerRuns = 0L
    private var maxQueueSizePackets = 0
    private var maxQueueSize = DataSize.ZERO

    /** The time that the packets waited in the pacer. */
    private val queueTime = DelayStats()

    /** The time from when the pacer passes a packet on until the packet has been written to the socket. */
    private val releaseToSendDelay = DelayStats()

    override fun setPacerConfig(config: PacerConfig) {
        pendingPacerConfig.set(config)
        requestProcess()
    }

    override fun setCongested(congested: Boolean) {
        pendingCongested.set(congested)
        requestProcess()
    }

    override fun createProbeClusters(configs: List<ProbeClusterConfig>) {
        pendingProbeClusters.addAll(configs)
        requestProcess()
    }

    override fun queueSizeData(): DataSize = queueSize

    /** Runs the pacer on [executor], to apply the pending updates and send what they allow. */
    private fun requestProcess() {
        if (processRequested.compareAndSet(false, true)) {
            executor.execute {
                processRequested.set(false)
                synchronized(lock) {
                    if (!stopped) {
                        process()
                    }
                }
            }
        }
    }

    override fun transform(packetInfo: PacketInfo): List<PacketInfo> {
        val rtpPacket = packetInfo.packet as? RtpPacket ?: return listOf(packetInfo)
        synchronized(lock) {
            paddingBeingGenerated?.let {
                // The controller asked for this packet, and takes it from the list.
                it.add(packetInfo)
                return emptyList()
            }
            applyPendingUpdates()
            if (!configured || stopped) {
                numPacketsPassedThrough++
                return listOf(packetInfo)
            }
            numPacketsPaced++
            queuedSsrcs.add(rtpPacket.ssrc)
            controller.enqueuePacket(packetInfo.toRtpPacketToSend(rtpPacket))
            maxQueueSizePackets = maxOf(maxQueueSizePackets, controller.queueSizePackets())
            maxQueueSize = maxOf(maxQueueSize, controller.queueSizeData())
            process()
        }
        return emptyList()
    }

    /** Applies the updates that are pending. The caller holds [lock]. */
    private fun applyPendingUpdates() {
        pendingPacerConfig.getAndSet(null)?.let { config ->
            controller.setPacerConfig(config)
            paddingRate = config.padRate()
            configured = true
        }
        pendingCongested.getAndSet(null)?.let {
            controller.setCongested(it)
            congested = it
        }
        if (pendingProbeClusters.isNotEmpty()) {
            val clusters = ArrayList<ProbeClusterConfig>()
            while (true) {
                clusters.add(pendingProbeClusters.poll() ?: break)
            }
            controller.createProbeClusters(clusters)
        }
    }

    /**
     * Sends the packets that are due and schedules the next run. The caller holds [lock]. This follows
     * TaskQueuePacedSender::MaybeProcessPackets in WebRTC.
     */
    private fun process() {
        applyPendingUpdates()
        if (!configured) {
            return
        }
        var nextSendTime = controller.nextSendTime()
        val now = clock.instant()
        var earlyExecuteMargin = earlyExecuteMargin()

        while (nextSendTime <= now + earlyExecuteMargin) {
            controller.processPackets()
            nextSendTime = controller.nextSendTime()

            // Probing state could change. Get margin after process packets.
            earlyExecuteMargin = earlyExecuteMargin()
        }
        queueSize = controller.queueSizeData()

        if (!needsTimer()) {
            cancelTimer()
            return
        }

        // Do not hold back in probing.
        val holdBackWindow = if (controller.isProbing()) Duration.ZERO else HOLD_BACK_WINDOW
        val timeToNextProcess = maxOf(holdBackWindow, Duration.between(now, nextSendTime) - earlyExecuteMargin)
        val nextProcessTime = now + timeToNextProcess

        // If no task is scheduled, or the task that is scheduled is later than the time needed, schedule a new one.
        val scheduled = scheduledProcessTime
        if (scheduled == null || scheduled > nextProcessTime) {
            timerTask?.cancel(false)
            scheduledProcessTime = nextProcessTime
            timerTask = timer.schedule(
                { executor.execute { timerFired(nextProcessTime) } },
                timeToNextProcess.toNanos(),
                TimeUnit.NANOSECONDS
            )
        }
    }

    private fun earlyExecuteMargin(): Duration =
        if (controller.isProbing()) PacingController.kMaxEarlyProbeProcessing else Duration.ZERO

    /**
     * Whether the controller has anything to do at a later time. Without a timer the controller still runs when the
     * next packet arrives.
     */
    private fun needsTimer(): Boolean =
        controller.queueSizePackets() > 0 || paddingRate > Bandwidth.ZERO || controller.isProbing() || congested

    private fun cancelTimer() {
        timerTask?.cancel(false)
        timerTask = null
        scheduledProcessTime = null
    }

    private fun timerFired(processTime: Instant) {
        synchronized(lock) {
            if (stopped || scheduledProcessTime != processTime) {
                // A task that was replaced by an earlier one.
                return
            }
            scheduledProcessTime = null
            timerTask = null
            numTimerRuns++
            process()
        }
    }

    private fun PacketInfo.toRtpPacketToSend(rtpPacket: RtpPacket): RtpPacketToSend {
        val type = when {
            packetOrigin == PacketOrigin.Retransmission -> RtpPacketMediaType.kRetransmission
            packetOrigin == PacketOrigin.Probing || packetOrigin == PacketOrigin.Padding -> RtpPacketMediaType.kPadding
            rtpPacket is AudioRtpPacket -> RtpPacketMediaType.kAudio
            else -> RtpPacketMediaType.kVideo
        }
        return RtpPacketToSend(
            packetType = type,
            ssrc = rtpPacket.ssrc,
            sequenceNumber = rtpPacket.sequenceNumber,
            headersSize = rtpPacket.headerLength,
            payloadSize = rtpPacket.payloadLength,
            // The bridge retransmits video only.
            originalPacketType = if (type == RtpPacketMediaType.kRetransmission) {
                RtpPacketToSend.OriginalType.kVideo
            } else {
                null
            },
            packetInfo = this
        )
    }

    /** What the controller calls to send packets. It is called with [lock] held. */
    private inner class Sender : PacingController.PacketSender {
        override fun sendPacket(packet: RtpPacketToSend, clusterInfo: PacedPacketInfo) {
            val packetInfo = packet.packetInfo!!
            if (clusterInfo.probeClusterId != PacedPacketInfo.kNotAProbe) {
                packetInfo.probingInfo = clusterInfo
            }
            packet.timeInSendQueue?.let { queueTime.add(it.toNanos()) }
            val releaseTime = System.nanoTime()
            packetInfo.onSent { releaseToSendDelay.add(System.nanoTime() - releaseTime) }
            next(packetInfo)
        }

        override fun fetchFec(): List<RtpPacketToSend> = emptyList()

        override fun generatePadding(size: DataSize): List<RtpPacketToSend> {
            val packets = ArrayList<PacketInfo>()
            paddingBeingGenerated = packets
            try {
                // WebRTC always sends padding packets of the maximum size when it cannot pad with a retransmission.
                sendPadding(maxOf(size.wholeBytes().toInt(), MIN_PADDING_SIZE))
            } finally {
                paddingBeingGenerated = null
            }
            numPaddingPacketsGenerated += packets.size
            return packets.mapNotNull { packetInfo ->
                val rtpPacket = packetInfo.packet as? RtpPacket
                if (rtpPacket == null) {
                    packetDiscarded(packetInfo)
                    null
                } else {
                    queuedSsrcs.add(rtpPacket.ssrc)
                    RtpPacketToSend(
                        packetType = RtpPacketMediaType.kPadding,
                        ssrc = rtpPacket.ssrc,
                        sequenceNumber = rtpPacket.sequenceNumber,
                        headersSize = rtpPacket.headerLength,
                        payloadSize = rtpPacket.payloadLength,
                        packetInfo = packetInfo
                    )
                }
            }
        }

        override fun getRtxSsrcForMedia(ssrc: Long): Long? = getRtxSsrc(ssrc)

        override fun onPacketDropped(packet: RtpPacketToSend) {
            numPacketsDropped++
            packet.packetInfo?.let { packetDiscarded(it) }
        }
    }

    override fun stop() {
        synchronized(lock) {
            if (!stopped) {
                stopped = true
                cancelTimer()
                // The controller passes the packets that are still queued to onPacketDropped.
                queuedSsrcs.forEach { controller.removePacketsForSsrc(it) }
                queuedSsrcs.clear()
                queueSize = DataSize.ZERO
            }
        }
        super.stop()
    }

    override fun getNodeStats(): NodeStatsBlock = super.getNodeStats().apply {
        synchronized(lock) {
            addBoolean("pacing", configured)
            addBoolean("congested", congested)
            addNumber("queue_size_packets", controller.queueSizePackets())
            addNumber("queue_size_bytes", controller.queueSizeData().wholeBytes())
            if (configured) {
                addNumber("pacing_rate_bps", controller.pacingRate().bps)
                addNumber("expected_queue_time_ms", controller.expectedQueueTime().toMillis())
            }
            addNumber("padding_rate_bps", paddingRate.bps)
            addNumber("num_packets_paced", numPacketsPaced)
            addNumber("num_packets_passed_through", numPacketsPassedThrough)
            addNumber("num_padding_packets_generated", numPaddingPacketsGenerated)
            addNumber("num_packets_dropped", numPacketsDropped)
            addNumber("num_timer_runs", numTimerRuns)
            addNumber("max_queue_size_packets", maxQueueSizePackets)
            addNumber("max_queue_size_bytes", maxQueueSize.wholeBytes())
        }
        addNumber("queue_time_avg_us", queueTime.averageMicros())
        addNumber("queue_time_max_us", queueTime.maxMicros())
        addNumber("release_to_send_delay_avg_us", releaseToSendDelay.averageMicros())
        addNumber("release_to_send_delay_max_us", releaseToSendDelay.maxMicros())
    }

    override val aggregationKey = name

    override fun trace(f: () -> Unit) = f.invoke()

    /** The average and the maximum of a delay. */
    private class DelayStats {
        private var count = 0L
        private var sumNanos = 0L
        private var maxNanos = 0L

        @Synchronized
        fun add(nanos: Long) {
            count++
            sumNanos += nanos
            if (nanos > maxNanos) {
                maxNanos = nanos
            }
        }

        @Synchronized
        fun averageMicros(): Long = if (count == 0L) 0 else sumNanos / count / 1000

        @Synchronized
        fun maxMicros(): Long = maxNanos / 1000
    }

    companion object {
        /**
         * The shortest time between two runs of a pacer that the timer starts, except while probing. WebRTC holds
         * back for up to 5 ms, and for less when packets are sent at a high rate.
         */
        private val HOLD_BACK_WINDOW: Duration = 1.ms

        /** The size of a padding packet of the maximum size: the RTP header and 224 bytes of padding. */
        private const val MIN_PADDING_SIZE = 12 + 224

        /** The timer that all pacers share. Its thread only submits tasks to the executors of the pacers. */
        private val defaultTimer: ScheduledExecutorService by lazy {
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "pacer-timer").apply { isDaemon = true }
            }
        }
    }
}
