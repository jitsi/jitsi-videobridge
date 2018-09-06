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
import org.jitsi.nlj.transform.node.PayloadTypeFilterNode
import org.jitsi.nlj.transform.node.incoming.AudioLevelReader
import org.jitsi.nlj.transform.node.incoming.FirRequester
import org.jitsi.nlj.transform.node.incoming.PaddingTermination
import org.jitsi.nlj.transform.node.incoming.RetransmissionRequester
import org.jitsi.nlj.transform.node.incoming.RtcpTermination
import org.jitsi.nlj.transform.node.incoming.RtxHandler
import org.jitsi.nlj.transform.node.incoming.SrtcpTransformerDecryptNode
import org.jitsi.nlj.transform.node.incoming.SrtpTransformerDecryptNode
import org.jitsi.nlj.transform.node.incoming.TccGeneratorNode
import org.jitsi.nlj.transform.node.incoming.VideoParser
import org.jitsi.nlj.transform.packetPath
import org.jitsi.nlj.transform.pipeline
import org.jitsi.nlj.util.Util.Companion.getMbps
import org.jitsi.nlj.util.appendLnIndent
import org.jitsi.rtp.Packet
import org.jitsi.rtp.SrtcpPacket
import org.jitsi.rtp.SrtpPacket
import org.jitsi.rtp.SrtpProtocolPacket
import org.jitsi.rtp.extensions.toHex
import org.jitsi.rtp.rtcp.RtcpIterator
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.rtp.util.RtpProtocol
import org.jitsi.service.neomedia.event.CsrcAudioLevelListener
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue

