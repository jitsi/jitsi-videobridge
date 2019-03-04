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

import org.jitsi.nlj.rtcp.KeyframeRequester
import org.jitsi.nlj.rtcp.NackHandler
import org.jitsi.nlj.rtcp.RtcpEventNotifier
import org.jitsi.nlj.rtcp.RtcpSrGenerator
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.NodeEventVisitor
import org.jitsi.nlj.transform.NodeStatsVisitor
import org.jitsi.nlj.transform.NodeTeardownVisitor
import org.jitsi.nlj.transform.node.ConsumerNode
import org.jitsi.nlj.transform.node.Node
import org.jitsi.nlj.transform.node.PacketCacher
import org.jitsi.nlj.transform.node.outgoing.AbsSendTime
import org.jitsi.nlj.transform.node.outgoing.OutgoingStatisticsTracker
import org.jitsi.nlj.transform.node.outgoing.OutgoingStreamStatistics
import org.jitsi.nlj.transform.node.outgoing.ProbingDataSender
import org.jitsi.nlj.transform.node.outgoing.RetransmissionSender
import org.jitsi.nlj.transform.node.outgoing.SentRtcpStats
import org.jitsi.nlj.transform.node.outgoing.SrtcpTransformerEncryptNode
import org.jitsi.nlj.transform.node.outgoing.SrtpTransformerEncryptNode
import org.jitsi.nlj.transform.node.outgoing.TccSeqNumTagger
import org.jitsi.nlj.transform.pipeline
import org.jitsi.nlj.util.PacketInfoQueue
import org.jitsi.nlj.util.Util.Companion.getMbps
import org.jitsi.nlj.util.cerror
import org.jitsi.nlj.util.cinfo
import org.jitsi.nlj.util.getLogger
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.service.neomedia.MediaType
import org.jitsi.util.Logger
import org.jitsi_modified.impl.neomedia.rtp.TransportCCEngine
import org.jitsi_modified.impl.neomedia.transform.SinglePacketTransformer
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService

