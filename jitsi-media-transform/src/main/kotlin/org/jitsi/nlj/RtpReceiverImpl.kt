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
import org.jitsi.nlj.rtp.AudioRtpPacket
import org.jitsi.nlj.rtp.VideoRtpPacket
import org.jitsi.nlj.transform.node.MediaTypeParser
import org.jitsi.nlj.transform.node.Node
import org.jitsi.nlj.transform.node.NodeEventVisitor
import org.jitsi.nlj.transform.node.NodeStatsVisitor
import org.jitsi.nlj.transform.node.PacketParser
import org.jitsi.nlj.transform.node.PayloadTypeFilterNode
import org.jitsi.nlj.transform.node.incoming.AudioLevelReader
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
import org.jitsi.nlj.util.cerror
import org.jitsi.nlj.util.cinfo
import org.jitsi.rtp.Packet
import org.jitsi.rtp.SrtcpPacket
import org.jitsi.rtp.SrtpPacket
import org.jitsi.rtp.SrtpProtocolPacket
import org.jitsi.rtp.extensions.toHex
import org.jitsi.rtp.rtcp.RtcpIterator
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.rtp.util.RtpProtocol
import org.jitsi.service.neomedia.event.CsrcAudioLevelListener
import org.jitsi.util.Logger
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
    private val incomingPacketQueue = LinkedBlockingQueue<PacketInfo>()
    private val srtpDecryptWrapper = SrtpTransformerDecryptNode()
    private val srtcpDecryptWrapper = SrtcpTransformerDecryptNode()
    private val tccGenerator = TccGeneratorNode(rtcpSender)
    private val payloadTypeFilter = PayloadTypeFilterNode()
    private val audioLevelListener = AudioLevelReader()
    private val rtcpTermination = RtcpTermination()

    companion object {
        val logger: Logger = Logger.getLogger(this::class.java)
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
    private val rtpPacketHandlerWrapper = object : Node("RTP packet handler wrapper") {
        override fun doProcessPackets(p: List<PacketInfo>) {
            rtpPacketHandler?.processPackets(p)
        }
    }

    /**
     * The [rtcpPacketHandler] can be re-assigned at any time, but it should maintain
     * its place in the receive pipeline.  To support both keeping it in the same
     * place and allowing it to be re-assigned, we wrap it with this.
     */
    private val rtcpPacketHandlerWrapper = object : Node("RTCP packet handler wrapper") {
        override fun doProcessPackets(p: List<PacketInfo>) {
            rtcpPacketHandler?.processPackets(p)
        }
    }

    // Stat tracking values
    var firstPacketWrittenTime: Long = 0
    var lastPacketWrittenTime: Long = 0
    var bytesReceived: Long = 0
    var packetsReceived: Long = 0

    init {
        logger.cinfo { "Receiver ${this.hashCode()} using executor ${executor.hashCode()}" }
        inputTreeRoot = pipeline {
            node(PacketParser("SRTP protocol parser") { SrtpProtocolPacket(it.getBuffer()) })
            demux {
                name = "SRTP/SRTCP demuxer"
                packetPath {
                    predicate = { pkt -> RtpProtocol.isRtp(pkt.getBuffer()) }
                    path = pipeline {
                        node(PacketParser("SRTP Parser") { SrtpPacket(it.getBuffer()) })
                        node(payloadTypeFilter)
                        node(tccGenerator)
                        node(srtpDecryptWrapper)
                        node(MediaTypeParser())
                        demux {
                            name = "Media type demuxer"
                            packetPath {
                                predicate = { pkt -> pkt is AudioRtpPacket }
                                path = pipeline {
                                    node(audioLevelListener)
                                    node(rtpPacketHandlerWrapper)
                                }
                            }
                            packetPath {
                                predicate = { pkt -> pkt is VideoRtpPacket }
                                path = pipeline {
                                    node(RtxHandler())
                                    node(PaddingTermination())
                                    node(VideoParser())
                                    node(RetransmissionRequester(rtcpSender))
                                    node(rtpPacketHandlerWrapper)
                                }
                            }
                        }
                    }
                }
                packetPath {
                    predicate = { pkt -> RtpProtocol.isRtcp(pkt.getBuffer()) }
                    path = pipeline {
                        val prevRtcpPackets = mutableListOf<Packet>()
                        node(PacketParser("SRTCP parser") { SrtcpPacket(it.getBuffer())} )
                        node(srtcpDecryptWrapper)
                        simpleNode("RTCP pre-parse cache ${hashCode()}") { pkts ->
                            prevRtcpPackets.clear()
                            pkts.forEach {
                                prevRtcpPackets.add(it.packet.clone())
                            }
                            pkts
                        }
                        node(PacketParser("RTCP parser") { RtcpPacket.fromBuffer(it.getBuffer()) })
                        //TODO: probably just make a class for this, but for now we're using the cache above to debug
                        simpleNode("Compound RTCP splitter") { pktInfos ->
                            try {
                                val outPackets = mutableListOf<PacketInfo>()
                                pktInfos.forEach { pktInfo ->
                                    val compoundRtcpPackets = RtcpIterator(pktInfo.packet.getBuffer()).getAll()
                                    compoundRtcpPackets.forEach {
                                        // For each compound RTCP packet, create a new PacketInfo with the packet and a copy
                                        // of any metadata in the original PacketInfo
                                        outPackets.add(PacketInfo(it, pktInfo.metaData.toMutableMap()))
                                    }
                                }
                                outPackets
                            } catch (e: Exception) {
                                logger.cerror {
                                    with (StringBuffer()) {
                                        appendln("Exception extracting RTCP.  The origina, decrypted packet buffer is " +
                                                "one of these:")
                                        prevRtcpPackets.forEach {
                                            appendln(it.getBuffer().toHex())
                                        }

                                        toString()
                                    }

                                }
                                emptyList()
                            }
                        }
                        node(rtcpTermination)
                        node(rtcpPacketHandlerWrapper)
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
//        logger.cinfo { "Receiver ${hashCode()} scheduling work" }
        executor.execute {
            val packets = mutableListOf<PacketInfo>()
            while (packets.size < 5) {
                val packet = incomingPacketQueue.poll() ?: break
                packets += packet
            }
            if (packets.isNotEmpty()) {
//                    logger.cinfo { "Receiver ${hashCode()} got data" }
                processPackets(packets)
            }

            if (running) {
                scheduleWork()
            }
        }
    }

    override fun processPackets(pkts: List<PacketInfo>) = inputTreeRoot.processPackets(pkts)

    override fun getStats(indent: Int): String {
        return with (StringBuffer()) {
            appendLnIndent(indent, "RTP Receiver $id")
            appendLnIndent(indent + 2, "queue size: ${incomingPacketQueue.size}")
            appendLnIndent(indent + 2, "Received $packetsReceived packets ($bytesReceived bytes) in " +
                    "${lastPacketWrittenTime - firstPacketWrittenTime}ms " +
                    "(${getMbps(bytesReceived, Duration.ofMillis(lastPacketWrittenTime - firstPacketWrittenTime))} mbps)")
            val statsVisitor = NodeStatsVisitor(this)
            inputTreeRoot.visit(statsVisitor)
            toString()
        }
    }

    override fun setNackHandler(nackHandler: NackHandler) {
        rtcpTermination.nackHandler = nackHandler
    }

    override fun enqueuePacket(p: PacketInfo) {
//        logger.cinfo { "Receiver ${hashCode()} enqueing data" }
        incomingPacketQueue.add(p)
        bytesReceived += p.packet.size
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
        inputTreeRoot.visit(NodeEventVisitor(event))
    }

    override fun setCsrcAudioLevelListener(csrcAudioLevelListener: CsrcAudioLevelListener) {
        audioLevelListener.csrcAudioLevelListener = csrcAudioLevelListener
    }
}
