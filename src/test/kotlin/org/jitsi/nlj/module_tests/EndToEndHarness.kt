/*
 * Copyright @ 2018 - present 8x8, Inc.
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

import org.jitsi.nlj.PacketHandler
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.util.safeShutdown
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.test_utils.Pcaps
import java.time.Duration
import java.util.concurrent.Executors

/**
 * Read packets from a PCAP file and feed them through a receiver
 * and then to a sender to simulate an end-to-end run of packets.
 *
 * NOTE that in some places this file tries to simulate behavior
 * of the bridge (for example cloning the packet before sending
 * it to the sender) but it does not perfectly simulate the bridge
 * (e.g. it does not do the simulcast filter/rewriting, send
 * probing, or other behaviors)
 */


fun main() {
    val pcap = Pcaps.Incoming.ONE_PARTICIPANT_RTP_RTCP_SIM_RTX
    val producer = PcapPacketProducer(pcap.filePath)

    val backgroundExecutor = Executors.newSingleThreadScheduledExecutor()
    val executor = Executors.newSingleThreadExecutor()

    val receiver = ReceiverFactory.createReceiver(
        executor, backgroundExecutor, pcap.srtpData,
        pcap.payloadTypes, pcap.headerExtensions, pcap.ssrcAssociations)

    producer.subscribe { pkt ->
        receiver.enqueuePacket(PacketInfo(pkt))
    }

    val sender = SenderFactory.createSender(
        executor, backgroundExecutor, pcap.srtpData,
        pcap.payloadTypes, pcap.headerExtensions, pcap.ssrcAssociations
    )

    receiver.rtpPacketHandler = object : PacketHandler {
        override fun processPackets(pkts: List<PacketInfo>) {
            pkts.forEach { pkt ->
                val buf = pkt.packet.getBuffer()
                println("forwarding buffer ${System.identityHashCode(buf)} $buf to sender")
            }
            sender.sendPackets(pkts)
        }
    }

    receiver.rtcpPacketHandler = object : PacketHandler {
        override fun processPackets(pkts: List<PacketInfo>) {
            sender.sendRtcp(pkts.map(PacketInfo::packet).map { it as RtcpPacket }.toList())
        }
    }

    producer.run()

    receiver.stop()
    sender.stop()
    executor.safeShutdown(Duration.ofSeconds(10))
    backgroundExecutor.safeShutdown(Duration.ofSeconds(10))

    println(receiver.getNodeStats().prettyPrint())
    println(sender.getNodeStats().prettyPrint())
}