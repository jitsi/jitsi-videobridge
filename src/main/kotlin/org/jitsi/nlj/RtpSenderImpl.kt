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

import org.jitsi.nlj.transform.node.outgoing.SrtcpTransformerEncryptNode
import org.jitsi.nlj.transform.node.outgoing.SrtpTransformerEncryptNode
import org.jitsi.nlj.transform_og.SinglePacketTransformer
import org.jitsi.nlj.util.Util.Companion.getMbps
import org.jitsi.rtp.Packet
import org.jitsi.rtp.rtcp.RtcpPacket
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue

class RtpSenderImpl(
    val id: Long,
    val executor: ExecutorService /*= Executors.newSingleThreadExecutor()*/
) : RtpSender() {
    private val outgoingRtpChain: PacketHandler? = null
    private val outgoingRtcpChain: PacketHandler? = null
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
//        outgoingRtpChain = chain {
//            name("Outgoing RTP chain")
//            addModule(srtpEncryptWrapper)
//        }
//        outgoingRtcpChain = packetTree {
//            simpleHandler("RTCP sender ssrc setter") { pkts ->
//                tempSenderSsrc?.let { senderSsrc ->
//                    pkts.forEachAs<RtcpPacket> {
//                        it.header.senderSsrc = senderSsrc
////                            println("Sending rtcp $it")
//                    }
//                    next?.processPackets(pkts)
//                }
//            }
//            handler(srtcpEncryptWrapper)
//            simpleHandler("RTCP sender") { pkts ->
//                packetSender.processPackets(pkts)
//            }
//
//        }

//        outgoingRtcpChain = chain {
//            name("Outgoing RTCP chain")
//            addModule(object : Module("RTCP field setter") {
//                override fun doProcessPackets(p: List<Packet>) {
//                    //TODO: for now, hard code an ssrc we've sent out
//                    // before as the senderSsrc in this packet
//                    tempSenderSsrc?.let { senderSsrc ->
//                        p.forEachAs<RtcpPacket> {
//                            it.header.senderSsrc = senderSsrc
////                            println("Sending rtcp $it")
//                        }
//                        next(p)
//                    } ?: run {
//                        println("RTCP chain no sender ssrc set")
//                    }
//                }
//            })
//            addModule(srtcpEncryptWrapper)
//            addModule(object : Module("Packet sender") {
//                override fun doProcessPackets(p: List<Packet>) {
////                    p.forEachAs<SrtcpPacket> {
////                        println("Rtcp chain sending rtcp packet: $it\n${it.getBuffer().toHex()}")
////                    }
//                    packetSender.processPackets(p)
//                }
//            })
//        }
//        outgoingRtpChain = packetTree {
//            handler(srtpEncryptWrapper)
//            handler {
//                simpleHandler("Sender ssrc setter") { pkts ->
//                    if (tempSenderSsrc == null) {
//                        pkts.forEachIf<SrtpPacket> {
//                            tempSenderSsrc = it.header.ssrc
//                        }
//                    }
//                    packetSender.processPackets(pkts)
//                }
//            }
//        }

//        outgoingRtpChain = chain {
//            //TODO: for now just hard-code the rtp path
//            addModule(srtpEncryptWrapper)
//            addModule(object : Module("Packet sender") {
//                override fun doProcessPackets(p: List<Packet>) {
//                    if (tempSenderSsrc == null) {
//                        p.forEachIf<SrtpPacket> {
//                            tempSenderSsrc = it.header.ssrc
//                        }
//                    }
//                    packetSender.processPackets(p)
//                }
//            })
//        }
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
        outgoingRtcpChain?.processPackets(pkts)
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
                if (packetsToProcess.isNotEmpty()) outgoingRtpChain?.processPackets(packetsToProcess)

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
            append(outgoingRtpChain?.getStats())
            toString()
        }
    }
}
