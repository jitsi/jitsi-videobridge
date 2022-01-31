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

package org.jitsi.videobridge.relay

import org.jitsi.nlj.AudioLevelListener
import org.jitsi.nlj.Event
import org.jitsi.nlj.Features
import org.jitsi.nlj.PacketHandler
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.RtpReceiver
import org.jitsi.nlj.RtpReceiverEventHandler
import org.jitsi.nlj.rtcp.CompoundRtcpParser
import org.jitsi.nlj.rtcp.RtcpEventNotifier
import org.jitsi.nlj.rtcp.RtcpRrGenerator
import org.jitsi.nlj.rtp.AudioRtpPacket
import org.jitsi.nlj.rtp.VideoRtpPacket
import org.jitsi.nlj.srtp.SrtpTransformers
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.stats.RtpReceiverStats
import org.jitsi.nlj.transform.NodeEventVisitor
import org.jitsi.nlj.transform.NodeStatsVisitor
import org.jitsi.nlj.transform.NodeTeardownVisitor
import org.jitsi.nlj.transform.node.ConsumerNode
import org.jitsi.nlj.transform.node.Node
import org.jitsi.nlj.transform.node.PacketStreamStatsNode
import org.jitsi.nlj.transform.node.RtpParser
import org.jitsi.nlj.transform.node.SrtcpDecryptNode
import org.jitsi.nlj.transform.node.SrtpDecryptNode
import org.jitsi.nlj.transform.node.ToggleablePcapWriter
import org.jitsi.nlj.transform.node.incoming.AudioLevelReader
import org.jitsi.nlj.transform.node.incoming.BitrateCalculator
import org.jitsi.nlj.transform.node.incoming.DuplicateTermination
import org.jitsi.nlj.transform.node.incoming.IncomingStatisticsTracker
import org.jitsi.nlj.transform.node.incoming.RetransmissionRequesterNode
import org.jitsi.nlj.transform.node.incoming.RtcpTermination
import org.jitsi.nlj.transform.node.incoming.RtxHandler
import org.jitsi.nlj.transform.node.incoming.VideoBitrateCalculator
import org.jitsi.nlj.transform.node.incoming.VideoParser
import org.jitsi.nlj.transform.node.incoming.VideoQualityLayerLookup
import org.jitsi.nlj.transform.packetPath
import org.jitsi.nlj.transform.pipeline
import org.jitsi.nlj.util.PacketInfoQueue
import org.jitsi.nlj.util.PacketPredicate
import org.jitsi.nlj.util.ReadOnlyStreamInformationStore
import org.jitsi.rtp.Packet
import org.jitsi.rtp.extensions.looksLikeRtcp
import org.jitsi.rtp.extensions.looksLikeRtp
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.utils.OrderedJsonObject
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.utils.queue.CountingErrorHandler
import org.jitsi.videobridge.octo.config.OctoConfig
import org.jitsi.videobridge.util.ByteBufferPool
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicBoolean

