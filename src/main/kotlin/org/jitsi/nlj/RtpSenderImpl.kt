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
package org.jitsi.nlj

import org.jitsi.config.JitsiConfig
import org.jitsi.metaconfig.config
import org.jitsi.metaconfig.from
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService
import org.jitsi.nlj.rtcp.KeyframeRequester
import org.jitsi.nlj.rtcp.NackHandler
import org.jitsi.nlj.rtcp.RtcpEventNotifier
import org.jitsi.nlj.rtcp.RtcpSrUpdater
import org.jitsi.nlj.rtp.TransportCcEngine
import org.jitsi.nlj.rtp.bandwidthestimation.BandwidthEstimator
import org.jitsi.nlj.rtp.bandwidthestimation.GoogleCcEstimator
import org.jitsi.nlj.srtp.SrtpTransformers
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.NodeEventVisitor
import org.jitsi.nlj.transform.NodeStatsVisitor
import org.jitsi.nlj.transform.NodeTeardownVisitor
import org.jitsi.nlj.transform.node.AudioRedHandler
import org.jitsi.nlj.transform.node.ConsumerNode
import org.jitsi.nlj.transform.node.Node
import org.jitsi.nlj.transform.node.PacketCacher
import org.jitsi.nlj.transform.node.PacketLossConfig
import org.jitsi.nlj.transform.node.PacketLossNode
import org.jitsi.nlj.transform.node.PacketStreamStatsNode
import org.jitsi.nlj.transform.node.SrtcpEncryptNode
import org.jitsi.nlj.transform.node.SrtpEncryptNode
import org.jitsi.nlj.transform.node.ToggleablePcapWriter
import org.jitsi.nlj.transform.node.outgoing.AbsSendTime
import org.jitsi.nlj.transform.node.outgoing.HeaderExtEncoder
import org.jitsi.nlj.transform.node.outgoing.OutgoingStatisticsTracker
import org.jitsi.nlj.transform.node.outgoing.ProbingDataSender
import org.jitsi.nlj.transform.node.outgoing.RetransmissionSender
import org.jitsi.nlj.transform.node.outgoing.SentRtcpStats
import org.jitsi.nlj.transform.node.outgoing.HeaderExtStripper
import org.jitsi.nlj.transform.node.outgoing.TccSeqNumTagger
import org.jitsi.nlj.transform.pipeline
import org.jitsi.nlj.util.PacketInfoQueue
import org.jitsi.nlj.util.StreamInformationStore
import org.jitsi.utils.logging2.cdebug
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.utils.MediaType
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.queue.CountingErrorHandler

import org.jitsi.nlj.util.BufferPool

