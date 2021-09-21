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
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService
import org.jitsi.nlj.rtcp.CompoundRtcpParser
import org.jitsi.nlj.rtcp.RembHandler
import org.jitsi.nlj.rtcp.RtcpEventNotifier
import org.jitsi.nlj.rtcp.RtcpRrGenerator
import org.jitsi.nlj.rtp.AudioRtpPacket
import org.jitsi.nlj.rtp.VideoRtpPacket
import org.jitsi.nlj.rtp.bandwidthestimation.BandwidthEstimator
import org.jitsi.nlj.srtp.SrtpTransformers
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.NodeEventVisitor
import org.jitsi.nlj.transform.NodeStatsVisitor
import org.jitsi.nlj.transform.NodeTeardownVisitor
import org.jitsi.nlj.transform.node.ConsumerNode
import org.jitsi.nlj.transform.node.Node
import org.jitsi.nlj.transform.node.PacketLossConfig
import org.jitsi.nlj.transform.node.PacketLossNode
import org.jitsi.nlj.transform.node.PacketStreamStatsNode
import org.jitsi.nlj.transform.node.RtpParser
import org.jitsi.nlj.transform.node.SrtcpDecryptNode
import org.jitsi.nlj.transform.node.SrtpDecryptNode
import org.jitsi.nlj.transform.node.ToggleablePcapWriter
import org.jitsi.nlj.transform.node.incoming.AudioLevelReader
import org.jitsi.nlj.transform.node.incoming.BitrateCalculator
import org.jitsi.nlj.transform.node.incoming.DuplicateTermination
import org.jitsi.nlj.transform.node.incoming.IncomingStatisticsTracker
import org.jitsi.nlj.transform.node.incoming.VideoQualityLayerLookup
import org.jitsi.nlj.transform.node.incoming.PaddingTermination
import org.jitsi.nlj.transform.node.incoming.RemoteBandwidthEstimator
import org.jitsi.nlj.transform.node.incoming.RetransmissionRequesterNode
import org.jitsi.nlj.transform.node.incoming.RtcpTermination
import org.jitsi.nlj.transform.node.incoming.RtxHandler
import org.jitsi.nlj.transform.node.incoming.DiscardableDiscarder
import org.jitsi.nlj.transform.node.incoming.TccGeneratorNode
import org.jitsi.nlj.transform.node.incoming.VideoBitrateCalculator
import org.jitsi.nlj.transform.node.incoming.VideoParser
import org.jitsi.nlj.transform.node.incoming.VideoMuteNode
import org.jitsi.nlj.transform.packetPath
import org.jitsi.nlj.transform.pipeline
import org.jitsi.nlj.util.PacketInfoQueue
import org.jitsi.nlj.util.PacketPredicate
import org.jitsi.nlj.util.ReadOnlyStreamInformationStore
import org.jitsi.utils.logging2.cdebug
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.rtp.Packet
import org.jitsi.rtp.extensions.looksLikeRtcp
import org.jitsi.rtp.extensions.looksLikeRtp
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.queue.CountingErrorHandler

import org.jitsi.nlj.util.Bandwidth
import org.jitsi.nlj.util.BufferPool

