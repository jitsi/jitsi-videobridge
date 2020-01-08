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

import ToggleablePcapWriter
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService
import org.jitsi.nlj.rtcp.KeyframeRequester
import org.jitsi.nlj.rtcp.NackHandler
import org.jitsi.nlj.rtcp.RtcpEventNotifier
import org.jitsi.nlj.rtcp.RtcpSrUpdater
import org.jitsi.nlj.rtp.TransportCcEngine
import org.jitsi.nlj.srtp.SrtpTransformers
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.NodeEventVisitor
import org.jitsi.nlj.transform.NodeStatsVisitor
import org.jitsi.nlj.transform.NodeTeardownVisitor
import org.jitsi.nlj.transform.node.ConsumerNode
import org.jitsi.nlj.transform.node.Node
import org.jitsi.nlj.transform.node.PacketCacher
import org.jitsi.nlj.transform.node.PacketStreamStatsNode
import org.jitsi.nlj.transform.node.SrtpTransformerNode
import org.jitsi.nlj.transform.node.outgoing.AbsSendTime
import org.jitsi.nlj.transform.node.outgoing.OutgoingStatisticsTracker
import org.jitsi.nlj.transform.node.outgoing.ProbingDataSender
import org.jitsi.nlj.transform.node.outgoing.RetransmissionSender
import org.jitsi.nlj.transform.node.outgoing.SentRtcpStats
import org.jitsi.nlj.transform.node.outgoing.TccSeqNumTagger
import org.jitsi.nlj.transform.pipeline
import org.jitsi.nlj.util.PacketInfoQueue
import org.jitsi.nlj.util.StreamInformationStore
import org.jitsi.nlj.util.cdebug
import org.jitsi.nlj.util.createChildLogger
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.utils.MediaType
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.queue.CountingErrorHandler

class RtpSenderImpl(
    val id: String,
    val transportCcEngine: TransportCcEngine? = null,
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
    protected val logger = parentLogger.createChildLogger(RtpSenderImpl::class)
    private val outgoingRtpRoot: Node
    private val outgoingRtxRoot: Node
    private val outgoingRtcpRoot: Node
    private val incomingPacketQueue = PacketInfoQueue("rtp-sender-incoming-packet-queue", executor, this::handlePacket)
    var running = true
    private var localVideoSsrc: Long? = null
    private var localAudioSsrc: Long? = null
    // TODO(brian): this is changed to a handler instead of a queue because we want to use
    // a PacketQueue, and the handler for a PacketQueue must be set at the time of creation.
    // since we want the handler to be another entity (something in jvb) we just use
    // a generic handler here and then the bridge can put it into its PacketQueue and have
    // its handler (likely in another thread) grab the packet and send it out
    private var outgoingPacketHandler: PacketHandler? = null

    private val srtpEncryptWrapper = SrtpTransformerNode("SRTP encrypt")
    private val srtcpEncryptWrapper = SrtpTransformerNode("SRTCP encrypt")
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
    }

    init {
        logger.cdebug { "Sender $id using executor ${executor.hashCode()}" }

        incomingPacketQueue.setErrorHandler(queueErrorCounter)

        outgoingRtpRoot = pipeline {
            node(outgoingPacketCache)
            node(absSendTime)
            node(statsTracker)
            node(TccSeqNumTagger(transportCcEngine, streamInformationStore))
            node(toggleablePcapWriter.newObserverNode())
            node(srtpEncryptWrapper)
            node(packetStreamStats.createNewNode())
            node(outputPipelineTerminationNode)
        }

        outgoingRtxRoot = pipeline {
            node(RetransmissionSender(streamInformationStore, logger))
            // We want RTX packets to hook into the main RTP pipeline starting at AbsSendTime
            node(absSendTime)
        }

        nackHandler = NackHandler(outgoingPacketCache.getPacketCache(), outgoingRtxRoot, logger)
        rtcpEventNotifier.addRtcpEventListener(nackHandler)

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
            node(outputPipelineTerminationNode)
        }

        probingDataSender = ProbingDataSender(
            outgoingPacketCache.getPacketCache(), outgoingRtxRoot, absSendTime, diagnosticContext,
            streamInformationStore,
            logger
        )
    }

    override fun onRttUpdate(newRtt: Double) {
        nackHandler.onRttUpdate(newRtt)
        keyframeRequester.onRttUpdate(newRtt)
        transportCcEngine?.onRttUpdate(Duration.ofNanos((newRtt * 1e6).toLong()))
    }

    /**
     * Insert packets into the incoming packet queue
     */
    override fun doProcessPacket(packetInfo: PacketInfo) {
        val packet = packetInfo.packet
        if (packet is RtcpPacket) {
            rtcpEventNotifier.notifyRtcpSent(packet)
        }
        packetInfo.addEvent(PACKET_QUEUE_ENTRY_EVENT)
        incomingPacketQueue.add(packetInfo)
    }

    override fun sendProbing(mediaSsrc: Long, numBytes: Int): Int = probingDataSender.sendProbing(mediaSsrc, numBytes)

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

    /**
     * Handles packets that have gone through the incoming queue and sends them
     * through the sender pipeline
     */
    private fun handlePacket(packetInfo: PacketInfo): Boolean {
        if (running) {
            packetInfo.addEvent(PACKET_QUEUE_EXIT_EVENT)

            val root = when (packetInfo.packet) {
                is RtcpPacket -> outgoingRtcpRoot
                else -> outgoingRtpRoot
            }
            root.processPacket(packetInfo)
            return true
        }
        return false
    }

    override fun getStreamStats() = statsTracker.getSnapshot()

    override fun getPacketStreamStats() = packetStreamStats.snapshot()

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
    }

    override fun stop() {
        running = false
        incomingPacketQueue.close()
    }

    override fun tearDown() {
        NodeTeardownVisitor().reverseVisit(outputPipelineTerminationNode)
        toggleablePcapWriter.disable()
    }

    companion object {
        val queueErrorCounter = CountingErrorHandler()

        private const val PACKET_QUEUE_ENTRY_EVENT = "Entered RTP sender incoming queue"
        private const val PACKET_QUEUE_EXIT_EVENT = "Exited RTP sender incoming queue"
    }
}