class RtpReceiverImpl @JvmOverloads constructor(
    val id: Long,
    /**
     * A function to be used when these receiver wants to send RTCP packets to the
     * participant it's receiving data from (NACK packets, for example)
     */
    private val rtcpSender: (RtcpPacket) -> Unit = {},
    /**
     * The executor this class will use for its work
     */
    private val executor: ExecutorService /*= Executors.newSingleThreadExecutor()*/
) : RtpReceiver() {
    override var name: String = "RtpReceiverImpl"
    private val inputTreeRoot: Node
    private val incomingPacketQueue = LinkedBlockingQueue<Packet>()
    private val srtpDecryptWrapper = SrtpTransformerDecryptNode()
    private val srtcpDecryptWrapper = SrtcpTransformerDecryptNode()
    private val tccGenerator = TccGeneratorNode(rtcpSender)
    private val payloadTypeFilter = PayloadTypeFilterNode()
    private val audioLevelListener = AudioLevelReader()

    override var rtpPacketHandler: PacketHandler? = null
    override var rtcpPacketHandler: PacketHandler? = null

    // Stat tracking values
    var firstPacketWrittenTime: Long = 0
    var lastPacketWrittenTime: Long = 0
    var bytesReceived: Long = 0
    var packetsReceived: Long = 0

    init {
        println("Receiver ${this.hashCode()} using executor ${executor.hashCode()}")
        inputTreeRoot = pipeline {
            simpleNode("SRTP protocol parser") { pkts ->
                pkts.map(Packet::getBuffer).map(::SrtpProtocolPacket)
            }
            demux {
                name = "SRTP/SRTCP demuxer"
                packetPath {
                    predicate = { pkt -> RtpProtocol.isRtp(pkt.getBuffer()) }
                    path = pipeline {
                        simpleNode("SRTP parser") { pkts ->
                            pkts.map(Packet::getBuffer).map(::SrtpPacket)
                        }
                        node(payloadTypeFilter)
                        node(tccGenerator)
                        node(srtpDecryptWrapper)
//                        node(PcapWriter())
                        node(RtxHandler())
                        node(PaddingTermination())
                        node(VideoParser())
                        node(FirRequester(rtcpSender))
                        //TODO: how should retransmissions without rtx be handled with srtp?
//                        node(NackGeneratorNode(rtcpSender))
                        node(RetransmissionRequester(rtcpSender))
                        node(audioLevelListener)
                        simpleNode("RTP packet handler") {
                            rtpPacketHandler?.processPackets(it)
                            emptyList()
                        }
                    }
                }
                packetPath {
                    predicate = { pkt -> RtpProtocol.isRtcp(pkt.getBuffer()) }
                    path = pipeline {
                        var prevRtcpPackets = mutableListOf<Packet>()
                        simpleNode("SRTCP parser ${hashCode()}") { pkts ->
                            pkts.map(Packet::getBuffer).map(::SrtcpPacket)
                        }
                        node(srtcpDecryptWrapper)
                        simpleNode("RTCP pre-parse cache ${hashCode()}") { pkts ->
                            prevRtcpPackets.clear()
                            pkts.forEach {
                                prevRtcpPackets.add(it.clone())
                            }
                            pkts
                        }

                        simpleNode("RTCP parser ${hashCode()}") { pkts ->
                            pkts.map(Packet::getBuffer).map { RtcpPacket.fromBuffer(it) }
                        }
                        simpleNode("Compound RTCP splitter") { pkts ->
                            try {
                                pkts
                                    .map(Packet::getBuffer)
                                    .map(::RtcpIterator)
                                    .map(RtcpIterator::getAll)
                                    .flatten()
                                    .toList()
                            } catch (e: Exception) {
                                println("Exception extracting rtcp.  The original, decrypted packet buffer is one of " +
                                        "these:\n")
                                prevRtcpPackets.forEach { println(it.getBuffer().toHex())}
                                emptyList()
                            }
                        }
                        node(RtcpTermination())
                        simpleNode("RTCP packet handler") {
                            println("RTCP packet handler forwarding ${it.size} rtcp packets")
//                            it.forEach { pkt ->
//                                println("Forwarding rtcp packet ${pkt::class} with buffer\n${pkt.getBuffer().toHex()}")
//                            }
                            rtcpPacketHandler?.processPackets(it)
                            emptyList()
                        }
                    }
                }
            }
        }

        scheduleWork()
    }

    private fun scheduleWork() {
        // Rescheduling this job after reading a single packet to allow
        // other threads to run doesn't seem  to scale all that well,
        // but doing this in a while (true) loop
        // holds a single thread exclusively, making it impossible to play
        // with things like sharing threads across tracks.  Processing a
        // max amount of packets at a time seems to work as a nice
        // compromise between the two.  It would be nice to be able to
        // avoid the busy-loop style polling for a new packet though
        //TODO: use drainTo (?)
        executor.execute {
            val packets = mutableListOf<Packet>()
            while (packets.size < 5) {
                val packet = incomingPacketQueue.poll() ?: break
                packets += packet
            }
            if (packets.isNotEmpty()) {
                processPackets(packets)
            }
            if (running) {
                scheduleWork()
            }
        }
    }

    override fun processPackets(pkts: List<Packet>) = inputTreeRoot.processPackets(pkts)

//    override fun attach(node: Node) {
//        rtpPacketHandler = node
//    }

    override fun getStats(indent: Int): String {

        return with (StringBuffer()) {
            appendLnIndent(indent, "RTP Receiver $id")
            appendLnIndent(indent, "queue size: ${incomingPacketQueue.size}")
            appendLnIndent(indent, "Received $packetsReceived packets ($bytesReceived bytes) in " +
                    "${lastPacketWrittenTime - firstPacketWrittenTime}ms " +
                    "(${getMbps(bytesReceived, Duration.ofMillis(lastPacketWrittenTime - firstPacketWrittenTime))} mbps)")
            val statsVisitor = NodeStatsVisitor(this)
            inputTreeRoot.visit(statsVisitor)
            toString()
        }
    }

    override fun enqueuePacket(p: Packet) {
        incomingPacketQueue.add(p)
        bytesReceived += p.size
        packetsReceived++
        if (firstPacketWrittenTime == 0L) {
            firstPacketWrittenTime = System.currentTimeMillis()
        }
        lastPacketWrittenTime = System.currentTimeMillis()
//        if (packetsReceived % 200 == 0L) {
//            println("BRIAN: module chain stats:\n${rootPacketHandler.getStats()}")
//        }
    }

    override fun setSrtpTransformer(srtpTransformer: SinglePacketTransformer) {
        srtpDecryptWrapper.setTransformer(srtpTransformer)
    }

    override fun setSrtcpTransformer(srtcpTransformer: SinglePacketTransformer) {
        srtcpDecryptWrapper.setTransformer(srtcpTransformer)
    }

    override fun handleEvent(event: Event) {
        inputTreeRoot.visit(NodeEventVisitor(event))
    }

    override fun setCsrcAudioLevelListener(csrcAudioLevelListener: CsrcAudioLevelListener) {
        println("BRIAN: rtpreceiverimpl setting csrc audio level listener")
        audioLevelListener.csrcAudioLevelListener = csrcAudioLevelListener
    }
}
