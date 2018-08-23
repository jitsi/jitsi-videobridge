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
import org.jitsi.nlj.transform.module.forEachAs
import org.jitsi.nlj.transform.module.incoming.DtlsReceiverModule
import org.jitsi.nlj.transform.module.outgoing.DtlsSenderModule
import org.jitsi.nlj.transform.packetPath
import org.jitsi.rtp.DtlsProtocolPacket
import org.jitsi.rtp.Packet
import org.jitsi.rtp.RtpPacket
import unsigned.toUInt
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import kotlin.experimental.and

/**
 * Handles all packets (incoming and outgoing) for a particular stream.
 * (TODO: 'stream' defined as what, exactly, here?)
 * Handles the DTLS negotiation
 *
 * Incoming packets should be written to [incomingQueue].  Outgoing
 * packets are put in [outgoingQueue] (and should be read by something)
 * TODO: maybe we want to have this 'push' the outgoing packets somewhere
 * else instead
 */
class Transceiver(
    private val executor: ExecutorService /*= Executors.newSingleThreadExecutor()*/
) {
    private val dtlsStack = DtlsClientStack()
    private val dtlsReceiver = DtlsReceiverModule()
    private val dtlsSender = DtlsSenderModule()

    /*private*/ val rtpReceiver: RtpReceiver = RtpReceiverImpl(123, executor)
    /*private*/ val rtpSender: RtpSender = RtpSenderImpl(123, executor)

    // The incoming chain in the Transceiver handles the DTLS
    // handshake and then defers to the RtpReceiver's input chain
    private val incomingChain: ModuleChain

    val incomingQueue = LinkedBlockingQueue<Packet>()
    val outgoingQueue = LinkedBlockingQueue<Packet>()

    var running = true

    init {
        println("Transceiver ${this.hashCode()} using executor ${executor.hashCode()}")
        dtlsStack.onHandshakeComplete { dtlsTransport, tlsContext ->
            //TODO: in the future we'll want to pass the dtls transport to the sctp connection
            val srtpTransformer = SRTPTransformer.initializeSRTPTransformer(
                dtlsStack.getChosenSrtpProtectionProfile(), tlsContext, false
            )
            val srtcpTransformer = SRTPTransformer.initializeSRTPTransformer(
                dtlsStack.getChosenSrtpProtectionProfile(), tlsContext, true
            )
            rtpReceiver.setSrtpTransformer(srtpTransformer)
            rtpReceiver.setSrtcpTransformer(srtcpTransformer)
            rtpSender.setSrtpTransformer(srtpTransformer)
        }

        incomingChain = chain {
            name("Transceiver incoming chain")
            demux {
                packetPath {
                    predicate = { packet ->
                        val byte = (packet.getBuffer().get(0) and 0xFF.toByte()).toUInt()
                        when (byte) {
                            in 20..63 -> true
                            else -> false
                        }
                    }
                    path = chain {
                        name ("DTLS chain")
                        addModule(object : Module("DTLS parser") {
                            override fun doProcessPackets(p: List<Packet>) {
                                next(p.map(Packet::getBuffer).map(::DtlsProtocolPacket))
                            }
                        })
                        addModule(dtlsReceiver)
                    }
                }
                packetPath {
                    predicate = { packet ->
                        val byte = (packet.getBuffer().get(0) and 0xFF.toByte()).toUInt()
                        when (byte) {
                            in 20..63 -> false
                            else -> true
                        }
                    }
                    path = rtpReceiver.moduleChain
                }
            }
        }


        // rewire the sender's hacked packet handler
        rtpSender.packetSender = { pkts ->
            outgoingQueue.addAll(pkts)
        }

        scheduleWork()
    }

    fun connectDtls() {
        dtlsSender.attach { pkts ->
            println("BRIAN: dtls adding packets to outgoing queue")
            outgoingQueue.addAll(pkts)
        }
        val tlsTransport = QueueDatagramTransport(
            dtlsReceiver::receive,
            { buf, off, len -> dtlsSender.send(buf, off, len) },
            1500
        )
        executor.submit {
            println("BRIAN: starting DTLS handshake")
            try {
                dtlsStack.connect(TlsClientImpl(), tlsTransport)
            } catch (e: Exception) {
                println("BRIAN: exception during dtls connect: $e")
            }
        }
    }

    fun getLocalFingerprint(): String = dtlsStack.localFingerprint
    fun getLocalFingerprintHashFunction(): String = dtlsStack.localFingerprintHashFunction
    fun setRemoteFingerprints(remoteFingerprints: Map<String, String>) {
        dtlsStack.remoteFingerprints = remoteFingerprints
    }

    fun sendPackets(p: List<Packet>) {
//        println("BRIAN: transceiver sending ${p.size} packets")
//        p.forEachAs<RtpPacket> {
//            println("BRIAN: transceiver sending packet ${it.header}")
//        }
        rtpSender.sendPackets(p)
    }

    private fun scheduleWork() {
        executor.execute {
            val packets = mutableListOf<Packet>()
            incomingQueue.drainTo(packets, 5)
            if (packets.isNotEmpty()) incomingChain.processPackets(packets)
            if (running) {
                scheduleWork()
            }
        }
    }
}