class RelayRtpReceiver(
    id: String,
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
    val streamInformationStore: ReadOnlyStreamInformationStore,
    val eventHandler: RtpReceiverEventHandler?,
    parentLogger: Logger
) : RtpReceiver() {
    private val logger = createChildLogger(parentLogger)

    private val running = AtomicBoolean(true)

    private val incomingPacketQueue = PacketInfoQueue(
        "relay-transceiver-incoming-packet-queue",
        executor,
        this::handleIncomingPacket,
        OctoConfig.config.recvQueueSize // TODO add RelayConfig
    ).apply {
        setErrorHandler(queueErrorCounter)
    }

    private val srtpDecryptWrapper = SrtpDecryptNode()
    private val srtcpDecryptWrapper = SrtcpDecryptNode()

    private val audioLevelReader = AudioLevelReader(streamInformationStore).apply {
        audioLevelListener = object : AudioLevelListener {
            override fun onLevelReceived(sourceSsrc: Long, level: Long) {
                eventHandler?.audioLevelReceived(sourceSsrc, level)
            }
        }
    }

    private val toggleablePcapWriter = ToggleablePcapWriter(logger, "$id-rx")
    private val statsTracker = IncomingStatisticsTracker(streamInformationStore)
    private val packetStreamStats = PacketStreamStatsNode()
    private val rtcpRrGenerator = RtcpRrGenerator(backgroundExecutor, rtcpSender, statsTracker) {
        emptyList() // TODO remove the need for this
    }
    private val rtcpTermination = RtcpTermination(rtcpEventNotifier, logger)

    private val audioBitrateCalculator = BitrateCalculator("Audio bitrate calculator")
    private val videoBitrateCalculator = VideoBitrateCalculator(logger)

    private val videoParser = VideoParser(streamInformationStore, logger)

    private val retransmissionRequester = RetransmissionRequesterNode(rtcpSender, backgroundExecutor, logger)

    override var packetHandler: PacketHandler? = null

    private val pipelineTerminationNode = object : ConsumerNode("Relay receiver termination node") {
        override fun consume(packetInfo: PacketInfo) {
            packetHandler?.processPacket(packetInfo) ?: packetDiscarded(packetInfo)
        }

        override fun trace(f: () -> Unit) = f.invoke()
    }

    /**
     * The tree of [Node]s which handles incoming packets.
     */
    private val inputTreeRoot: Node = pipeline {
        node(packetStreamStats)
        demux("RTP/RTCP") {
            packetPath {
                name = "RTP"
                predicate = PacketPredicate(Packet::looksLikeRtp)
                path = pipeline {
                    node(RtpParser(streamInformationStore, logger))
                    node(srtpDecryptWrapper)
                    node(toggleablePcapWriter.newObserverNode())
                    node(statsTracker)
                    demux("Audio/Video") {
                        packetPath {
                            name = "Video"
                            predicate = PacketPredicate { it is VideoRtpPacket }
                            path = pipeline {
                                node(RtxHandler(streamInformationStore, logger))
                                node(DuplicateTermination())
                                node(retransmissionRequester)
                                node(videoParser)
                                node(VideoQualityLayerLookup(logger))
                                node(videoBitrateCalculator)
                                node(pipelineTerminationNode)
                            }
                        }
                        packetPath {
                            name = "Audio"
                            predicate = PacketPredicate { it is AudioRtpPacket }
                            path = pipeline {
                                node(audioLevelReader)
                                node(audioBitrateCalculator)
                                node(pipelineTerminationNode)
                            }
                        }
                    }
                }
            }
            packetPath {
                name = "RTCP"
                predicate = PacketPredicate(Packet::looksLikeRtcp)
                path = pipeline {
                    node(srtcpDecryptWrapper)
                    node(toggleablePcapWriter.newObserverNode())
                    node(CompoundRtcpParser(logger))
                    node(rtcpTermination)
                    node(pipelineTerminationNode)
                }
            }
        }
    }

    override fun enqueuePacket(p: PacketInfo) {
        if (running.get()) {
            p.addEvent(PACKET_QUEUE_ENTRY_EVENT)
            incomingPacketQueue.add(p)
        } else {
            ByteBufferPool.returnBuffer(p.packet.buffer)
        }
    }

    private fun handleIncomingPacket(packetInfo: PacketInfo): Boolean {
        packetInfo.addEvent(PACKET_QUEUE_EXIT_EVENT)
        processPacket(packetInfo)
        return true
    }

    override fun doProcessPacket(packetInfo: PacketInfo) = inputTreeRoot.processPacket(packetInfo)

    override fun handleEvent(event: Event) {
        NodeEventVisitor(event).visit(inputTreeRoot)
    }

    override fun getNodeStats(): NodeStatsBlock = NodeStatsBlock("Relay receiver").apply {
        addBlock(super.getNodeStats())
        NodeStatsVisitor(this).visit(inputTreeRoot)
    }

    override fun forceMuteAudio(shouldMute: Boolean) {
        // No-op - force-mute is done by the bridge local to the endpoint being muted
    }

    override fun forceMuteVideo(shouldMute: Boolean) {
        // No-op, likewise
    }

    override fun onRttUpdate(newRttMs: Double) {
        TODO("Not yet implemented")
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

    override fun setSrtpTransformers(srtpTransformers: SrtpTransformers) {
        srtpDecryptWrapper.transformer = srtpTransformers.srtpDecryptTransformer
        srtcpDecryptWrapper.transformer = srtpTransformers.srtcpDecryptTransformer
    }

    override fun getStats(): RtpReceiverStats = RtpReceiverStats(
        incomingStats = statsTracker.getSnapshot(),
        packetStreamStats = packetStreamStats.snapshot(),
        videoParserStats = videoParser.getStats()
    )

    override fun stop() {
        running.set(false)
        rtcpRrGenerator.running = false
        retransmissionRequester.stop()
    }

    override fun tearDown() {
        NodeTeardownVisitor().visit(inputTreeRoot)
        incomingPacketQueue.close()
        toggleablePcapWriter.disable()
    }

    override fun isReceivingAudio(): Boolean = audioBitrateCalculator.active
    override fun isReceivingVideo(): Boolean = videoBitrateCalculator.active

    fun getDebugState(): OrderedJsonObject = OrderedJsonObject().apply {
        putAll(getNodeStats().toJson())
        put("incomingPacketQueue", incomingPacketQueue.debugState)
    }

    companion object {
        private const val PACKET_QUEUE_ENTRY_EVENT = "Entered Relay RTP receiver incoming queue"
        private const val PACKET_QUEUE_EXIT_EVENT = "Exited Relay RTP receiver incoming queue"

        @JvmField
        val queueErrorCounter = CountingErrorHandler()
    }
}
