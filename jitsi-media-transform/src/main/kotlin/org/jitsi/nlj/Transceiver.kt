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

import org.bouncycastle.crypto.tls.TlsContext
import org.jitsi.nlj.dtls.DtlsClientStack
import org.jitsi.nlj.srtp.SrtpUtil
import org.jitsi.nlj.srtp.TlsRole
import org.jitsi.nlj.transform.module.incoming.DtlsReceiverModule
import org.jitsi.nlj.transform.module.outgoing.DtlsSenderModule
import org.jitsi.nlj.transform.node.Node
import org.jitsi.rtp.Packet
import org.jitsi.rtp.extensions.toHex
import org.jitsi.service.neomedia.RTPExtension
import org.jitsi.service.neomedia.format.MediaFormat
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue

// This is an API class, so its usages will largely be outside of this library
@Suppress("unused")
/**
 * Handles all packets (incoming and outgoing) for a particular stream.
 * (TODO: 'stream' defined as what, exactly, here?)
 * Handles the DTLS negotiation
 *
 * Incoming packets should be written to [incomingQueue].  Outgoing
 * packets are put in [outgoingQueue] (and should be read by something)
 * TODO: maybe we want to have this 'push' the outgoing packets somewhere
 * else instead (then we could have all senders push to a single queue and
 * have the one thread just read from the queue and send, rather than that thread
 * having to read from a bunch of individual queues)
 */
