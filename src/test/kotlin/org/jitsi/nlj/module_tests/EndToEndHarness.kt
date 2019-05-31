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
import org.jitsi.nlj.util.BufferPool
import org.jitsi.nlj.util.safeShutdown
import org.jitsi.service.libjitsi.LibJitsi
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
    // We need to start libjitsi so that the openssl lib gets loaded.
    LibJitsi.start()

    val pcap = Pcaps.Incoming.ONE_PARTICIPANT_RTP_RTCP_SIM_RTX

    var numBuffersRequested = 0
    fun getBuffer(size: Int): ByteArray {
        numBuffersRequested++
        return ByteArray(size)
    }
    var numBuffersReturned = 0
    fun returnBuffer(buf: ByteArray) {
        numBuffersReturned++
    }
    BufferPool.getBuffer = ::getBuffer
    BufferPool.returnBuffer = ::returnBuffer
    org.jitsi.rtp.util.BufferPool.getArray = ::getBuffer
    org.jitsi.rtp.util.BufferPool.returnArray = ::returnBuffer

    val producer = PcapPacketProducer(pcap.filePath)

    val backgroundExecutor = Executors.newSingleThreadScheduledExecutor()
    val executor = Executors.newSingleThreadExecutor()

    val sender = SenderFactory.createSender(
        executor, backgroundExecutor, pcap.srtpData,
        pcap.payloadTypes, pcap.headerExtensions, pcap.ssrcAssociations
    )

    val receiver = ReceiverFactory.createReceiver(
        executor, backgroundExecutor, pcap.srtpData,
        pcap.payloadTypes, pcap.headerExtensions, pcap.ssrcAssociations,
        { rtcpPacket -> sender.processPacket(PacketInfo(rtcpPacket)) })

    producer.subscribe { pkt ->
        val packetInfo = PacketInfo(pkt)
        packetInfo.receivedTime = System.currentTimeMillis()
        receiver.enqueuePacket(packetInfo)
    }

    receiver.packetHandler = object : PacketHandler {
        override fun processPacket(packetInfo: PacketInfo) {
            sender.processPacket(packetInfo)
        }
    }

    sender.onOutgoingPacket(object : PacketHandler {
        override fun processPacket(packetInfo: PacketInfo) {
            BufferPool.returnBuffer(packetInfo.packet.getBuffer())
        }
    })

    producer.run()

    receiver.stop()
    sender.stop()
    executor.safeShutdown(Duration.ofSeconds(10))
    backgroundExecutor.safeShutdown(Duration.ofSeconds(10))

    println(receiver.getNodeStats().prettyPrint())
    println(sender.getNodeStats().prettyPrint())

    println("gave out $numBuffersRequested buffers, returned $numBuffersReturned")
}