class RtpSenderImpl(
        val id: String,
        transportCcEngine: TransportCCEngine? = null,
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
        logLevelDelegate: Logger? = null
) : RtpSender() {
    protected val logger = getLogger(classLogger, logLevelDelegate)
    private val outgoingRtpRoot: Node
    private val outgoingRtxRoot: Node
    private val outgoingRtcpRoot: Node
    private val incomingPacketQueue = PacketInfoQueue(id, executor, this::processPacket)
    var numIncomingBytes: Long = 0
    var firstPacketWrittenTime = -1L
    var lastPacketWrittenTime = -1L
    var running = true
    private var localVideoSsrc: Long? = null
    private var localAudioSsrc: Long? = null
    //TODO(brian): this is changed to a handler instead of a queue because we want to use
    // a PacketQueue, and the handler for a PacketQueue must be set at the time of creation.
    // since we want the handler to be another entity (something in jvb) we just use
    // a generic handler here and then the bridge can put it into its PacketQueue and have
    // its handler (likely in another thread) grab the packet and send it out
    private var outgoingPacketHandler: PacketHandler? = null

    private var firstQueueReadTime: Long = -1
    private var lastQueueReadTime: Long = -1
    private var numQueueReads: Long = 0
    private var numTimesQueueEmpty: Long = 0

    private val srtpEncryptWrapper = SrtpTransformerEncryptNode()
    private val srtcpEncryptWrapper = SrtcpTransformerEncryptNode()
    private val outgoingPacketCache = PacketCacher()
    private val absSendTime = AbsSendTime()
    private val statTracker = OutgoingStatisticsTracker()
    private val keyframeRequester = KeyframeRequester()
    private val probingDataSender: ProbingDataSender
    /**
     * The SR generator runs on its own in the background.  It will access the outgoing stats via the given
     * [OutgoingStatisticsTracker] to grab a snapshot of the current state for filling out an SR and sending it
     * via the given RTCP sender.
     */
    private val rtcpSrGenerator = RtcpSrGenerator(
            backgroundExecutor,
            { rtcpPacket -> sendRtcp(rtcpPacket) },
            statTracker
    )
    private val nackHandler: NackHandler

    private val outputPipelineTerminationNode = object : ConsumerNode("Output pipeline termination node") {
        override fun consume(packetInfo: PacketInfo) {
            if (packetInfo.timeline.totalDelay() > Duration.ofMillis(100)) {
                logger.cerror { "Packet took >100ms to get through bridge:\n${packetInfo.timeline}"}
            }
            outgoingPacketHandler?.processPacket(packetInfo)
        }
    }

    companion object {
        private val classLogger: Logger = Logger.getLogger(this::class.java)
        private const val PACKET_QUEUE_ENTRY_EVENT = "Entered RTP sender incoming queue"
        private const val PACKET_QUEUE_EXIT_EVENT = "Exited RTP sender incoming queue"
    }

    init {
        logger.cinfo { "Sender $id using executor ${executor.hashCode()}" }

        outgoingRtpRoot = pipeline {
            node(outgoingPacketCache)
            node(absSendTime)
            node(statTracker)
            node(TccSeqNumTagger(transportCcEngine))
            node(srtpEncryptWrapper)
            node(outputPipelineTerminationNode)
        }

        outgoingRtxRoot = pipeline {
            node(RetransmissionSender())
            // We want RTX packets to hook into the main RTP pipeline starting at AbsSendTime
            node(absSendTime)
        }

        nackHandler = NackHandler(outgoingPacketCache.getPacketCache(), outgoingRtxRoot)
        rtcpEventNotifier.addRtcpEventListener(nackHandler)

        //TODO: aggregate/translate PLI/FIR/etc in the egress RTCP pipeline
        //TODO: are we setting outgoing rtcp sequence numbers correctly? just add a simple node here to rewrite them
        outgoingRtcpRoot = pipeline {
            node(keyframeRequester)
            node(SentRtcpStats())
            //TODO(brian): not sure this is a great idea.  it works as a catch-call but can also be error-prone
            // (i found i was accidentally clobbering the sender ssrc for SRs which caused issues).  I think
            // it'd be better to notify everything creating RTCP the bridge SSRCs and then everything should be
            // responsible for setting it themselves
            simpleNode("RTCP sender ssrc setter") { packetInfo ->
                val senderSsrc = localVideoSsrc ?: return@simpleNode null
                val rtcpPacket = packetInfo.packetAs<RtcpPacket>()
                if (rtcpPacket.header.senderSsrc == 0L) {
                    rtcpPacket.header.senderSsrc = senderSsrc
                }
                packetInfo
            }
            node(srtcpEncryptWrapper)
            node(outputPipelineTerminationNode)
        }

        probingDataSender = ProbingDataSender(outgoingPacketCache.getPacketCache(), outgoingRtxRoot, absSendTime)
    }

    override fun sendPacket(packetInfo: PacketInfo) {
        numIncomingBytes += packetInfo.packet.sizeBytes
        packetInfo.addEvent(PACKET_QUEUE_ENTRY_EVENT)
        incomingPacketQueue.add(packetInfo)
        if (firstPacketWrittenTime == -1L) {
            firstPacketWrittenTime = System.currentTimeMillis()
        }
        lastPacketWrittenTime = System.currentTimeMillis()
    }

    override fun sendRtcp(rtcpPacket: RtcpPacket) {
        rtcpEventNotifier.notifyRtcpSent(rtcpPacket)
        //TODO: do we want to allow for PacketInfo to be passed in to sendRtcp?
        outgoingRtcpRoot.processPacket(PacketInfo(rtcpPacket))
    }

    override fun sendProbing(mediaSsrc: Long, numBytes: Int): Int = probingDataSender.sendProbing(mediaSsrc, numBytes)

    override fun onOutgoingPacket(handler: PacketHandler) {
        outgoingPacketHandler = handler
    }

    override fun setSrtpTransformer(srtpTransformer: SinglePacketTransformer) {
        srtpEncryptWrapper.setTransformer(srtpTransformer)
    }

    override fun setSrtcpTransformer(srtcpTransformer: SinglePacketTransformer) {
        srtcpEncryptWrapper.setTransformer(srtcpTransformer)
    }

    override fun requestKeyframe(mediaSsrc: Long) {
        keyframeRequester.requestKeyframe(mediaSsrc)
    }

    private fun processPacket(packet: PacketInfo): Boolean {
        if (running) {
            val now = System.currentTimeMillis()
            if (firstQueueReadTime == -1L) {
                firstQueueReadTime = now
            }
            numQueueReads++
            lastQueueReadTime = now
            packet.addEvent(PACKET_QUEUE_EXIT_EVENT)
            outgoingRtpRoot.processPacket(packet)
            return true
        }
        return false
    }

    override fun getStreamStats(): Map<Long, OutgoingStreamStatistics.Snapshot> {
        return statTracker.getCurrentStats().map { (ssrc, stats) ->
            Pair(ssrc, stats.getSnapshot())
        }.toMap()
    }

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

    override fun getNodeStats(): NodeStatsBlock {
        val bitRateMbps = getMbps(numBytesSent, Duration.ofMillis(lastPacketSentTime - firstPacketSentTime))
        return NodeStatsBlock("RTP sender $id").apply {
            addStat("$numIncomingBytes incoming bytes in ${lastPacketWrittenTime - firstPacketWrittenTime} (${getMbps(numIncomingBytes, Duration.ofMillis(lastPacketWrittenTime - firstPacketWrittenTime))} mbps)")
            addStat("Sent $numPacketsSent packets in ${lastPacketSentTime - firstPacketSentTime} ms")
            addStat("Sent $numBytesSent bytes in ${lastPacketSentTime - firstPacketSentTime} ms ($bitRateMbps mbps)")
            val queueReadTotal = lastQueueReadTime - firstQueueReadTime
            addStat("Read from queue at a rate of " +
                    "${numQueueReads / (Duration.ofMillis(queueReadTotal).seconds.toDouble())} times per second")
            addStat("The queue was empty $numTimesQueueEmpty out of $numQueueReads times")
            addStat("Nack handler", nackHandler.getNodeStats())
            addStat("Probing data sender", probingDataSender.getNodeStats())
            NodeStatsVisitor(this).reverseVisit(outputPipelineTerminationNode)
        }
    }

    override fun stop() {
        running = false
        rtcpSrGenerator.running = false
        incomingPacketQueue.close()
    }

    override fun tearDown() {
        NodeTeardownVisitor().reverseVisit(outputPipelineTerminationNode)
    }
}
