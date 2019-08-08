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

import org.jitsi.nlj.rtcp.CompoundRtcpParser
import org.jitsi.nlj.rtcp.RtcpEventNotifier
import org.jitsi.nlj.rtcp.RtcpRrGenerator
import org.jitsi.nlj.rtp.AudioRtpPacket
import org.jitsi.nlj.rtp.VideoRtpPacket
import org.jitsi.nlj.srtp.SrtpTransformers
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.NodeEventVisitor
import org.jitsi.nlj.transform.NodeStatsVisitor
import org.jitsi.nlj.transform.NodeTeardownVisitor
import org.jitsi.nlj.transform.node.ConsumerNode
import org.jitsi.nlj.transform.node.Node
import org.jitsi.nlj.transform.node.PacketStreamStatsNode
import org.jitsi.nlj.transform.node.RtpParser
import org.jitsi.nlj.transform.node.SrtpTransformerNode
import org.jitsi.nlj.transform.node.incoming.AudioLevelReader
import org.jitsi.nlj.transform.node.incoming.IncomingStatisticsTracker
import org.jitsi.nlj.transform.node.incoming.PaddingTermination
import org.jitsi.nlj.transform.node.incoming.RetransmissionRequesterNode
import org.jitsi.nlj.transform.node.incoming.RtcpTermination
import org.jitsi.nlj.transform.node.incoming.RtxHandler
import org.jitsi.nlj.transform.node.incoming.SilenceDiscarder
import org.jitsi.nlj.transform.node.incoming.TccGeneratorNode
import org.jitsi.nlj.transform.node.incoming.VideoBitrateCalculator
import org.jitsi.nlj.transform.node.incoming.VideoParser
import org.jitsi.nlj.transform.node.incoming.Vp8Parser
import org.jitsi.nlj.transform.packetPath
import org.jitsi.nlj.transform.pipeline
import org.jitsi.nlj.util.PacketInfoQueue
import org.jitsi.nlj.util.PacketPredicate
import org.jitsi.nlj.util.ReadOnlyStreamInformationStore
import org.jitsi.nlj.util.cdebug
import org.jitsi.nlj.util.getLogger
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.util.RTCPUtils
import org.jitsi.utils.logging.Logger
import org.jitsi.utils.queue.CountingErrorHandler
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService

