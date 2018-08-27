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

import io.pkts.Pcap
import io.pkts.packet.UDPPacket
import io.pkts.protocol.Protocol
import org.jitsi.nlj.srtp.SrtpProfileInformation
import org.jitsi.nlj.srtp.SrtpUtil
import org.jitsi.nlj.srtp.TlsRole
import org.jitsi.rtp.UnparsedPacket
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

fun ByteBuffer.clone(): ByteBuffer {
    val clone = ByteBuffer.allocate(capacity())
    rewind()//copy from the beginning
    clone.put(this)
    rewind()
    clone.flip()
    return clone
}

// All of this information is specific to the pcap file
val pcapFile = "/Users/bbaldino/new_pipeline_captures/capture_1_incoming_participant_1.pcap"
val numExpectedPackets = 24501
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

fun createRtpReceiver(executor: ExecutorService): RtpReceiver {
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
    val rtpReceiver = RtpReceiverImpl(
        1,
        { rtcpPacket -> Unit },
        executor
    )
    rtpReceiver.setSrtpTransformer(srtpTransformer)
    rtpReceiver.setSrtcpTransformer(srtcpTransformer)

    return rtpReceiver
}

fun main(args: Array<String>) {
    val executor = Executors.newSingleThreadExecutor()
    val numReceivers = 32
    val receivers = mutableListOf<RtpReceiver>()
    val receiverDoneFutures = mutableListOf<CompletableFuture<Unit>>()

    for (i in 1..numReceivers) {
        val rtpReceiver = createRtpReceiver(executor)
        var numReceivedPackets = 0
        val doneFuture = CompletableFuture<Unit>()
        rtpReceiver.rtpPacketHandler = { pkts ->
            numReceivedPackets += pkts.size
            if (numReceivedPackets == numExpectedPackets) {
                println("ALL PACKETS FORWARDED")
                doneFuture.complete(Unit)
            }
        }
        receivers.add(rtpReceiver)
        receiverDoneFutures.add(doneFuture)
    }


    val pcap = Pcap.openStream(pcapFile)
    val startTime = System.nanoTime()
    val buf2 = ByteBuffer.allocate(1500)
    pcap.loop { pkt ->
        val buf: ByteBuffer = if (pkt.hasProtocol(Protocol.UDP)) {
            val udpPacket = pkt.getPacket(Protocol.UDP) as UDPPacket
            ByteBuffer.wrap(udpPacket.payload.array)
        } else {
            // When capturing on the loopback interface, the packets have a null ethernet
            // frame which messes up the pkts libary's parsing, so instead use a hack to
            // grab the buffer directly
            ByteBuffer.wrap(pkt.payload.array, 32, (pkt.payload.array.size - 32)).slice()
        }
//        println("got rtp buf:\n${buf.toHex()}")
        for (receiver in receivers) {
            val p = UnparsedPacket(buf.clone())
            receiver.enqueuePacket(p)

        }
        true
    }
    println("loop done")
    receiverDoneFutures.map { it.join() }
    val finishTime = System.nanoTime()
    val time = Duration.ofNanos(finishTime - startTime)
    receivers.forEach { println(it.getStats()) }

    println("$numReceivers receiver pipelines processed $numExpectedPackets packets each in a total of ${time.toMillis()}ms")
}
