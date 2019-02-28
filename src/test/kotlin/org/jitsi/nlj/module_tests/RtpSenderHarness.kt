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

import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.RtpExtensionAddedEvent
import org.jitsi.nlj.RtpPayloadTypeAddedEvent
import org.jitsi.nlj.RtpSender
import org.jitsi.nlj.RtpSenderImpl
import org.jitsi.nlj.SsrcAssociationEvent
import org.jitsi.nlj.rtcp.RtcpEventNotifier
import org.jitsi.nlj.util.safeShutdown
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.rtp.util.RtpProtocol
import org.jitsi.test_utils.Pcaps
import org.jitsi.test_utils.SrtpData
import java.time.Duration
import java.util.Random
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import kotlin.system.measureTimeMillis

private fun createSender(executor: ExecutorService, backgroundExecutor: ScheduledExecutorService, srtpData: SrtpData): RtpSender {
    val sender = RtpSenderImpl(
        Random().nextLong().toString(),
        null,
        RtcpEventNotifier(),
        executor,
        backgroundExecutor
    )
    sender.setSrtpTransformer(SrtpTransformerFactory.createSrtpTransformer(srtpData))
    sender.setSrtcpTransformer(SrtpTransformerFactory.createSrtcpTransformer(srtpData))

    return sender
}

fun main(args: Array<String>) {
    val pcap = Pcaps.Outgoing.ONE_PARITICPANT_RTP_AND_RTCP_DECRYPTED

    val producer = PcapPacketProducer(pcap.filePath)

    val senderExecutor = Executors.newSingleThreadExecutor()
    val backgroundExecutor = Executors.newSingleThreadScheduledExecutor()
    val numSenders = 1
    val senders = mutableListOf<RtpSender>()
    repeat(numSenders) {
        val sender = createSender(senderExecutor, backgroundExecutor, pcap.srtpData)
        pcap.payloadTypes.forEach {
            sender.handleEvent(RtpPayloadTypeAddedEvent(it))
        }
        pcap.headerExtensions.forEach {
            sender.handleEvent(RtpExtensionAddedEvent(it.id.toByte(), it.extension))
        }
        pcap.ssrcAssociations.forEach {
            sender.handleEvent(SsrcAssociationEvent(it.primarySsrc, it.secondarySsrc, it.associationType))
        }
        senders.add(sender)
    }

    producer.subscribe { pkt ->
        senders.forEach {
            if (RtpProtocol.isRtp(pkt.getBuffer())) {
                val rtpPacket = RtpPacket.fromBuffer(pkt.getBuffer())
                it.sendPackets(listOf(PacketInfo(rtpPacket.clone())))
            } else {
                it.sendRtcp(listOf(RtcpPacket.parse(pkt.clone().getBuffer())))
            }
        }
    }

    val time = measureTimeMillis {
        producer.run()
    }
    println("took $time ms")
    senders.forEach(RtpSender::stop)
    senderExecutor.safeShutdown(Duration.ofSeconds(10))
    backgroundExecutor.safeShutdown(Duration.ofSeconds(10))

    senders.forEach { println(it.getNodeStats().prettyPrint()) }
}
