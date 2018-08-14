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

import org.jitsi.nlj.dtls.DtlsClientStack
import org.jitsi.nlj.dtls.QueueDatagramTransport
import org.jitsi.nlj.dtls.TlsClientImpl
import org.jitsi.nlj.srtp_og.SRTPTransformer
import org.jitsi.nlj.transform.chain
import org.jitsi.nlj.transform.module.Module
import org.jitsi.nlj.transform.module.ModuleChain
import org.jitsi.nlj.transform.module.PacketHandler
import org.jitsi.nlj.transform.module.PacketWriter
import org.jitsi.nlj.transform.module.getMbps
import org.jitsi.nlj.transform.module.incoming.DtlsReceiverModule
import org.jitsi.nlj.transform.module.incoming.SrtpTransformerWrapperDecrypt
import org.jitsi.nlj.transform.module.outgoing.DtlsSenderModule
import org.jitsi.nlj.transform.packetPath
import org.jitsi.rtp.DtlsProtocolPacket
import org.jitsi.rtp.Packet
import org.jitsi.rtp.RtpPacket
import org.jitsi.rtp.SrtcpPacket
import org.jitsi.rtp.SrtpPacket
import org.jitsi.rtp.SrtpProtocolPacket
import org.jitsi.rtp.util.RtpProtocol
import unsigned.toUInt
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import kotlin.experimental.and

