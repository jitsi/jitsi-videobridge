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
import org.jitsi.nlj.transform.node.Node
import org.jitsi.nlj.transform.node.incoming.DtlsReceiverNode
import org.jitsi.nlj.transform.node.outgoing.DtlsSenderNode
import org.jitsi.rtp.Packet
import org.jitsi.rtp.extensions.toHex
import org.jitsi.service.neomedia.RTPExtension
import org.jitsi.service.neomedia.format.MediaFormat
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
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
    private val rtpExtensions = mutableMapOf<Byte, RTPExtension>()
    private val payloadTypes = mutableMapOf<Byte, MediaFormat>()
    private val receiveSsrcs = ConcurrentHashMap.newKeySet<Long>()

    /*private*/ val rtpSender: RtpSender = RtpSenderImpl(123, executor)
    /*private*/ val rtpReceiver: RtpReceiver =
        RtpReceiverImpl(
            123,
            { rtcpPacket ->
                rtpSender.sendRtcp(listOf(rtcpPacket))
            },
            executor)

    private val incomingChain: PacketHandler

    val incomingQueue = LinkedBlockingQueue<Packet>()
    val outgoingQueue = LinkedBlockingQueue<Packet>()

    var running = true

    init {
        println("Transceiver ${this.hashCode()} using executor ${executor.hashCode()}")
        incomingChain = rtpReceiver

        // rewire the sender's hacked packet handler
        rtpSender.packetSender = object : Node("RTP packet sender") {
            override fun doProcessPackets(p: List<Packet>) {
                outgoingQueue.addAll(p) }
            }
        scheduleWork()
    }

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

    fun addReceiveSsrc(ssrc: Long) {
        receiveSsrcs.add(ssrc)
        rtpReceiver.handleEvent(ReceiveSsrcAddedEvent(ssrc))
    }

    fun removeReceiveSsrc(ssrc: Long) {
        receiveSsrcs.remove(ssrc)
        rtpReceiver.handleEvent(ReceiveSsrcRemovedEvent(ssrc))
    }

    fun receivesSsrc(ssrc: Long): Boolean = receiveSsrcs.contains(ssrc)

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
