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
import org.jitsi.impl.neomedia.transform.srtp.SRTPTransformer
import org.jitsi.nlj.srtp.SrtpProfileInformation
import org.jitsi.nlj.srtp.SrtpUtil
import org.jitsi.nlj.srtp.TlsRole
import org.jitsi.service.neomedia.RTPExtension
import org.jitsi_modified.service.neomedia.format.DummyAudioMediaFormat
import org.jitsi_modified.service.neomedia.format.Vp8MediaFormat
import java.net.URI
import java.util.Random
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

val srtpInformation = SrtpInformation(
    srtpProfileInformation = SrtpProfileInformation(
        cipherKeyLength=16,
        cipherSaltLength=14,
        cipherName=1,
        authFunctionName=1,
        authKeyLength=20,
        rtcpAuthTagLength=10,
        rtpAuthTagLength=10
    ),
    keyingMaterial = byteArrayOf(
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
    ),
    tlsRole =  TlsRole.CLIENT
)

fun createSrtpTransformer(): SinglePacketTransformer {
    return SrtpUtil.initializeTransformer(
        srtpProfileInformation,
        keyingMaterial,
        tlsRole,
        false
    )
}

fun createSrtcpTransformer(): SinglePacketTransformer {
    return SrtpUtil.initializeTransformer(
        srtpProfileInformation,
        keyingMaterial,
        tlsRole,
        true
    )
}

fun createReceiver(executor: ScheduledExecutorService): RtpReceiver {
    val receiver = RtpReceiverImpl(
        Random().nextLong(),
        {},
        null,
        executor
    )
    receiver.setSrtpTransformer(createSrtpTransformer())
    receiver.setSrtcpTransformer(createSrtcpTransformer())

    return receiver
}

fun main(args: Array<String>) {
    val pcapFile = "/Users/bbaldino/new_pipeline_captures/capture_1_incoming_participant_1_rtp_and_rtcp.pcap"

    val producer = PcapPacketProducer(PcapFileInformation(pcapFile, srtpInformation))

    val receiverExecutor = Executors.newSingleThreadScheduledExecutor()
    val numReceivers = 150
    val receivers = mutableListOf<RtpReceiver>()
    repeat(numReceivers) {
        val receiver = createReceiver(receiverExecutor)
        receiver.handleEvent(RtpPayloadTypeAddedEvent(100, Vp8MediaFormat()))
        receiver.handleEvent(RtpPayloadTypeAddedEvent(111,
            DummyAudioMediaFormat()
        ))
        receiver.handleEvent(RtpExtensionAddedEvent(5, RTPExtension(URI(RTPExtension.TRANSPORT_CC_URN))))
        receivers.add(receiver)
    }

    producer.subscribe { pkt ->
        receivers.forEach { it.enqueuePacket(PacketInfo(pkt.clone())) }
    }

    val time = measureTimeMillis {
        producer.run()
    }
    println("took $time ms")
    receivers.forEach(RtpReceiver::stop)
    receiverExecutor.shutdown()
    receiverExecutor.awaitTermination(10, TimeUnit.SECONDS)

    receivers.forEach { println(it.getStats()) }
}