class RtpReceiverImpl @JvmOverloads constructor(
    val id: Long,
    val executor: ExecutorService = Executors.newSingleThreadExecutor(),
    val packetHandler: PacketHandler? = null
) : RtpReceiver() {
    private val moduleChain: ModuleChain
    private val incomingPacketQueue = LinkedBlockingQueue<Packet>()
    var running = true
    var dtlsStack = DtlsClientStack()

    var firstPacketWrittenTime: Long = 0
    var lastPacketWrittenTime: Long = 0
    var bytesReceived: Long = 0

    var dtlsReceiver = DtlsReceiverModule()
    var dtlsSender = DtlsSenderModule()

    init {
        moduleChain = chain {
            name("$id Incoming packet chain")
            demux {
                name = "DTLS/SRTP demuxer"
                packetPath {
                    predicate = { packet ->
                        val byte = (packet.buf.get(0) and 0xFF.toByte()).toUInt()
                        when (byte) {
                            in 20..63 -> true
                            else -> false
                        }
                    }
                    path = chain {
                        name ("DTLS chain")
                        addModule(object : Module("DTLS parser") {
                            override fun doProcessPackets(p: List<Packet>) {
                                println("BRIAN: dtls parser got data")
                                next(p.map(Packet::buf).map(::DtlsProtocolPacket))
                            }
                        })
                        addModule(dtlsReceiver)
                    }
                }
                packetPath {
                    predicate = { packet ->
                        val byte = (packet.buf.get(0) and 0xFF.toByte()).toUInt()
                        when (byte) {
                            in 20..63 -> false
                            else -> true
                        }
                    }
                    path = chain {
                        name("SRTP chain")
                        addModule(object : Module("SRTP protocol parser") {
                            override fun doProcessPackets(p: List<Packet>) {
                                next(p.map(Packet::buf).map(::SrtpProtocolPacket))
                            }
                        })
                        demux {
                            name = "SRTP/SRTCP demuxer"
                            packetPath {
                                predicate = { pkt -> RtpProtocol.isRtp(pkt.buf) }
                                path = chain {
                                    addModule(object : Module("SRTP parser") {
                                        override fun doProcessPackets(p: List<Packet>) {
                                            println("BRIAN: SRTP parser $this got ${p.size} packets")
                                            val outPackets = mutableListOf<SrtpPacket>()
                                            p.forEach { pkt ->
                                                pkt as SrtpProtocolPacket
                                                println("BRIAN: srtp parser looking at packet with size ${pkt.size}")
                                                val op = SrtpPacket(pkt.buf)
                                                println("BRIAN: srtp parser $this passing on packet ${op.ssrc} ${op.seqNum} size ${op.size}")
                                                outPackets.add(op)
                                            }
                                            println("BRIAN: SRTP parser $this forwarding ${outPackets.size} packets")
                                            next(outPackets)
//                                            next(p.map(Packet::buf).map(::SrtpPacket))
                                        }
                                    })
                                    val decryptWrapper = SrtpTransformerWrapperDecrypt()
                                    dtlsStack.subscribe { dtlsTransport, tlsContext ->
                                        val transformer = SRTPTransformer.initializeSRTPTransformer(
                                            dtlsStack.getChosenSrtpProtectionProfile(), tlsContext, false
                                        )
                                        decryptWrapper.srtpTransformer = transformer
                                    }
                                    addModule(decryptWrapper)
//                                    val srtpDecrypt = SrtpDecryptModule()
//                                    dtlsStack.subscribe { dtlsTransport, tlsContext ->
//                                        try {
//                                            val protectionProfileInformation =
//                                                Util.getProtectionProfileInformation(dtlsStack.getChosenSrtpProtectionProfile())
//                                            val keyingMaterial =
//                                                tlsContext.exportKeyingMaterial(
//                                                    ExporterLabel.dtls_srtp,
//                                                    null,
//                                                    2 * (protectionProfileInformation.cipherKeyLength + protectionProfileInformation.cipherSaltLength)
//                                                )
//                                            println("BRIAN rtp receiver creating context factories")
//                                            val factories =
//                                                Util.createSrtpContextFactories(
//                                                    protectionProfileInformation,
//                                                    keyingMaterial,
//                                                    tlsContext is TlsClientContext
//                                                )
//                                            println("BRIAN: setting decrypt factory on srtpdecrypt ${srtpDecrypt.hashCode()}")
////                                            srtpDecrypt.setDecryptFactory(factories.decryptFactory)
//                                            srtpDecrypt.setDecryptFactory(factories.encryptFactory)
//                                        } catch (e: Exception) {
//                                            println("BRIAN: exception creating factories: $e")
//                                            e.printStackTrace(System.out)
//                                        } catch (t: Throwable) {
//                                            println("BRIAN: excption creating factories: $t")
//                                            t.printStackTrace(System.out)
//                                        }
//                                    }
//                                    addModule(srtpDecrypt)
                                    addModule(object : Module("vp8 filter") {
                                        override fun doProcessPackets(p: List<Packet>) {
                                            val outpackets = p.map { it as RtpPacket}
                                                .filter { it.header.payloadType == 100}
                                                .toCollection(ArrayList())
                                            next(outpackets)
                                        }
                                    })
                                    addModule(PacketWriter())
//                                    addModule(object : Module("RTP handler") {
//                                        override fun doProcessPackets(p: List<Packet>) {
//                                            p.forEachAs<RtpPacket> { pkt ->
////                                                println("BRIAN: received rtp packet: " +
////                                                        "${pkt.header.ssrc} ${pkt.header.sequenceNumber} ${pkt.header.payloadType}\n" +
////                                                        pkt.payload.toHex()
////                                                )
//                                                if (pkt.header.payloadType == 100) {
//                                                    val vp8pd = Vp8PayloadDescriptor(pkt.payload)
//                                                    println("BRIAN: received vp8 packet ${pkt.header.ssrc} ${pkt.header.sequenceNumber} " +
//                                                            "with descriptor:\n$vp8pd")
//                                                }
//                                            }
//                                        }
//                                    })
                                }
                            }
                            packetPath {
                                predicate = { pkt -> RtpProtocol.isRtcp(pkt.buf) }
                                path = chain {
                                    addModule(object : Module("SRTCP parser") {
                                        override fun doProcessPackets(p: List<Packet>) {
                                            println("BRIAN: got srtcp")
                                            next(p.map(Packet::buf).map(::SrtcpPacket))
                                        }
                                    })
                                    //addModule(srtcpDecrypt)
                                }
                            }
                        }
                    }
                }
            }
        }
        scheduleWork()
    }

    fun connectDtls() {
        println("BRIAN: submitting dtls connect task")
        val tlsTransport = QueueDatagramTransport(
            dtlsReceiver::receive,
            { buf, off, len -> dtlsSender.send(buf, off, len) },
            1500
        )
        executor.submit {
            println("BRIAN: running dtlsStack.connect")
            try {
                dtlsStack.connect(TlsClientImpl(), tlsTransport)
            } catch (e: Exception) {
                println("BRIAN: exception during dtls connect: $e")
            }
        }
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
        executor.execute {
            val packets = mutableListOf<Packet>()
            while (packets.size < 5) {
                val packet = incomingPacketQueue.poll() ?: break
                packets += packet
            }
            if (packets.isNotEmpty()) processPackets(packets)
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
            appendln("Received $bytesReceived bytes in ${lastPacketWrittenTime - firstPacketWrittenTime}ms (${getMbps(bytesReceived, Duration.ofMillis(lastPacketWrittenTime - firstPacketWrittenTime))} mbps)")
            append(moduleChain.getStats())
            toString()
        }
    }

    override fun enqueuePacket(p: Packet) {
        println("BRIAN: RtpReceiver enqueing packet of size ${p.size}")
        incomingPacketQueue.add(p)
        bytesReceived += p.size
        if (firstPacketWrittenTime == 0L) {
            firstPacketWrittenTime = System.currentTimeMillis()
        }
        lastPacketWrittenTime = System.currentTimeMillis()
    }
}
