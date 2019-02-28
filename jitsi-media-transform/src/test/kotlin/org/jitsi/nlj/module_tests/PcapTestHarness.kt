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
package org.jitsi.nlj.module_tests

import io.pkts.Pcap
import io.pkts.packet.UDPPacket
import io.pkts.protocol.Protocol
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.RtpExtensionAddedEvent
import org.jitsi.nlj.RtpPayloadTypeAddedEvent
import org.jitsi.nlj.RtpReceiver
import org.jitsi.nlj.RtpReceiverImpl
import org.jitsi.nlj.RtpSender
import org.jitsi.nlj.RtpSenderImpl
import org.jitsi.nlj.format.OpusPayloadType
import org.jitsi.nlj.format.Vp8PayloadType
import org.jitsi.nlj.rtcp.RtcpEventNotifier
import org.jitsi.nlj.srtp.SrtpProfileInformation
import org.jitsi.nlj.srtp.SrtpUtil
import org.jitsi.nlj.srtp.TlsRole
import org.jitsi.nlj.transform.node.Node
import org.jitsi.nlj.util.NameableThreadFactory
import org.jitsi.nlj.util.safeShutdown
import org.jitsi.rtp.UnparsedPacket
import org.jitsi.rtp.extensions.clone
import org.jitsi.service.neomedia.RTPExtension
import java.lang.Thread.sleep
import java.net.URI
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

// All of this information is specific to the pcap file
const val pcapFile = "/Users/bbaldino/new_pipeline_captures/capture_1_incoming_participant_1_rtp_and_rtcp.pcap"
const val numExpectedPackets = 24501
val srtpProfileInformation = SrtpProfileInformation(
    cipherKeyLength=16,
    cipherSaltLength=14,
    cipherName=1,
    authFunctionName=1,
    authKeyLength=20,
    rtcpAuthTagLength=10,
    rtpAuthTagLength=10
)
val keyingMaterial = byteArrayOf(
    0x2D.toByte(), 0x6C.toByte(), 0x37.toByte(), 0xC7.toByte(),
    0xA3.toByte(), 0x49.toByte(), 0x25.toByte(), 0x82.toByte(),
    0x1F.toByte(), 0x3B.toByte(), 0x62.toByte(), 0x0D.toByte(),
    0x05.toByte(), 0x8A.toByte(), 0x29.toByte(), 0x64.toByte(),
    0x6F.toByte(), 0x49.toByte(), 0xD6.toByte(), 0x04.toByte(),
    0xE6.toByte(), 0xD6.toByte(), 0x48.toByte(), 0xE0.toByte(),
    0x67.toByte(), 0x43.toByte(), 0xF3.toByte(), 0x1F.toByte(),
    0x6D.toByte(), 0x2F.toByte(), 0x4B.toByte(), 0x33.toByte(),
    0x6A.toByte(), 0x61.toByte(), 0xD8.toByte(), 0x84.toByte(),
    0x00.toByte(), 0x32.toByte(), 0x1A.toByte(), 0x84.toByte(),
    0x00.toByte(), 0x8C.toByte(), 0xC5.toByte(), 0xC3.toByte(),
    0xCB.toByte(), 0x18.toByte(), 0xCE.toByte(), 0x8D.toByte(),
    0x34.toByte(), 0x3C.toByte(), 0x2C.toByte(), 0x70.toByte(),
    0x62.toByte(), 0x26.toByte(), 0x39.toByte(), 0x05.toByte(),
    0x7D.toByte(), 0x5A.toByte(), 0xF9.toByte(), 0xC7.toByte()
)
val tlsRole = TlsRole.CLIENT

private val rtcpEventNotifier = RtcpEventNotifier()

fun createRtpReceiver(executor: ExecutorService, backgroundExecutor: ScheduledExecutorService): RtpReceiver {
    val rtpReceiver = RtpReceiverImpl(
        "1",
        { rtcpPacket -> Unit },
        rtcpEventNotifier = rtcpEventNotifier,
        executor = executor,
        backgroundExecutor = backgroundExecutor
    )

    val srtpTransformer = SrtpUtil.initializeTransformer(
            srtpProfileInformation,
            keyingMaterial,
            tlsRole,
            false
    )
    val srtcpTransformer = SrtpUtil.initializeTransformer(
            srtpProfileInformation,
            keyingMaterial,
            tlsRole,
            true
    )

    rtpReceiver.setSrtpTransformer(srtpTransformer)
    rtpReceiver.setSrtcpTransformer(srtcpTransformer)

    rtpReceiver.handleEvent(RtpPayloadTypeAddedEvent(Vp8PayloadType(100)))
    rtpReceiver.handleEvent(RtpPayloadTypeAddedEvent(OpusPayloadType(111)))
    rtpReceiver.handleEvent(RtpExtensionAddedEvent(5, RTPExtension(URI(RTPExtension.TRANSPORT_CC_URN))))

    return rtpReceiver
}