class Transceiver(
    private val id: String,
    private val executor: ExecutorService /*= Executors.newSingleThreadExecutor()*/
) {
    private val dtlsStack = DtlsClientStack()
    private val dtlsReceiver = DtlsReceiverModule()
    private val dtlsSender = DtlsSenderModule()
    private val rtpExtensions = mutableMapOf<Byte, RTPExtension>()
    private val payloadTypes = mutableMapOf<Byte, MediaFormat>()

    /*private*/ val rtpSender: RtpSender = RtpSenderImpl(123, executor)
    /*private*/ val rtpReceiver: RtpReceiver =
        RtpReceiverImpl(
            123,
            { rtcpPacket ->
                rtpSender.sendRtcp(listOf(rtcpPacket))
            },
            executor)

    // The incoming chain in the Transceiver handles the DTLS
    // handshake and then defers to the RtpReceiver's input chain
    private val incomingChain: PacketHandler

    val incomingQueue = LinkedBlockingQueue<Packet>()
    val outgoingQueue = LinkedBlockingQueue<Packet>()

    var running = true

    init {
        println("Transceiver ${this.hashCode()} using executor ${executor.hashCode()}")
//        dtlsStack.onHandshakeComplete { dtlsTransport, tlsContext ->
//            val srtpProfileInfo =
//                SrtpUtil.getSrtpProfileInformationFromSrtpProtectionProfile(dtlsStack.getChosenSrtpProtectionProfile())
//            val keyingMaterial = SrtpUtil.getKeyingMaterial(tlsContext, srtpProfileInfo)
//            println("Transceiver $id creating transformers with:\n" +
//                    "profile info:\n$srtpProfileInfo\n" +
//                    "keyingMaterial:\n${ByteBuffer.wrap(keyingMaterial).toHex()}\n" +
//                    "tls role: ${TlsRole.fromTlsContext(tlsContext)}")
//            val srtpTransformer = SrtpUtil.initializeTransformer(
//                srtpProfileInfo,
//                keyingMaterial,
//                TlsRole.fromTlsContext(tlsContext),
//                false
//            )
//            val srtcpTransformer = SrtpUtil.initializeTransformer(
//                srtpProfileInfo,
//                keyingMaterial,
//                TlsRole.fromTlsContext(tlsContext),
//                true
//            )
//
//            rtpReceiver.setSrtpTransformer(srtpTransformer)
//            rtpReceiver.setSrtcpTransformer(srtcpTransformer)
//            rtpSender.setSrtpTransformer(srtpTransformer)
//            rtpSender.setSrtcpTransformer(srtcpTransformer)
//        }

        incomingChain = rtpReceiver

//        incomingChain = chain {
//            name("Transceiver incoming chain")
//            demux {
//                packetPath {
//                    name = "DTLS protocol chain"
//                    predicate = { packet ->
//                        val byte = (packet.getBuffer().get(0) and 0xFF.toByte()).toUInt()
//                        when (byte) {
//                            in 20..63 -> true
//                            else -> false
//                        }
//                    }
//                    path = chain {
//                        name ("DTLS chain")
//                        addModule(object : Module("DTLS parser") {
//                            override fun doProcessPackets(p: List<Packet>) {
//                                next(p.map(Packet::getBuffer).map(::DtlsProtocolPacket))
//                            }
//                        })
//                        addModule(dtlsReceiver)
//                    }
//                }
//                packetPath {
//                    name = "RTP protocol chain"
//                    predicate = { packet ->
//                        val byte = (packet.getBuffer().get(0) and 0xFF.toByte()).toUInt()
//                        when (byte) {
//                            in 20..63 -> false
//                            else -> true
//                        }
//                    }
//                    path = rtpReceiver
//                }
//            }
//        }


        // rewire the sender's hacked packet handler
//        rtpSender.packetSender = SimplePacketHandler("RtpSender packet sender") { pkts -> outgoingQueue.addAll(pkts) }
        rtpSender.packetSender = object : Node("RTP packet sender") {
            override fun doProcessPackets(p: List<Packet>) {
                outgoingQueue.addAll(p) }
            }
        scheduleWork()
    }

//    fun connectDtls() {
//        dtlsSender.attach { pkts ->
//            println("BRIAN: dtls adding packets to outgoing queue")
//            outgoingQueue.addAll(pkts)
//        }
//        val tlsTransport = QueueDatagramTransport(
//            dtlsReceiver::receive,
//            { buf, off, len -> dtlsSender.send(buf, off, len) },
//            1500
//        )
//        executor.submit {
//            println("BRIAN: starting DTLS handshake")
//            try {
//                dtlsStack.connect(TlsClientImpl(), tlsTransport)
//            } catch (e: Exception) {
//                println("BRIAN: exception during dtls connect: $e")
//            }
//        }
//    }
//
//    fun getLocalFingerprint(): String = dtlsStack.localFingerprint
//    fun getLocalFingerprintHashFunction(): String = dtlsStack.localFingerprintHashFunction
//    fun setRemoteFingerprints(remoteFingerprints: Map<String, String>) {
//        dtlsStack.remoteFingerprints = remoteFingerprints
//    }

    fun sendPackets(p: List<Packet>) {
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

    fun addDynamicRtpPayloadType(rtpPayloadType: Byte, format: MediaFormat) {
        payloadTypes[rtpPayloadType] = format
        println("Payload type added: $rtpPayloadType -> $format")
        rtpReceiver.handleEvent(RtpPayloadTypeAddedEvent(rtpPayloadType, format))
    }

    fun clearDynamicRtpPayloadTypes() {
        println("All payload types being cleared")
        rtpReceiver.handleEvent(RtpPayloadTypeClearEvent())
        payloadTypes.clear()
    }

    fun addDynamicRtpPayloadTypeOverride(originalPt: Byte, overloadPt: Byte) {
        //TODO
        println("Overriding payload type $originalPt to $overloadPt")
    }

    fun addRtpExtension(extensionId: Byte, rtpExtension: RTPExtension) {
        println("Adding RTP extension: $extensionId -> $rtpExtension")
        rtpExtensions[extensionId] = rtpExtension
        rtpReceiver.handleEvent(RtpExtensionAddedEvent(extensionId, rtpExtension))
    }

    fun clearRtpExtensions() {
        println("Clearing all RTP extensions")
        rtpReceiver.handleEvent(RtpExtensionClearEvent())
        rtpExtensions.clear()
    }

    fun setSrtpInformation(chosenSrtpProtectionProfile: Int, tlsContext: TlsContext) {
        val srtpProfileInfo =
            SrtpUtil.getSrtpProfileInformationFromSrtpProtectionProfile(chosenSrtpProtectionProfile)
        val keyingMaterial = SrtpUtil.getKeyingMaterial(tlsContext, srtpProfileInfo)
        println("Transceiver $id creating transformers with:\n" +
                "profile info:\n$srtpProfileInfo\n" +
                "keyingMaterial:\n${ByteBuffer.wrap(keyingMaterial).toHex()}\n" +
                "tls role: ${TlsRole.fromTlsContext(tlsContext)}")
        val srtpTransformer = SrtpUtil.initializeTransformer(
            srtpProfileInfo,
            keyingMaterial,
            TlsRole.fromTlsContext(tlsContext),
            false
        )
        val srtcpTransformer = SrtpUtil.initializeTransformer(
            srtpProfileInfo,
            keyingMaterial,
            TlsRole.fromTlsContext(tlsContext),
            true
        )

        rtpReceiver.setSrtpTransformer(srtpTransformer)
        rtpReceiver.setSrtcpTransformer(srtcpTransformer)
        rtpSender.setSrtpTransformer(srtpTransformer)
        rtpSender.setSrtcpTransformer(srtcpTransformer)
    }
}