class RtpReceiverImpl @JvmOverloads constructor(
    val id: String,
    /**
     * A function to be used when these receiver wants to send RTCP packets to the
     * participant it's receiving data from (NACK packets, for example)
     */
    private val rtcpSender: (RtcpPacket) -> Unit = {},
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
    /**
     * Returns the current sending bitrate in bps.
     */
    getSendBitrate: () -> Long,
    streamInformationStore: ReadOnlyStreamInformationStore,
    logLevelDelegate: Logger? = null
) : RtpReceiver() {
    private val logger = getLogger(classLogger, logLevelDelegate)
    private var running: Boolean = true
    private val inputTreeRoot: Node
    private val incomingPacketQueue =
            PacketInfoQueue("rtp-receiver-incoming-packet-queue", executor, this::handleIncomingPacket)
    private val srtpDecryptWrapper = SrtpTransformerNode("SRTP Decrypt node")
    private val srtcpDecryptWrapper = SrtpTransformerNode("SRTCP Decrypt node")
    private val tccGenerator = TccGeneratorNode(rtcpSender, streamInformationStore)
    private val audioLevelReader = AudioLevelReader(streamInformationStore)
    private val silenceDiscarder = SilenceDiscarder(true)
    private val statsTracker = IncomingStatisticsTracker(streamInformationStore)
    private val packetStreamStats = PacketStreamStatsNode()
    private val rtcpRrGenerator = RtcpRrGenerator(backgroundExecutor, rtcpSender, statsTracker)
    private val rtcpTermination = RtcpTermination(rtcpEventNotifier)

    companion object {
        private val classLogger: Logger = Logger.getLogger(this::class.java)
        val queueErrorCounter = CountingErrorHandler()

        private const val PACKET_QUEUE_ENTRY_EVENT = "Entered RTP receiver incoming queue"
        private const val PACKET_QUEUE_EXIT_EVENT = "Exited RTP receiver incoming queue"
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
    private val packetHandlerWrapper = object : ConsumerNode("Packet handler wrapper") {
        override fun consume(packetInfo: PacketInfo) {
            // When there's no handler set we're effectively dropping packets, so their buffers
            // should be returned.
            packetHandler?.processPacket(packetInfo) ?: packetDiscarded(packetInfo)
        }
    }

    init {
        logger.cdebug { "Receiver ${this.hashCode()} using executor ${executor.hashCode()}" }
        rtcpEventNotifier.addRtcpEventListener(rtcpRrGenerator)

        incomingPacketQueue.setErrorHandler(queueErrorCounter)

        inputTreeRoot = pipeline {
            node(packetStreamStats)
            demux("SRTP/SRTCP") {
                packetPath {
                    name = "SRTP path"
                    predicate = PacketPredicate { !RTCPUtils.isRtcp(it.buffer, it.offset, it.length) }
                    path = pipeline {
                        node(RtpParser(streamInformationStore))
                        node(tccGenerator)
                        // TODO: temporarily putting the audioLevelReader node here such that we can determine whether
                        // or not a packet should be discarded before doing SRTP. audioLevelReader has been moved here
                        // (instead of introducing a different class to read audio levels) to avoid parsing the RTP
                        // header extensions twice (which is expensive). In the future we will parse and cache the
                        // header extensions to make this lookup more efficient, at which time we could move
                        // audioLevelReader back to where it was (in the audio path) and add a new node here which would
                        // check for different discard conditions (i.e. checking the audio level for silence)
                        node(audioLevelReader)
                        node(srtpDecryptWrapper)
                        node(statsTracker)
                        demux("Media type") {
                            packetPath {
                                name = "Audio path"
                                predicate = PacketPredicate { it is AudioRtpPacket }
                                path = pipeline {
                                    node(silenceDiscarder.rtpNode)
                                    node(packetHandlerWrapper)
                                }
                            }
                            packetPath {
                                name = "Video path"
                                predicate = PacketPredicate { it is VideoRtpPacket }
                                path = pipeline {
                                    node(RtxHandler(streamInformationStore))
                                    node(PaddingTermination())
                                    node(VideoParser(streamInformationStore))
                                    node(Vp8Parser())
                                    node(VideoBitrateCalculator())
                                    node(RetransmissionRequesterNode(rtcpSender, backgroundExecutor))
                                    node(packetHandlerWrapper)
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
                        node(CompoundRtcpParser())
                        node(silenceDiscarder.rtcpNode)
                        node(rtcpTermination)
                        node(packetHandlerWrapper)
                    }
                }
            }
        }
    }

    private fun handleIncomingPacket(packet: PacketInfo): Boolean {
        if (running) {
            packet.addEvent(PACKET_QUEUE_EXIT_EVENT)
            processPacket(packet)
            return true
        }
        return false
    }

    override fun doProcessPacket(packetInfo: PacketInfo) = inputTreeRoot.processPacket(packetInfo)

    override fun getNodeStats(): NodeStatsBlock = NodeStatsBlock("RTP receiver $id").apply {
        addBlock(super.getNodeStats())
        addString("running", running.toString())
        NodeStatsVisitor(this).visit(inputTreeRoot)
    }

    override fun enqueuePacket(p: PacketInfo) {
//        logger.cinfo { "Receiver $id enqueing data" }
        p.addEvent(PACKET_QUEUE_ENTRY_EVENT)
        incomingPacketQueue.add(p)
    }

    override fun setSrtpTransformers(srtpTransformers: SrtpTransformers) {
        srtpDecryptWrapper.transformer = srtpTransformers.srtpDecryptTransformer
        srtcpDecryptWrapper.transformer = srtpTransformers.srtcpDecryptTransformer
    }

    override fun handleEvent(event: Event) {
        NodeEventVisitor(event).visit(inputTreeRoot)
    }

    override fun setAudioLevelListener(audioLevelListener: AudioLevelListener) {
        audioLevelReader.audioLevelListener = audioLevelListener
    }

    override fun getStreamStats() = statsTracker.getSnapshot()

    override fun getPacketStreamStats() = packetStreamStats.snapshot()

    override fun stop() {
        running = false
        rtcpRrGenerator.running = false
        incomingPacketQueue.close()
    }

    override fun tearDown() {
        NodeTeardownVisitor().visit(inputTreeRoot)
    }
}