fun createRtpSender(executor: ExecutorService, backgroundExecutor: ScheduledExecutorService): RtpSender {
    val sender = RtpSenderImpl(
            "456",
            executor = executor,
            backgroundExecutor = backgroundExecutor,
            rtcpEventNotifier = rtcpEventNotifier)
    val srtpTransformer = SrtpUtil.initializeTransformer(
            srtpProfileInformation,
            keyingMaterial,
            tlsRole,
            false
    )
    val srtcpTransformer = SrtpUtil.initializeTransformer(
            srtpProfileInformation,
            keyingMaterial,
            tlsRole,
            true
    )
    sender.setSrtpTransformer(srtpTransformer)
    sender.setSrtcpTransformer(srtcpTransformer)

    sender.handleEvent(RtpExtensionAddedEvent(11, RTPExtension(URI(RTPExtension.ABS_SEND_TIME_URN))))
    sender.handleEvent(RtpExtensionAddedEvent(5, RTPExtension(URI(RTPExtension.TRANSPORT_CC_URN))))
    return sender
}

fun main(args: Array<String>) {
    val receiverExecutor = Executors.newSingleThreadExecutor(NameableThreadFactory("Receiver executor"))
    val senderExecutor = Executors.newSingleThreadExecutor(NameableThreadFactory("Sender executor"))
    val backgroundExecutor = Executors.newSingleThreadScheduledExecutor(NameableThreadFactory("Background executor"))
    val numReceivers = 1
    val receivers = mutableListOf<RtpReceiver>()
    val receiverDoneFutures = mutableListOf<CompletableFuture<Unit>>()
    val sender = createRtpSender(senderExecutor, backgroundExecutor)

    for (i in 1..numReceivers) {
        val rtpReceiver = createRtpReceiver(receiverExecutor, backgroundExecutor)
        var numReceivedPackets = 0
        val doneFuture = CompletableFuture<Unit>()
        rtpReceiver.rtpPacketHandler = (object : Node("Packet receiver") {
            override fun doProcessPackets(p: List<PacketInfo>) {
                numReceivedPackets += p.size
                if (numReceivedPackets == numExpectedPackets) {
                    println("ALL PACKETS FORWARDED")
                    doneFuture.complete(Unit)
                }
                val clonedPackets = p.map { it.packet.clone()}
                        .map { PacketInfo(it) }
                        .toList()
//                p.forEach {
//                    it.packet = it.packet.clone()
//                }
                sender.sendPackets(clonedPackets)
            }
        })
        receivers.add(rtpReceiver)
        receiverDoneFutures.add(doneFuture)
    }


    val pcap = Pcap.openStream(pcapFile)
    val startTime = System.nanoTime()
    val buf2 = ByteBuffer.allocate(1500)
    var numPackets = 0
    pcap.loop { pkt ->
        numPackets++
        val buf: ByteBuffer = if (pkt.hasProtocol(Protocol.UDP)) {
            val udpPacket = pkt.getPacket(Protocol.UDP) as UDPPacket
            ByteBuffer.wrap(udpPacket.payload.array)
        } else {
            // When capturing on the loopback interface, the packets have a null ethernet
            // frame which messes up the pkts libary's parsing, so instead use a hack to
            // grab the buffer directly
            ByteBuffer.wrap(pkt.payload.array, 32, (pkt.payload.array.size - 32)).slice()
        }
//        if (numPackets % 100 == 0) {
//            println("BRIAN: dropping packet ${RtpPacket(buf).header.sequenceNumber}")
//            return@loop true
//        }
//        println("got rtp buf:\n${buf.toHex()}")
//        println("Sending packet #$numPackets to receivers")
        for (receiver in receivers) {
            val p = UnparsedPacket(buf.clone())
            receiver.enqueuePacket(PacketInfo(p))
        }
        true
    }
    println("loop done")
//    receiverDoneFutures.map { it.join() }
    sleep(3000)
    val finishTime = System.nanoTime()
    val time = Duration.ofNanos(finishTime - startTime)
    receivers.forEach { println(it.getNodeStats()) }

    println("$numReceivers receiver pipelines processed $numExpectedPackets packets each in a total of ${time.toMillis()}ms")

    receivers.forEach(RtpReceiver::stop)

    sender.stop()
    println("sender stats:\n${sender.getNodeStats()}")


    receiverExecutor.safeShutdown(Duration.ofSeconds(5))
    senderExecutor.safeShutdown(Duration.ofSeconds(5))
    backgroundExecutor.safeShutdown(Duration.ofSeconds(5))

}
