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
import org.jitsi.nlj.PacketHandler
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.RtpReceiver
import org.jitsi.nlj.rtcp.SingleRtcpParser
import org.jitsi.nlj.rtp.AudioRtpPacket
import org.jitsi.nlj.rtp.VideoRtpPacket
import org.jitsi.nlj.rtp.bandwidthestimation.BandwidthEstimator
import org.jitsi.nlj.srtp.SrtpTransformers
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.stats.PacketStreamStats
import org.jitsi.nlj.transform.NodeEventVisitor
import org.jitsi.nlj.transform.NodeStatsVisitor
import org.jitsi.nlj.transform.node.ConsumerNode
import org.jitsi.nlj.transform.node.Node
import org.jitsi.nlj.transform.node.RtpParser
import org.jitsi.nlj.transform.node.incoming.AudioLevelReader
import org.jitsi.nlj.transform.node.incoming.IncomingStatisticsSnapshot
import org.jitsi.nlj.transform.node.incoming.VideoParser
import org.jitsi.nlj.transform.node.incoming.Vp8Parser
import org.jitsi.nlj.transform.packetPath
import org.jitsi.nlj.transform.pipeline
import org.jitsi.nlj.util.OrderedJsonObject
import org.jitsi.nlj.util.PacketInfoQueue
import org.jitsi.nlj.util.PacketPredicate
import org.jitsi.nlj.util.ReadOnlyStreamInformationStore
import org.jitsi.rtp.Packet
import org.jitsi.rtp.extensions.looksLikeRtcp
import org.jitsi.rtp.extensions.looksLikeRtp
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.utils.queue.CountingErrorHandler
import org.jitsi.videobridge.octo.config.OctoConfig.Config
import org.jitsi.videobridge.util.ByteBufferPool
import org.jitsi.videobridge.util.TaskPools
import java.util.concurrent.atomic.AtomicBoolean

/**
 * An [RtpReceiver] for all data that comes in over the Octo link.
 */
class OctoRtpReceiver(
    val streamInformationStore: ReadOnlyStreamInformationStore,
    parentLogger: Logger
) : RtpReceiver() {
    private val logger = createChildLogger(parentLogger)

    private val running = AtomicBoolean(true)

    private val incomingPacketQueue = PacketInfoQueue(
        "octo-transceiver-incoming-packet-queue",
        TaskPools.CPU_POOL,
        this::handleIncomingPacket,
        Config.recvQueueSize()
    ).apply {
        setErrorHandler(queueErrorCounter)
    }

    private val audioLevelReader = AudioLevelReader(streamInformationStore)

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
                                node(Vp8Parser(logger))
                                node(pipelineTerminationNode)
                            }
                        }
                        packetPath {
                            name = "Audio"
                            predicate = PacketPredicate { it is AudioRtpPacket }
                            path = pipeline {
                                node(audioLevelReader)
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
                }
            }
        }
    }

    override fun enqueuePacket(p: PacketInfo) {
        if (running.get()) {
            incomingPacketQueue.add(p)
        } else {
            ByteBufferPool.returnBuffer(p.packet.buffer)
        }
    }

    private fun handleIncomingPacket(packetInfo: PacketInfo): Boolean {
        processPacket(packetInfo)
        return true
    }

    override fun doProcessPacket(packetInfo: PacketInfo) = inputTreeRoot.processPacket(packetInfo)

    override fun handleEvent(event: Event) {
        NodeEventVisitor(event).visit(inputTreeRoot)
    }

    override fun getNodeStats(): NodeStatsBlock = NodeStatsBlock("Octo receiver").apply {
        NodeStatsVisitor(this).visit(inputTreeRoot)
    }

    override fun forceMuteAudio(shouldMute: Boolean) {
        // No op(?)
    }

    override fun getPacketStreamStats(): PacketStreamStats.Snapshot {
        TODO("Not yet implemented")
    }

    override fun getStreamStats(): IncomingStatisticsSnapshot {
        TODO("Not yet implemented")
    }

    override fun isReceivingAudio(): Boolean = true

    override fun isReceivingVideo(): Boolean = true

    override fun onBandwidthEstimateChanged(listener: BandwidthEstimator.Listener) {
        TODO("Not yet implemented")
    }

    override fun onRttUpdate(newRttMs: Double) {
        TODO("Not yet implemented")
    }

    override fun setAudioLevelListener(audioLevelListener: AudioLevelListener) {
        audioLevelReader.audioLevelListener = audioLevelListener
    }

    override fun setSrtpTransformers(srtpTransformers: SrtpTransformers) {}

    override fun stop() {
        running.set(false)
    }

    override fun tearDown() {
        incomingPacketQueue.close()
    }

    fun getDebugState(): OrderedJsonObject = OrderedJsonObject().apply {
        putAll(getNodeStats().toJson())
        put("incomingPacketQueue", incomingPacketQueue.debugState)
    }

    companion object {
        @JvmField
        val queueErrorCounter = CountingErrorHandler()
    }
}
