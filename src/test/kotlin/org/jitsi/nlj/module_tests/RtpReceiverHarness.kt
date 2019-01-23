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

import org.jitsi.impl.neomedia.transform.SinglePacketTransformer
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.RtpExtensionAddedEvent
import org.jitsi.nlj.RtpPayloadTypeAddedEvent
import org.jitsi.nlj.RtpReceiver
import org.jitsi.nlj.RtpReceiverImpl
import org.jitsi.nlj.format.PayloadType
import org.jitsi.nlj.rtcp.RtcpEventNotifier
import org.jitsi.nlj.srtp.SrtpProfileInformation
import org.jitsi.nlj.srtp.SrtpUtil
import org.jitsi.nlj.srtp.TlsRole
import org.jitsi.nlj.util.safeShutdown
import org.jitsi.service.neomedia.RTPExtension
import java.net.URI
import java.time.Duration
import java.util.Random
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import kotlin.system.measureTimeMillis

/**
 * Feed media data from a PCAP file through N receivers.  This harness
 * allows:
 * 1) Changing the PCAP file to feed in different data
 * 2) Changing the number of RTP Receivers we feed the incoming data to
 * 3) Changing the number of threads used for all of the receivers
 *
 * This can give a sense of how many threads would be necessary to run
 * N receivers given a certain input bit rate (determined by the PCAP file)
 */

//TODO: the below information is hard-coded to the hard-coded pcap we're using.  put this in a file associated
// with the pcap file so it can be loaded programmatically
private val srtpInformation = SrtpInformation(
    srtpProfileInformation = SrtpProfileInformation(
        cipherKeyLength = 16,
        cipherSaltLength = 14,
        cipherName = 1,
        authFunctionName = 1,
        authKeyLength = 20,
        rtcpAuthTagLength = 10,
        rtpAuthTagLength = 10
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
    tlsRole = TlsRole.CLIENT
)

private fun createSrtpTransformer(): SinglePacketTransformer {
    return SrtpUtil.initializeTransformer(
        srtpProfileInformation,
        keyingMaterial,
        tlsRole,
        false
    )
}

private fun createSrtcpTransformer(): SinglePacketTransformer {
    return SrtpUtil.initializeTransformer(
        srtpProfileInformation,
        keyingMaterial,
        tlsRole,
        true
    )
}

fun createReceiver(executor: ExecutorService, backgroundExecutor: ScheduledExecutorService): RtpReceiver {
    val receiver = RtpReceiverImpl(
        Random().nextLong().toString(),
        {},
        null,
        RtcpEventNotifier(),
        executor,
        backgroundExecutor
    )
    receiver.setSrtpTransformer(createSrtpTransformer())
    receiver.setSrtcpTransformer(createSrtcpTransformer())

    return receiver
}

fun main(args: Array<String>) {
    val pcapFile = "/Users/bbaldino/new_pipeline_captures/capture_1_incoming_participant_1_rtp_and_rtcp.pcap"

    Thread.sleep(10000)

    val producer = PcapPacketProducer(
        PcapFileInformation(
            pcapFile,
            srtpInformation
        )
    )

    val backgroundExecutor = Executors.newSingleThreadScheduledExecutor()
    val executor = Executors.newSingleThreadExecutor()
    val numReceivers = 1
    val receivers = mutableListOf<RtpReceiver>()
    repeat(numReceivers) {
        val receiver = createReceiver(executor, backgroundExecutor)
        receiver.handleEvent(RtpPayloadTypeAddedEvent(PayloadType.vp8(100)))
        receiver.handleEvent(RtpPayloadTypeAddedEvent(PayloadType.dummyAudio(111)))
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
    executor.safeShutdown(Duration.ofSeconds(10))
    backgroundExecutor.safeShutdown(Duration.ofSeconds(10))

    receivers.forEach { println(it.getNodeStats().prettyPrint()) }
}
