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

import org.jitsi.impl.neomedia.transform.SinglePacketTransformer
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.node.*
import org.jitsi.nlj.transform.node.outgoing.AbsSendTime
import org.jitsi.nlj.transform.node.outgoing.OutgoingStatisticsTracker
import org.jitsi.nlj.transform.node.outgoing.RetransmissionSender
import org.jitsi.nlj.transform.node.outgoing.SrtcpTransformerEncryptNode
import org.jitsi.nlj.transform.node.outgoing.SrtpTransformerEncryptNode
import org.jitsi.nlj.transform.node.outgoing.TccSeqNumTagger
import org.jitsi.nlj.transform.pipeline
import org.jitsi.nlj.util.Util.Companion.getMbps
import org.jitsi.nlj.util.cerror
import org.jitsi.nlj.util.cinfo
import org.jitsi.nlj.util.getLogger
import org.jitsi.rtp.RtpPacket
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi_modified.impl.neomedia.rtp.TransportCCEngine
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class RtpSenderImpl(
        val id: String,
        transportCcEngine: TransportCCEngine? = null,
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
        val backgroundExecutor: ScheduledExecutorService
) : RtpSender() {
    protected val logger = getLogger(this.javaClass)
    private val outgoingRtpRoot: Node
    private val outgoingRtxRoot: Node
    private val outgoingRtcpRoot: Node
    val incomingPacketQueue = LinkedBlockingQueue<PacketInfo>()
    var numIncomingBytes: Long = 0
    var firstPacketWrittenTime = -1L
    var lastPacketWrittenTime = -1L
    var running = true

    private var firstQueueReadTime: Long = -1
    private var lastQueueReadTime: Long = -1
    private var numQueueReads: Long = 0
    private var numTimesQueueEmpty: Long = 0

    private val srtpEncryptWrapper = SrtpTransformerEncryptNode()
    private val srtcpEncryptWrapper = SrtcpTransformerEncryptNode()
    private val outgoingPacketCache = PacketCache()
    private val absSendTime = AbsSendTime()
    private val statTracker = OutgoingStatisticsTracker()
    private val rtcpSrGenerator = RtcpSrGenerator(backgroundExecutor, { rtcpPacket -> sendRtcp(listOf(rtcpPacket)) } , statTracker)
    private val nackHandler: NackHandler

    private val outputPipelineTerminationNode = object : Node("Output pipeline termination node") {
        override fun doProcessPackets(p: List<PacketInfo>) {
            p.forEach {
                if (it.timeline.totalDelay() > Duration.ofMillis(100)) {
                    logger.cerror { "Packet took >100ms to get through bridge:\n${it.timeline}"}
                }
            }
            this@RtpSenderImpl.packetSender.processPackets(p)
        }
    }

    private var tempSenderSsrc: Long? = null

    companion object {
        private const val PACKET_QUEUE_ENTRY_EVENT = "Entered RTP sender incoming queue"
        private const val PACKET_QUEUE_EXIT_EVENT = "Exited RTP sender incoming queue"
    }

    init {
        logger.cinfo { "Sender ${this.hashCode()} using executor ${executor.hashCode()}" }
        outgoingRtpRoot = pipeline {
            simpleNode("TEMP sender ssrc setter") { pktInfos ->
                if (tempSenderSsrc == null && pktInfos.isNotEmpty()) {
                    val pktInfo = pktInfos[0]
                    if (pktInfo.packet is RtpPacket) {
                        tempSenderSsrc = (pktInfo.packet as? RtpPacket)?.header?.ssrc
                        logger.cinfo { "RtpSenderImpl ${hashCode()} setting sender ssrc to $tempSenderSsrc" }
                    }
                }
                pktInfos
            }
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

        //TODO: aggregate/translate PLI/FIR/etc in the egress RTCP pipeline
        outgoingRtcpRoot = pipeline {
            simpleNode("RTCP sender ssrc setter") { pktInfos ->
                tempSenderSsrc?.let { senderSsrc ->
                    pktInfos.forEachAs<RtcpPacket> { _, pkt ->
                        //TODO: get the sender ssrc working right, i think we may be getting the wrong
                        // one somehow
                        if (pkt.header.senderSsrc == 0L) {
                            pkt.header.senderSsrc = senderSsrc
                        }
                        pkt.getBuffer()
//                        logger.cinfo { "Sending RTCP\n$pkt\n\n${pkt.getBuffer().toHex()}" }
                    }
                    return@simpleNode pktInfos
                }
                emptyList()
            }
            node(srtcpEncryptWrapper)
            node(outputPipelineTerminationNode)
        }
        executor.execute(this::doWork)
    }

    override fun getNackHandler(): NackHandler = nackHandler

    override fun sendPackets(pkts: List<PacketInfo>) {
        pkts.forEach {
            numIncomingBytes += it.packet.size
            it.addEvent(PACKET_QUEUE_ENTRY_EVENT)
        }
        incomingPacketQueue.addAll(pkts)
        if (firstPacketWrittenTime == -1L) {
            firstPacketWrittenTime = System.currentTimeMillis()
        }
        lastPacketWrittenTime = System.currentTimeMillis()
    }

    override fun sendRtcp(pkts: List<RtcpPacket>) {
        //TODO: do we want to allow for PacketInfo to be passed in to sendRtcp?
        outgoingRtcpRoot.processPackets(pkts.map { PacketInfo(it) })
    }

    override fun setSrtpTransformer(srtpTransformer: SinglePacketTransformer) {
        srtpEncryptWrapper.setTransformer(srtpTransformer)
    }

    override fun setSrtcpTransformer(srtcpTransformer: SinglePacketTransformer) {
        srtcpEncryptWrapper.setTransformer(srtcpTransformer)
    }

    private fun doWork() {
        while (running) {
            val now = System.currentTimeMillis()
            if (firstQueueReadTime == -1L) {
                firstQueueReadTime = now
            }
            numQueueReads++
            lastQueueReadTime = now
            incomingPacketQueue.poll(100, TimeUnit.MILLISECONDS)?.let {
                it.addEvent(PACKET_QUEUE_EXIT_EVENT)
                outgoingRtpRoot.processPackets(listOf(it))
            }
        }
    }


    override fun handleEvent(event: Event) {
        outputPipelineTerminationNode.reverseVisit(NodeEventVisitor(event))
    }

    override fun getStats(): NodeStatsBlock {
        val bitRateMbps = getMbps(numBytesSent, Duration.ofMillis(lastPacketSentTime - firstPacketSentTime))
        return NodeStatsBlock("RTP sender $id").apply {
            addStat("queue size: ${incomingPacketQueue.size}")
            addStat("$numIncomingBytes incoming bytes in ${lastPacketWrittenTime - firstPacketWrittenTime} (${getMbps(numIncomingBytes, Duration.ofMillis(lastPacketWrittenTime - firstPacketWrittenTime))} mbps)")
            addStat("Sent $numPacketsSent packets in ${lastPacketSentTime - firstPacketSentTime} ms")
            addStat("Sent $numBytesSent bytes in ${lastPacketSentTime - firstPacketSentTime} ms ($bitRateMbps mbps)")
            val queueReadTotal = lastQueueReadTime - firstQueueReadTime
            addStat("Read from queue at a rate of " +
                    "${numQueueReads / (Duration.ofMillis(queueReadTotal).seconds.toDouble())} times per second")
            addStat("The queue was empty $numTimesQueueEmpty out of $numQueueReads times")
            addStat("nack handler stats: ${nackHandler.getStats()}")
            val statsVisitor = NodeStatsVisitor(this)
            outputPipelineTerminationNode.reverseVisit(statsVisitor)
        }
    }

    override fun stop() {
        running = false
        rtcpSrGenerator.running = false
    }
}