class RtpSenderImpl(
    val id: String,
    private val rtcpEventNotifier: RtcpEventNotifier,
    /**
     * The executor this class will use for its primary work (i.e. critical path
     * packet processing).  This [RtpSender] will execute a blocking queue read
     * on this executor.
     */
    val executor: ExecutorService,
    /**
     * A [ScheduledExecutorService] which can be used for less important
     * background tasks, or tasks that need to execute at some fixed delay/rate
     */
    val backgroundExecutor: ScheduledExecutorService,
    private val streamInformationStore: StreamInformationStore,
    parentLogger: Logger,
    diagnosticContext: DiagnosticContext = DiagnosticContext()
) : RtpSender() {
    private val logger = createChildLogger(parentLogger)
    private val outgoingRtpRoot: Node
    private val outgoingRtxRoot: Node
    private val outgoingRtcpRoot: Node
    private val incomingPacketQueue = PacketInfoQueue(
        "rtp-sender-incoming-packet-queue",
        executor,
        this::handlePacket,
        queueSize
    )
    var running = true
    private var localVideoSsrc: Long? = null
    private var localAudioSsrc: Long? = null
    // TODO(brian): this is changed to a handler instead of a queue because we want to use
    // a PacketQueue, and the handler for a PacketQueue must be set at the time of creation.
    // since we want the handler to be another entity (something in jvb) we just use
    // a generic handler here and then the bridge can put it into its PacketQueue and have
    // its handler (likely in another thread) grab the packet and send it out
    private var outgoingPacketHandler: PacketHandler? = null
    override val bandwidthEstimator: BandwidthEstimator = GoogleCcEstimator(diagnosticContext, logger)
    private val transportCcEngine = TransportCcEngine(bandwidthEstimator, logger)

    private val srtpEncryptWrapper = SrtpEncryptNode()
    private val srtcpEncryptWrapper = SrtcpEncryptNode()
    private val toggleablePcapWriter = ToggleablePcapWriter(logger, "$id-tx")
    private val outgoingPacketCache = PacketCacher()
    private val absSendTime = AbsSendTime(streamInformationStore)
    private val statsTracker = OutgoingStatisticsTracker()
    private val packetStreamStats = PacketStreamStatsNode()
    private val rtcpSrUpdater = RtcpSrUpdater(statsTracker)
    private val keyframeRequester = KeyframeRequester(streamInformationStore, logger)
    private val probingDataSender: ProbingDataSender

    private val nackHandler: NackHandler

    private val outputPipelineTerminationNode = object : ConsumerNode("Output pipeline termination node") {
        override fun consume(packetInfo: PacketInfo) {
            // While there's no handler set we're effectively dropping packets, so their buffers
            // should be returned.
            outgoingPacketHandler?.processPacket(packetInfo) ?: packetDiscarded(packetInfo)
        }

        override val aggregationKey = name

        override fun trace(f: () -> Unit) = f.invoke()
    }

    init {
        logger.cdebug { "Sender $id using executor ${executor.hashCode()}" }

        if (packetLossConfig.enabled) {
            logger.warn("Will simulate packet loss: $packetLossConfig")
        }

        incomingPacketQueue.setErrorHandler(queueErrorCounter)

        outgoingRtpRoot = pipeline {
            node(AudioRedHandler(streamInformationStore))
            node(HeaderExtStripper(streamInformationStore))
            node(outgoingPacketCache)
            node(absSendTime)
            node(statsTracker)
            node(TccSeqNumTagger(transportCcEngine, streamInformationStore))
            node(HeaderExtEncoder())
            node(toggleablePcapWriter.newObserverNode())
            node(srtpEncryptWrapper)
            node(packetStreamStats.createNewNode())
            node(PacketLossNode(packetLossConfig), condition = { packetLossConfig.enabled })
            node(outputPipelineTerminationNode)
        }

        outgoingRtxRoot = pipeline {
            node(RetransmissionSender(streamInformationStore, logger))
            // We want RTX packets to hook into the main RTP pipeline starting at AbsSendTime
            node(absSendTime)
        }

        nackHandler = NackHandler(outgoingPacketCache.getPacketCache(), outgoingRtxRoot, logger)
        rtcpEventNotifier.addRtcpEventListener(nackHandler)
        rtcpEventNotifier.addRtcpEventListener(transportCcEngine)

        // TODO: are we setting outgoing rtcp sequence numbers correctly? just add a simple node here to rewrite them
        outgoingRtcpRoot = pipeline {
            node(keyframeRequester)
            node(SentRtcpStats())
            // TODO(brian): not sure this is a great idea.  it works as a catch-call but can also be error-prone
            // (i found i was accidentally clobbering the sender ssrc for SRs which caused issues).  I think
            // it'd be better to notify everything creating RTCP the bridge SSRCs and then everything should be
            // responsible for setting it themselves
            simpleNode("RTCP sender ssrc setter") { packetInfo ->
                val senderSsrc = localVideoSsrc ?: return@simpleNode packetInfo
                val rtcpPacket = packetInfo.packetAs<RtcpPacket>()
                if (rtcpPacket.senderSsrc == 0L) {
                    rtcpPacket.senderSsrc = senderSsrc
                }
                packetInfo
            }
            node(rtcpSrUpdater)
            node(toggleablePcapWriter.newObserverNode())
            node(srtcpEncryptWrapper)
            node(packetStreamStats.createNewNode())
            node(PacketLossNode(packetLossConfig), condition = { packetLossConfig.enabled })
            node(outputPipelineTerminationNode)
        }

        probingDataSender = ProbingDataSender(
            packetCache = outgoingPacketCache.getPacketCache(),
            rtxDataSender = outgoingRtxRoot,
            garbageDataSender = absSendTime,
            diagnosticContext = diagnosticContext,
            streamInformationStore = streamInformationStore,
            parentLogger = logger
        )
    }

    override fun onRttUpdate(newRttMs: Double) {
        nackHandler.onRttUpdate(newRttMs)
        keyframeRequester.onRttUpdate(newRttMs)
        transportCcEngine.onRttUpdate(Duration.ofNanos((newRttMs * 1e6).toLong()))
    }

    /**
     * Insert packets into the incoming packet queue
     */
    override fun doProcessPacket(packetInfo: PacketInfo) {
        if (running) {
            val packet = packetInfo.packet
            if (packet is RtcpPacket) {
                rtcpEventNotifier.notifyRtcpSent(packet)
            }
            packetInfo.addEvent(PACKET_QUEUE_ENTRY_EVENT)
            incomingPacketQueue.add(packetInfo)
        } else {
            BufferPool.returnBuffer(packetInfo.packet.buffer)
        }
    }

    override fun sendProbing(mediaSsrcs: Collection<Long>, numBytes: Int): Int =
        probingDataSender.sendProbing(mediaSsrcs, numBytes)

    override fun onOutgoingPacket(handler: PacketHandler) {
        outgoingPacketHandler = handler
    }

    override fun setSrtpTransformers(srtpTransformers: SrtpTransformers) {
        srtpEncryptWrapper.transformer = srtpTransformers.srtpEncryptTransformer
        srtcpEncryptWrapper.transformer = srtpTransformers.srtcpEncryptTransformer
    }

    override fun requestKeyframe(mediaSsrc: Long?) {
        keyframeRequester.requestKeyframe(mediaSsrc)
    }

    override fun setFeature(feature: Features, enabled: Boolean) {
        when (feature) {
            Features.TRANSCEIVER_PCAP_DUMP -> {
                if (enabled) {
                    toggleablePcapWriter.enable()
                } else {
                    toggleablePcapWriter.disable()
                }
            }
        }
    }

    override fun isFeatureEnabled(feature: Features): Boolean {
        return when (feature) {
            Features.TRANSCEIVER_PCAP_DUMP -> toggleablePcapWriter.isEnabled()
        }
    }

    /**
     * Handles packets that have gone through the incoming queue and sends them
     * through the sender pipeline
     */
    private fun handlePacket(packetInfo: PacketInfo): Boolean {
        return if (running) {
            packetInfo.addEvent(PACKET_QUEUE_EXIT_EVENT)

            val root = when (packetInfo.packet) {
                is RtcpPacket -> outgoingRtcpRoot
                else -> outgoingRtpRoot
            }
            root.processPacket(packetInfo)
            true
        } else {
            BufferPool.returnBuffer(packetInfo.packet.buffer)
            false
        }
    }

    override fun getStreamStats() = statsTracker.getSnapshot()

    override fun getPacketStreamStats() = packetStreamStats.snapshot()

    override fun getTransportCcEngineStats() = transportCcEngine.getStatistics()

    override fun handleEvent(event: Event) {
        when (event) {
            is SetLocalSsrcEvent -> {
                when (event.mediaType) {
                    MediaType.VIDEO -> localVideoSsrc = event.ssrc
                    MediaType.AUDIO -> localAudioSsrc = event.ssrc
                    else -> {}
                }
            }
        }
        NodeEventVisitor(event).reverseVisit(outputPipelineTerminationNode)
        probingDataSender.handleEvent(event)
    }

    override fun getNodeStats(): NodeStatsBlock = NodeStatsBlock("RTP sender $id").apply {
        addBlock(nackHandler.getNodeStats())
        addBlock(probingDataSender.getNodeStats())
        addJson("packetQueue", incomingPacketQueue.debugState)
        NodeStatsVisitor(this).reverseVisit(outputPipelineTerminationNode)

        addString("running", running.toString())
        addString("localVideoSsrc", localVideoSsrc?.toString() ?: "null")
        addString("localAudioSsrc", localAudioSsrc?.toString() ?: "null")
        addJson("transportCcEngine", transportCcEngine.getStatistics().toJson())
        addJson("Bandwidth Estimation", bandwidthEstimator.getStats().toJson())
    }

    override fun stop() {
        running = false
    }

    override fun tearDown() {
        logger.info("Tearing down")
        NodeTeardownVisitor().reverseVisit(outputPipelineTerminationNode)
        incomingPacketQueue.close()
        toggleablePcapWriter.disable()
    }

    companion object {
        val queueErrorCounter = CountingErrorHandler()

        private const val PACKET_QUEUE_ENTRY_EVENT = "Entered RTP sender incoming queue"
        private const val PACKET_QUEUE_EXIT_EVENT = "Exited RTP sender incoming queue"

        private val queueSize: Int by config("jmt.transceiver.send.queue-size".from(JitsiConfig.newConfig))

        /**
         * Configuration for the packet loss to introduce in the send pipeline (for debugging/testing purposes).
         */
        private val packetLossConfig = PacketLossConfig("jmt.debug.packet-loss.outgoing")
    }
}
