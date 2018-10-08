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
import org.jitsi.nlj.transform.node.Node
import org.jitsi.nlj.transform.node.NodeEventVisitor
import org.jitsi.nlj.transform.node.NodeStatsVisitor
import org.jitsi.nlj.transform.node.PacketCache
import org.jitsi.nlj.transform.node.outgoing.AbsSendTime
import org.jitsi.nlj.transform.node.outgoing.OutgoingStatisticsTracker
import org.jitsi.nlj.transform.node.outgoing.RetransmissionSender
import org.jitsi.nlj.transform.node.outgoing.SrtcpTransformerEncryptNode
import org.jitsi.nlj.transform.node.outgoing.SrtpTransformerEncryptNode
import org.jitsi.nlj.transform.node.outgoing.TccSeqNumTagger
import org.jitsi.nlj.transform.pipeline
import org.jitsi.nlj.util.Util.Companion.getMbps
import org.jitsi.nlj.util.appendLnIndent
import org.jitsi.nlj.util.cinfo
import org.jitsi.nlj.util.getLogger
import org.jitsi.rtp.RtpPacket
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi_modified.impl.neomedia.rtp.TransportCCEngine
import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService

class RtpSenderImpl(
    val id: Long,
    transportCcEngine: TransportCCEngine? = null,
    val executor: ScheduledExecutorService
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

    private val srtpEncryptWrapper = SrtpTransformerEncryptNode()
    private val srtcpEncryptWrapper = SrtcpTransformerEncryptNode()
    private val outgoingPacketCache = PacketCache()
    private val absSendTime = AbsSendTime()
    private val statTracker = OutgoingStatisticsTracker()
    private val rtcpSrGenerator = RtcpSrGenerator(executor, { rtcpPacket -> sendRtcp(listOf(rtcpPacket)) } , statTracker)

    private val outputPipelineTerminationNode = object : Node("Output pipeline termination node") {
        override fun doProcessPackets(p: List<PacketInfo>) {
            this@RtpSenderImpl.packetSender.processPackets(p)
        }
    }

    private var tempSenderSsrc: Long? = null

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
        scheduleWork()
    }

    override fun getNackHandler(): NackHandler {
        //TODO: don't return a new one every time
        return NackHandler(outgoingPacketCache.getPacketCache(), outgoingRtxRoot)
    }

    override fun sendPackets(pkts: List<PacketInfo>) {
        incomingPacketQueue.addAll(pkts)
        pkts.forEach { numIncomingBytes += it.packet.size }
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

    private fun scheduleWork() {
        executor.execute {
            if (running) {
                val packetsToProcess = mutableListOf<PacketInfo>()
                incomingPacketQueue.drainTo(packetsToProcess, 20)
                if (packetsToProcess.isNotEmpty()) {
                    outgoingRtpRoot.processPackets(packetsToProcess)
                }

                scheduleWork()
            }
        }
    }

    override fun handleEvent(event: Event) {
        outputPipelineTerminationNode.reverseVisit(NodeEventVisitor(event))
    }

    override fun getStats(indent: Int): String {
        val bitRateMbps = getMbps(numBytesSent, Duration.ofMillis(lastPacketSentTime - firstPacketSentTime))
        return with (StringBuffer()) {
            appendLnIndent(indent, "RTP Sender $id")
            appendLnIndent(indent + 2, "queue size: ${incomingPacketQueue.size}")
            appendLnIndent(indent + 2, "$numIncomingBytes incoming bytes in ${lastPacketWrittenTime - firstPacketWrittenTime} (${getMbps(numIncomingBytes, Duration.ofMillis(lastPacketWrittenTime - firstPacketWrittenTime))} mbps)")
            appendLnIndent(indent + 2, "Sent $numPacketsSent packets in ${lastPacketSentTime - firstPacketSentTime} ms")
            appendLnIndent(indent + 2, "Sent $numBytesSent bytes in ${lastPacketSentTime - firstPacketSentTime} ms ($bitRateMbps mbps)")
            val statsVisitor = NodeStatsVisitor(this)
            outputPipelineTerminationNode.reverseVisit(statsVisitor)

            toString()
        }
    }

    override fun stop() {
        running = false
        rtcpSrGenerator.running = false
    }
}
