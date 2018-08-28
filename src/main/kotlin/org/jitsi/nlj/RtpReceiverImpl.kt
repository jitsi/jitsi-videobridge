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

import org.jitsi.nlj.transform.chain
import org.jitsi.nlj.transform.module.Module
import org.jitsi.nlj.transform.module.ModuleChain
import org.jitsi.nlj.transform.module.PacketHandler
import org.jitsi.nlj.transform.module.PayloadTypeFilterModule
import org.jitsi.nlj.transform.module.forEachAs
import org.jitsi.nlj.transform.module.getMbps
import org.jitsi.nlj.transform.module.incoming.SrtcpTransformerWrapperDecrypt
import org.jitsi.nlj.transform.module.incoming.SrtpTransformerWrapperDecrypt
import org.jitsi.nlj.transform.module.incoming.TccGeneratorModule
import org.jitsi.nlj.transform.packetPath
import org.jitsi.nlj.transform_og.SinglePacketTransformer
import org.jitsi.rtp.Packet
import org.jitsi.rtp.SrtcpPacket
import org.jitsi.rtp.SrtpPacket
import org.jitsi.rtp.SrtpProtocolPacket
import org.jitsi.rtp.rtcp.RtcpIterator
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.rtp.util.RtpProtocol
import org.jitsi.service.neomedia.RTPExtension
import org.jitsi.service.neomedia.format.MediaFormat
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
    override val moduleChain: ModuleChain
    private val incomingPacketQueue = LinkedBlockingQueue<Packet>()
    private val srtpDecryptWrapper = SrtpTransformerWrapperDecrypt()
    private val srtcpDecryptWrapper = SrtcpTransformerWrapperDecrypt()
    private val tccGenerator = TccGeneratorModule(rtcpSender)
    private val payloadTypeFilter = PayloadTypeFilterModule()

    /**
     * This [RtpReceiver] will invoke this method with RTP packets that have
     * made it through the entire receive pipeline.  A caller should set this
     * variable to a function for handling fully-processed RTP packets from
     * this receiver.
     */
    override var rtpPacketHandler: PacketHandler = {}

    // Stat tracking values
    var firstPacketWrittenTime: Long = 0
    var lastPacketWrittenTime: Long = 0
    var bytesReceived: Long = 0
    var packetsReceived: Long = 0

    init {
        println("Receiver ${this.hashCode()} using executor ${executor.hashCode()}")
        moduleChain = chain {
            name("Receive chain")
            addModule(object : Module("SRTP protocol parser") {
                override fun doProcessPackets(p: List<Packet>) {
                    next(p.map(Packet::getBuffer).map(::SrtpProtocolPacket))
                }
            })
            demux {
                name = "SRTP/SRTCP demuxer"
                packetPath {
                    name = "SRTP Path"
                    predicate = { pkt -> RtpProtocol.isRtp(pkt.getBuffer()) }
                    path = chain {
                        addModule(object : Module("SRTP parser") {
                            override fun doProcessPackets(p: List<Packet>) {
                                next(p.map(Packet::getBuffer).map(::SrtpPacket))
                            }
                        })
                        addModule(payloadTypeFilter)
                        addModule(tccGenerator)
                        addModule(srtpDecryptWrapper)
                        addModule(object : Module("packet handler") {
                            override fun doProcessPackets(p: List<Packet>) {
                                rtpPacketHandler.invoke(p)
                            }
                        })
                    }
                }
                packetPath {
                    name = "SRTCP path"
                    predicate = { pkt -> RtpProtocol.isRtcp(pkt.getBuffer()) }
                    path = chain {
                        addModule(object : Module("SRTCP parser") {
                            override fun doProcessPackets(p: List<Packet>) {
                                next(p.map(Packet::getBuffer).map(::SrtcpPacket))
                            }
                        })
                        addModule(srtcpDecryptWrapper)
                        addModule(object : Module("Compound RTCP splitter") {
                            override fun doProcessPackets(p: List<Packet>) {
                                val outPackets = mutableListOf<RtcpPacket>()
                                p.forEach {
                                    val iter = RtcpIterator(it.getBuffer())
                                    val pkts = iter.getAll()
//                                    println("BRIAN: extracted ${pkts.size} rtcp packets from compound:\n$pkts")
                                    outPackets.addAll(pkts)
                                }
                                if (outPackets.isNotEmpty()) {
                                    next(outPackets)
                                }
                            }
                        })
                        addModule(object : Module("RTCP Handler") {
                            override fun doProcessPackets(p: List<Packet>) {
                                p.forEachAs<RtcpPacket> {
//                                    println("BRIAN: got decrypted rtcp $it")
//                                    println("BRIAN: rtcp packet:\n$it\n with buffer:\n ${it.getBuffer().toHex()}")
                                }
                            }
                        })
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

    override fun processPackets(pkts: List<Packet>) = moduleChain.processPackets(pkts)

    override fun getStats(): String {
        return with (StringBuffer()) {
            appendln("RTP Receiver $id")
            appendln("queue size: ${incomingPacketQueue.size}")
            appendln("Received $packetsReceived packets ($bytesReceived bytes) in " +
                    "${lastPacketWrittenTime - firstPacketWrittenTime}ms " +
                    "(${getMbps(bytesReceived, Duration.ofMillis(lastPacketWrittenTime - firstPacketWrittenTime))} mbps)")
            append(moduleChain.getStats())
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
//            println("BRIAN: module chain stats: ${moduleChain.getStats()}")
//        }
    }

    override fun setSrtpTransformer(srtpTransformer: SinglePacketTransformer) {
        srtpDecryptWrapper.setTransformer(srtpTransformer)
    }
    override fun setSrtcpTransformer(srtcpTransformer: SinglePacketTransformer) {
        srtcpDecryptWrapper.setTransformer(srtcpTransformer)
    }

    override fun onRtpExtensionAdded(extensionId: Byte, rtpExtension: RTPExtension) {
        println("RTP receiver adding extension ${rtpExtension.uri}")
        //TODO: change these to iterate through a list of modules
        tccGenerator.onRtpExtensionAdded(extensionId, rtpExtension)
    }

    override fun onRtpExtensionRemoved(extensionId: Byte) {
        tccGenerator.onRtpExtensionRemoved(extensionId)
    }

    override fun onRtpPayloadTypeAdded(payloadType: Byte, format: MediaFormat) {
        payloadTypeFilter.onRtpPayloadTypeAdded(payloadType, format)
    }

    override fun onRtpPayloadTypeRemoved(payloadType: Byte) {
        payloadTypeFilter.onRtpPayloadTypeRemoved(payloadType)
    }
}
