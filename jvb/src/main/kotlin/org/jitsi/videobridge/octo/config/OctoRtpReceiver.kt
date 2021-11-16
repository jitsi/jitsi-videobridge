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

package org.jitsi.videobridge.octo.config

import org.jitsi.nlj.AudioLevelListener
import org.jitsi.nlj.Event
import org.jitsi.nlj.Features
import org.jitsi.nlj.PacketHandler
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.RtpReceiver
import org.jitsi.nlj.TransceiverEventHandler
import org.jitsi.nlj.rtcp.SingleRtcpParser
import org.jitsi.nlj.rtp.AudioRtpPacket
import org.jitsi.nlj.rtp.VideoRtpPacket
import org.jitsi.nlj.srtp.SrtpTransformers
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.stats.RtpReceiverStats
import org.jitsi.nlj.transform.NodeEventVisitor
import org.jitsi.nlj.transform.NodeStatsVisitor
import org.jitsi.nlj.transform.node.ConsumerNode
import org.jitsi.nlj.transform.node.Node
import org.jitsi.nlj.transform.node.RtpParser
import org.jitsi.nlj.transform.node.incoming.AudioLevelReader
import org.jitsi.nlj.transform.node.incoming.BitrateCalculator
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
import org.jitsi.utils.OrderedJsonObject
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.utils.queue.CountingErrorHandler
import org.jitsi.videobridge.util.ByteBufferPool
import org.jitsi.videobridge.util.TaskPools
import java.util.concurrent.atomic.AtomicBoolean

/**
 * An [RtpReceiver] for all data that comes in over the Octo link.
 */
class OctoRtpReceiver(
    val streamInformationStore: ReadOnlyStreamInformationStore,
    val eventHandler: TransceiverEventHandler?,
    parentLogger: Logger
) : RtpReceiver() {
    private val logger = createChildLogger(parentLogger)

    private val running = AtomicBoolean(true)

    private val incomingPacketQueue = PacketInfoQueue(
        "octo-transceiver-incoming-packet-queue",
        TaskPools.CPU_POOL,
        this::handleIncomingPacket,
        OctoConfig.config.recvQueueSize
    ).apply {
        setErrorHandler(queueErrorCounter)
    }

    private val audioLevelReader = AudioLevelReader(streamInformationStore).apply {
        audioLevelListener = object : AudioLevelListener {
            override fun onLevelReceived(sourceSsrc: Long, level: Long) {
                eventHandler?.audioLevelReceived(sourceSsrc, level)
            }
        }
    }

    private val audioBitrateCalculator = BitrateCalculator("Audio bitrate calculator")
    private val videoBitrateCalculator = VideoBitrateCalculator(logger)

    override var packetHandler: PacketHandler? = null

    private val pipelineTerminationNode = object : ConsumerNode("Octo receiver termination node") {
        override fun consume(packetInfo: PacketInfo) {
            packetHandler?.processPacket(packetInfo) ?: packetDiscarded(packetInfo)
        }

        override fun trace(f: () -> Unit) = f.invoke()
    }

    /**
     * The tree of [Node]s which handles incoming packets.
     */
    private val inputTreeRoot: Node = pipeline {
        demux("RTP/RTCP") {
            packetPath {
                name = "RTP"
                predicate = PacketPredicate(Packet::looksLikeRtp)
                path = pipeline {
                    node(RtpParser(streamInformationStore, logger))
                    demux("Audio/Video") {
                        packetPath {
                            name = "Video"
                            predicate = PacketPredicate { it is VideoRtpPacket }
                            path = pipeline {
                                node(VideoParser(streamInformationStore, logger))
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
                    // We currently only have single RTCP packets in Octo
                    node(SingleRtcpParser(logger))
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

    override fun getNodeStats(): NodeStatsBlock = NodeStatsBlock("Octo receiver").apply {
        addBlock(super.getNodeStats())
        NodeStatsVisitor(this).visit(inputTreeRoot)
    }

    override fun forceMuteAudio(shouldMute: Boolean) {
        // No op(?)
    }

    override fun forceMuteVideo(shouldMute: Boolean) {
        // noop
    }

    override fun onRttUpdate(newRttMs: Double) {
        TODO("Not yet implemented")
    }

    override fun setFeature(feature: Features, enabled: Boolean) {
        TODO("Not yet implemented")
    }

    override fun isFeatureEnabled(feature: Features): Boolean {
        TODO("Not yet implemented")
    }

    override fun setSrtpTransformers(srtpTransformers: SrtpTransformers) {}
    override fun getStats(): RtpReceiverStats {
        TODO("Not yet implemented")
    }

    override fun stop() {
        running.set(false)
    }

    override fun tearDown() {
        incomingPacketQueue.close()
    }

    override fun isReceivingAudio(): Boolean = audioBitrateCalculator.active
    override fun isReceivingVideo(): Boolean = videoBitrateCalculator.active

    fun getDebugState(): OrderedJsonObject = OrderedJsonObject().apply {
        putAll(getNodeStats().toJson())
        put("incomingPacketQueue", incomingPacketQueue.debugState)
    }

    companion object {
        private const val PACKET_QUEUE_ENTRY_EVENT = "Entered Octo RTP receiver incoming queue"
        private const val PACKET_QUEUE_EXIT_EVENT = "Exited Octo RTP receiver incoming queue"

        @JvmField
        val queueErrorCounter = CountingErrorHandler()
    }
}
