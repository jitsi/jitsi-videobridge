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

import org.jitsi.nlj.transform.node.Node
import org.jitsi.nlj.transform.node.NodeStatsVisitor
import org.jitsi.nlj.transform.node.outgoing.SrtcpTransformerEncryptNode
import org.jitsi.nlj.transform.node.outgoing.SrtpTransformerEncryptNode
import org.jitsi.nlj.transform.pipeline
import org.jitsi.nlj.transform_og.SinglePacketTransformer
import org.jitsi.nlj.util.Util.Companion.getMbps
import org.jitsi.nlj.util.forEachAs
import org.jitsi.rtp.Packet
import org.jitsi.rtp.RtpPacket
import org.jitsi.rtp.RtpProtocolPacket
import org.jitsi.rtp.rtcp.RtcpPacket
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue

class RtpSenderImpl(
    val id: Long,
    val executor: ExecutorService /*= Executors.newSingleThreadExecutor()*/
) : RtpSender() {
    private val outgoingRtpRoot: Node
    private val outgoingRtcpRoot: Node
    val incomingPacketQueue = LinkedBlockingQueue<Packet>()
    var numIncomingBytes: Long = 0
    var firstPacketWrittenTime = -1L
    var lastPacketWrittenTime = -1L
    var running = true

    private val srtpEncryptWrapper = SrtpTransformerEncryptNode()
    private val srtcpEncryptWrapper = SrtcpTransformerEncryptNode()

    private var tempSenderSsrc: Long? = null

    init {
        println("Sender ${this.hashCode()} using executor ${executor.hashCode()}")
        outgoingRtpRoot = pipeline {
            node(srtpEncryptWrapper)
            simpleNode("TEMP sender ssrc setter") { pkts ->
                if (tempSenderSsrc == null && pkts.isNotEmpty()) {
                    val pkt = pkts.get(0)
                    if (pkt is RtpPacket) {
                        tempSenderSsrc = pkt.header.ssrc
                    }
                }
                pkts
            }
            simpleNode("RTP sender") { pkts ->
                packetSender.processPackets(pkts)
                emptyList()
            }
        }
        outgoingRtcpRoot = pipeline {
            simpleNode("RTCP sender ssrc setter") { pkts ->
                tempSenderSsrc?.let { senderSsrc ->
                    pkts.forEachAs<RtcpPacket> {
                        it.header.senderSsrc = senderSsrc
                    }
                }
                pkts
            }
            node(srtcpEncryptWrapper)
            simpleNode("RTCP sender") { pkts ->
                packetSender.processPackets(pkts)
                emptyList()
            }
        }
        scheduleWork()
    }

    override fun sendPackets(pkts: List<Packet>) {
//        println("BRIAN: sender got ${pkts.size} packets to send")
//        pkts.forEachAs<RtpPacket> {
//            println("BRIAN: sender sendign packet ${it.header} with buf ${it.getBuffer().toHex()}")
//        }
        incomingPacketQueue.addAll(pkts)
        pkts.forEach { numIncomingBytes += it.size }
        if (firstPacketWrittenTime == -1L) {
            firstPacketWrittenTime = System.currentTimeMillis()
        }
        lastPacketWrittenTime = System.currentTimeMillis()
    }

    override fun sendRtcp(pkts: List<RtcpPacket>) {
//        println("RtpSenderImpl#sendRtcp sending ${pkts.size} rtcp packets")
        outgoingRtcpRoot.processPackets(pkts)
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
//                println("BRIAN: rtp sender job running")
                val packetsToProcess = mutableListOf<Packet>()
                incomingPacketQueue.drainTo(packetsToProcess, 20)
//                println("BRIAN: rtp sender job got ${packetsToProcess.size} packets to give to chain")
                if (packetsToProcess.isNotEmpty()) outgoingRtpRoot.processPackets(packetsToProcess)

                scheduleWork()
            }
        }
    }

    override fun getStats(): String {
        val bitRateMbps = getMbps(numBytesSent, Duration.ofMillis(lastPacketSentTime - firstPacketSentTime))
        return with (StringBuffer()) {
            appendln("RTP Sender $id")
            appendln("queue size: ${incomingPacketQueue.size}")
            appendln("$numIncomingBytes incoming bytes in ${lastPacketWrittenTime - firstPacketWrittenTime} (${getMbps(numIncomingBytes, Duration.ofMillis(lastPacketWrittenTime - firstPacketWrittenTime))} mbps)")
            appendln("Sent $numPacketsSent packets in ${lastPacketSentTime - firstPacketSentTime} ms")
            appendln("Sent $numBytesSent bytes in ${lastPacketSentTime - firstPacketSentTime} ms ($bitRateMbps mbps)")
            val statsVisitor = NodeStatsVisitor(this)
            outgoingRtpRoot.visit(statsVisitor)
            outgoingRtcpRoot.visit(statsVisitor)
            toString()
        }
    }
}