class RtpReceiverImpl @JvmOverloads constructor(
    val id: String,
    /**
     * A function to be used when these receiver wants to send RTCP packets to the
     * participant it's receiving data from (NACK packets, for example)
     */
    private val rtcpSender: (RtcpPacket) -> Unit = {},
    rtcpEventNotifier: RtcpEventNotifier,
    /**
     * The executor this class will use for its primary work (i.e. critical path
     * packet processing).  This [RtpReceiver] will execute a blocking queue read
     * on this executor.
     */
    private val executor: ExecutorService,
    /**
     * A [ScheduledExecutorService] which can be used for less important
     * background tasks, or tasks that need to execute at some fixed delay/rate
     */
    private val backgroundExecutor: ScheduledExecutorService,
    streamInformationStore: ReadOnlyStreamInformationStore,
    private val eventHandler: RtpReceiverEventHandler,
    parentLogger: Logger,
    diagnosticContext: DiagnosticContext = DiagnosticContext()
) : RtpReceiver() {
    private val logger = createChildLogger(parentLogger)
    private var running: Boolean = true
    private val inputTreeRoot: Node
    private val queueSize: Int by config("jmt.transceiver.recv.queue-size".from(JitsiConfig.newConfig))
    private val incomingPacketQueue = PacketInfoQueue(
        "rtp-receiver-incoming-packet-queue",
        executor,
        this::handleIncomingPacket,
        queueSize
    )
    private val srtpDecryptWrapper = SrtpDecryptNode()
    private val srtcpDecryptWrapper = SrtcpDecryptNode()
    private val tccGenerator = TccGeneratorNode(rtcpSender, streamInformationStore, logger)
    private val remoteBandwidthEstimator = RemoteBandwidthEstimator(streamInformationStore, logger, diagnosticContext)
    private val audioLevelReader = AudioLevelReader(streamInformationStore).apply {
        audioLevelListener = object : AudioLevelListener {
            override fun onLevelReceived(sourceSsrc: Long, level: Long) {
                eventHandler.audioLevelReceived(sourceSsrc, level)
            }
        }
    }

    private val videoMuteNode = VideoMuteNode()

    private val silenceDiscarder = DiscardableDiscarder("Silence discarder", false)
    private val paddingOnlyDiscarder = DiscardableDiscarder("Padding-only discarder", true)
    private val statsTracker = IncomingStatisticsTracker(streamInformationStore)
    private val packetStreamStats = PacketStreamStatsNode()
    private val rtcpRrGenerator = RtcpRrGenerator(backgroundExecutor, rtcpSender, statsTracker) {
        remoteBandwidthEstimator.createRemb()?.let {
            listOf(it)
        } ?: emptyList()
    }
    private val rtcpTermination = RtcpTermination(rtcpEventNotifier, logger)
    private val retransmissionRequester = RetransmissionRequesterNode(rtcpSender, backgroundExecutor, logger)
    private val rembHandler = RembHandler(streamInformationStore, logger).apply {
        addListener(object : BandwidthEstimator.Listener {
            override fun bandwidthEstimationChanged(newValue: Bandwidth) {
                eventHandler.bandwidthEstimationChanged(newValue)
            }
        })
    }
    private val toggleablePcapWriter = ToggleablePcapWriter(logger, "$id-rx")
    private val videoBitrateCalculator = VideoBitrateCalculator(parentLogger)
    private val audioBitrateCalculator = BitrateCalculator("Audio bitrate calculator")

    override fun isReceivingAudio() = audioBitrateCalculator.active
    override fun isReceivingVideo() = videoBitrateCalculator.active

    companion object {
        val queueErrorCounter = CountingErrorHandler()

        private const val PACKET_QUEUE_ENTRY_EVENT = "Entered RTP receiver incoming queue"
        private const val PACKET_QUEUE_EXIT_EVENT = "Exited RTP receiver incoming queue"

        /**
         * Configuration for the packet loss to introduce in the receive pipeline (for debugging/testing purposes).
         */
        private val packetLossConfig = PacketLossConfig("jmt.debug.packet-loss.incoming")
    }

    /**
     * [packetHandler] will be invoked with RTP packets that have made
     * it through the entire receive pipeline.  Some external entity should
     * assign it to a [PacketHandler] with appropriate logic.
     */
    override var packetHandler: PacketHandler? = null

    /**
     * The [packetHandler] can be re-assigned at any time, but it should maintain
     * its place in the receive pipeline.  To support both keeping it in the same
     * place and allowing it to be re-assigned, we wrap it with this.
     */
    private val packetHandlerWrapper = object : ConsumerNode("Input pipeline termination node") {
        override fun consume(packetInfo: PacketInfo) {
            // When there's no handler set we're effectively dropping packets, so their buffers
            // should be returned.
            packetHandler?.processPacket(packetInfo) ?: packetDiscarded(packetInfo)
        }

        override val aggregationKey: String = name

        override fun trace(f: () -> Unit) = f.invoke()
    }

    init {
        logger.cdebug { "using executor ${executor.hashCode()}" }

        if (packetLossConfig.enabled) {
            logger.warn("Will simulate packet loss: $packetLossConfig")
        }

        rtcpEventNotifier.addRtcpEventListener(rtcpRrGenerator)
        rtcpEventNotifier.addRtcpEventListener(rembHandler)

        incomingPacketQueue.setErrorHandler(queueErrorCounter)

        inputTreeRoot = pipeline {
            node(packetStreamStats)
            demux("SRTP/SRTCP") {
                packetPath {
                    name = "SRTP path"
                    predicate = PacketPredicate(Packet::looksLikeRtp)
                    path = pipeline {
                        node(PacketLossNode(packetLossConfig), condition = { packetLossConfig.enabled })
                        node(RtpParser(streamInformationStore, logger))
                        node(tccGenerator)
                        node(remoteBandwidthEstimator)
                        // TODO: temporarily putting the audioLevelReader node here such that we can determine whether
                        // or not a packet should be discarded before doing SRTP. audioLevelReader has been moved here
                        // (instead of introducing a different class to read audio levels) to avoid parsing the RTP
                        // header extensions twice (which is expensive). In the future we will parse and cache the
                        // header extensions to make this lookup more efficient, at which time we could move
                        // audioLevelReader back to where it was (in the audio path) and add a new node here which would
                        // check for different discard conditions (i.e. checking the audio level for silence)
                        node(audioLevelReader)
                        node(videoMuteNode)
                        node(srtpDecryptWrapper)
                        node(toggleablePcapWriter.newObserverNode())
                        node(statsTracker)
                        node(PaddingTermination(logger))
                        demux("Media Type") {
                            packetPath {
                                name = "Audio path"
                                predicate = PacketPredicate { it is AudioRtpPacket }
                                path = pipeline {
                                    node(silenceDiscarder)
                                    node(audioBitrateCalculator)
                                    node(packetHandlerWrapper)
                                }
                            }
                            packetPath {
                                name = "Video path"
                                predicate = PacketPredicate { it is VideoRtpPacket }
                                path = pipeline {
                                    node(RtxHandler(streamInformationStore, logger))
                                    node(DuplicateTermination())
                                    node(retransmissionRequester)
                                    node(paddingOnlyDiscarder)
                                    node(VideoParser(streamInformationStore, logger))
                                    node(VideoQualityLayerLookup(logger))
                                    node(videoBitrateCalculator)
                                    node(packetHandlerWrapper)
                                }
                            }
                        }
                    }
                }
                packetPath {
                    name = "SRTCP path"
                    predicate = PacketPredicate(Packet::looksLikeRtcp)
                    path = pipeline {
                        node(srtcpDecryptWrapper)
                        node(toggleablePcapWriter.newObserverNode())
                        node(CompoundRtcpParser(logger))
                        node(rtcpTermination)
                        node(packetHandlerWrapper)
                    }
                }
            }
        }
    }

    private fun handleIncomingPacket(packet: PacketInfo): Boolean {
        return if (running) {
            packet.addEvent(PACKET_QUEUE_EXIT_EVENT)
            processPacket(packet)
            true
        } else {
            BufferPool.returnBuffer(packet.packet.buffer)
            false
        }
    }

    override fun doProcessPacket(packetInfo: PacketInfo) = inputTreeRoot.processPacket(packetInfo)

    override fun getNodeStats(): NodeStatsBlock = NodeStatsBlock("RTP receiver $id").apply {
        addBlock(super.getNodeStats())
        addBoolean("running", running)
        NodeStatsVisitor(this).visit(inputTreeRoot)
    }

    override fun enqueuePacket(p: PacketInfo) {
//        logger.cinfo { "Receiver $id enqueing data" }
        if (running) {
            p.addEvent(PACKET_QUEUE_ENTRY_EVENT)
            incomingPacketQueue.add(p)
        } else {
            BufferPool.returnBuffer(p.packet.buffer)
        }
    }

    override fun setSrtpTransformers(srtpTransformers: SrtpTransformers) {
        srtpDecryptWrapper.transformer = srtpTransformers.srtpDecryptTransformer
        srtcpDecryptWrapper.transformer = srtpTransformers.srtcpDecryptTransformer
    }

    override fun handleEvent(event: Event) {
        NodeEventVisitor(event).visit(inputTreeRoot)
    }

    override fun getStreamStats() = statsTracker.getSnapshot()

    override fun getPacketStreamStats() = packetStreamStats.snapshot()

    override fun forceMuteAudio(shouldMute: Boolean) {
        audioLevelReader.forceMute = shouldMute
    }

    override fun forceMuteVideo(shouldMute: Boolean) {
        videoMuteNode.forceMute = shouldMute
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

    override fun stop() {
        running = false
        rtcpRrGenerator.running = false
        retransmissionRequester.stop()
    }

    override fun tearDown() {
        logger.info("Tearing down")
        NodeTeardownVisitor().visit(inputTreeRoot)
        incomingPacketQueue.close()
        toggleablePcapWriter.disable()
    }

    override fun onRttUpdate(newRttMs: Double) {
        remoteBandwidthEstimator.onRttUpdate(newRttMs)
    }
}
