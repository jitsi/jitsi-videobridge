/*
 * Copyright @ 2018 Atlassian Pty Ltd
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

import org.jitsi.nlj.rtcp.RtcpEventNotifier
import org.jitsi.nlj.rtcp.RtcpRrGenerator
import org.jitsi.nlj.rtp.AudioRtpPacket
import org.jitsi.nlj.rtp.VideoRtpPacket
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.NodeEventVisitor
import org.jitsi.nlj.transform.NodeStatsVisitor
import org.jitsi.nlj.transform.NodeTeardownVisitor
import org.jitsi.nlj.transform.node.ConsumerNode
import org.jitsi.nlj.transform.node.Node
import org.jitsi.nlj.transform.node.PacketParser
import org.jitsi.nlj.transform.node.RtpParser
import org.jitsi.nlj.transform.node.incoming.AudioLevelReader
import org.jitsi.nlj.transform.node.incoming.IncomingStatisticsSnapshot
import org.jitsi.nlj.transform.node.incoming.IncomingStatisticsTracker
import org.jitsi.nlj.transform.node.incoming.PaddingTermination
import org.jitsi.nlj.transform.node.incoming.RetransmissionRequesterNode
import org.jitsi.nlj.transform.node.incoming.RtcpTermination
import org.jitsi.nlj.transform.node.incoming.RtxHandler
import org.jitsi.nlj.transform.node.incoming.SrtcpTransformerDecryptNode
import org.jitsi.nlj.transform.node.incoming.SrtpTransformerDecryptNode
import org.jitsi.nlj.transform.node.incoming.TccGeneratorNode
import org.jitsi.nlj.transform.node.incoming.VideoBitrateCalculator
import org.jitsi.nlj.transform.node.incoming.VideoParser
import org.jitsi.nlj.transform.node.incoming.Vp8Parser
import org.jitsi.nlj.transform.packetPath
import org.jitsi.nlj.transform.pipeline
import org.jitsi.nlj.util.PacketInfoQueue
import org.jitsi.nlj.util.Util.Companion.getMbps
import org.jitsi.nlj.util.cinfo
import org.jitsi.nlj.util.getLogger
import org.jitsi.rtp.PacketPredicate
import org.jitsi.rtp.rtcp.CompoundRtcpPacket
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.util.RTCPUtils
import org.jitsi.utils.logging.Logger
import org.jitsi_modified.impl.neomedia.rtp.TransportCCEngine
import org.jitsi_modified.impl.neomedia.transform.SinglePacketTransformer
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService

class RtpReceiverImpl @JvmOverloads constructor(
    val id: String,
    /**
     * A function to be used when these receiver wants to send RTCP packets to the
     * participant it's receiving data from (NACK packets, for example)
     */
    private val rtcpSender: (RtcpPacket) -> Unit = {},
    transportCcEngine: TransportCCEngine? = null,
    private val rtcpEventNotifier: RtcpEventNotifier,
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
    logLevelDelegate: Logger? = null
) : RtpReceiver() {
    private val logger = getLogger(classLogger, logLevelDelegate)
    private var running: Boolean = true
    private val inputTreeRoot: Node
    private val incomingPacketQueue
            = PacketInfoQueue("rtp-receiver-incoming-packet-queue", executor, this::handleIncomingPacket)
    private val srtpDecryptWrapper = SrtpTransformerDecryptNode()
    private val srtcpDecryptWrapper = SrtcpTransformerDecryptNode()
    private val tccGenerator = TccGeneratorNode(rtcpSender, backgroundExecutor)
    private val audioLevelReader = AudioLevelReader()
    private val statsTracker = IncomingStatisticsTracker()
    private val rtcpRrGenerator = RtcpRrGenerator(backgroundExecutor, rtcpSender, statsTracker)
    private val rtcpTermination = RtcpTermination(rtcpEventNotifier, transportCcEngine)

    companion object {
        private val classLogger: Logger = Logger.getLogger(this::class.java)
        private const val PACKET_QUEUE_ENTRY_EVENT = "Entered RTP receiver incoming queue"
        private const val PACKET_QUEUE_EXIT_EVENT = "Exited RTP receiver incoming queue"
    }

    /**
     * [rtpPacketHandler] will be invoked with RTP packets that have made
     * it through the entire receive pipeline.  Some external entity should
     * assign it to a [PacketHandler] with appropriate logic.
     */
    override var rtpPacketHandler: PacketHandler? = null
    /**
     * [rtcpPacketHandler] will be invoked with RTCP packets that were not
     * terminated and should be further routed (e.g. RTCPFB packets).
     * Some external entity should assign it to a [PacketHandler] with appropriate logic.
     */
    override var rtcpPacketHandler: PacketHandler? = null

    /**
     * The [rtpPacketHandler] can be re-assigned at any time, but it should maintain
     * its place in the receive pipeline.  To support both keeping it in the same
     * place and allowing it to be re-assigned, we wrap it with this.
     */
    private val rtpPacketHandlerWrapper = object : ConsumerNode("RTP packet handler wrapper") {
        override fun consume(packetInfo: PacketInfo) {
            rtpPacketHandler?. let {
                it.processPacket(packetInfo)
            } ?: let {
                // While there's no handler set we're effectively dropping packets, so their buffers
                // should be returned.
                packetDiscarded(packetInfo)
            }
        }
    }

    /**
     * The [rtcpPacketHandler] can be re-assigned at any time, but it should maintain
     * its place in the receive pipeline.  To support both keeping it in the same
     * place and allowing it to be re-assigned, we wrap it with this.
     */
    private val rtcpPacketHandlerWrapper = object : ConsumerNode("RTCP packet handler wrapper") {
        override fun consume(packetInfo: PacketInfo) {
            rtcpPacketHandler?. let {
                it.processPacket(packetInfo)
            } ?: let {
                // While there's no handler set we're effectively dropping packets, so their buffers
                // should be returned.
                packetDiscarded(packetInfo)
            }
        }
    }

    // Stat tracking values
    var firstPacketWrittenTime: Long = 0
    var lastPacketWrittenTime: Long = 0
    var bytesReceived: Long = 0
    var packetsReceived: Long = 0

    var bytesProcessed: Long = 0
    var packetsProcessed: Long = 0
    var firstPacketProcessedTime: Long = 0
    var lastPacketProcessedTime: Long = 0

    private var firstQueueReadTime: Long = -1
    private var lastQueueReadTime: Long = -1
    private var numQueueReads: Long = 0
    private var numTimesQueueEmpty: Long = 0

    init {
        logger.cinfo { "Receiver ${this.hashCode()} using executor ${executor.hashCode()}" }
        rtcpEventNotifier.addRtcpEventListener(rtcpRrGenerator)

        inputTreeRoot = pipeline {
            demux("SRTP/SRTCP") {
                packetPath {
                    name = "SRTP path"
                    predicate = PacketPredicate { !RTCPUtils.isRtcp(it.buffer, it.offset, it.length) }
                    path = pipeline {
                        node(RtpParser())
                        node(tccGenerator)
                        node(audioLevelReader)
                        node(srtpDecryptWrapper)
                        node(statsTracker)
                        demux("Media type") {
                            packetPath {
                                name = "Audio path"
                                predicate = PacketPredicate { it is AudioRtpPacket }
                                path = pipeline {
                                    node(rtpPacketHandlerWrapper)
                                }
                            }
                            packetPath {
                                name = "Video path"
                                predicate = PacketPredicate { it is VideoRtpPacket }
                                path = pipeline {
                                    node(RtxHandler())
                                    node(PaddingTermination())
                                    node(VideoParser())
                                    node(Vp8Parser())
                                    node(VideoBitrateCalculator())
                                    node(RetransmissionRequesterNode(rtcpSender, backgroundExecutor))
                                    node(rtpPacketHandlerWrapper)
                                }
                            }
                        }
                    }
                }
                packetPath {
                    name = "SRTCP path"
                    predicate = PacketPredicate { RTCPUtils.isRtcp(it.buffer, it.offset, it.length) }
                    path = pipeline {
                        node(srtcpDecryptWrapper)
                        node(PacketParser("Compound RTCP parser") { CompoundRtcpPacket(it.buffer, it.offset, it.length) })
                        node(rtcpTermination)
                        node(rtcpPacketHandlerWrapper)
                    }
                }
            }
        }
    }

    private fun handleIncomingPacket(packet: PacketInfo): Boolean {
        if (running) {
            val now = System.currentTimeMillis()
            if (firstQueueReadTime == -1L) {
                firstQueueReadTime = now
            }
            numQueueReads++
            lastQueueReadTime = now
            packet.addEvent(PACKET_QUEUE_EXIT_EVENT)
            bytesProcessed += packet.packet.length
            packetsProcessed++
            if (firstPacketProcessedTime == 0L) {
                firstPacketProcessedTime = System.currentTimeMillis()
            }
            lastPacketProcessedTime = System.currentTimeMillis()
            processPacket(packet)
            return true
        }
        return false
    }

    override fun processPacket(packetInfo: PacketInfo) = inputTreeRoot.processPacket(packetInfo)

    override fun getNodeStats(): NodeStatsBlock {
        return NodeStatsBlock("RTP receiver $id").apply {
            addStat( "Received $packetsReceived packets ($bytesReceived bytes) in " + "${lastPacketWrittenTime - firstPacketWrittenTime}ms " + "(${getMbps(bytesReceived, Duration.ofMillis(lastPacketWrittenTime - firstPacketWrittenTime))} mbps)")
            addStat("Processed $packetsProcessed " + "(${(packetsProcessed / (packetsReceived.toDouble())) * 100}%) ($bytesProcessed bytes) in " + "${lastPacketProcessedTime - firstPacketProcessedTime}ms " + "(${getMbps(bytesProcessed, Duration.ofMillis(lastPacketProcessedTime - firstPacketProcessedTime))} mbps)")
            val queueReadTotal = lastQueueReadTime - firstQueueReadTime
            addStat("Read from queue at a rate of " + "${numQueueReads / (Duration.ofMillis(queueReadTotal).seconds.toDouble())} times per second")
            addStat("The queue was empty $numTimesQueueEmpty out of $numQueueReads times")
            NodeStatsVisitor(this).visit(inputTreeRoot)
        }
    }

    override fun enqueuePacket(p: PacketInfo) {
//        logger.cinfo { "Receiver $id enqueing data" }
        bytesReceived += p.packet.length
        p.addEvent(PACKET_QUEUE_ENTRY_EVENT)
        incomingPacketQueue.add(p)
        packetsReceived++
        if (firstPacketWrittenTime == 0L) {
            firstPacketWrittenTime = System.currentTimeMillis()
        }
        lastPacketWrittenTime = System.currentTimeMillis()
    }

    override fun setSrtpTransformer(srtpTransformer: SinglePacketTransformer) {
        srtpDecryptWrapper.setTransformer(srtpTransformer)
    }

    override fun setSrtcpTransformer(srtcpTransformer: SinglePacketTransformer) {
        srtcpDecryptWrapper.setTransformer(srtcpTransformer)
    }

    override fun handleEvent(event: Event) {
        NodeEventVisitor(event).visit(inputTreeRoot)
    }

    override fun setAudioLevelListener(audioLevelListener: AudioLevelListener) {
        audioLevelReader.audioLevelListener = audioLevelListener
    }

    override fun getStreamStats(): IncomingStatisticsSnapshot {
        return statsTracker.getSnapshot()
    }

    override fun stop() {
        running = false
        rtcpRrGenerator.running = false
        incomingPacketQueue.close()
    }

    override fun tearDown() {
        NodeTeardownVisitor().visit(inputTreeRoot)
    }
}
