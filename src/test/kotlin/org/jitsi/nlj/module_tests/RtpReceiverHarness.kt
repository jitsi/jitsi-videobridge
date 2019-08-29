/*
 * Copyright @ 2018 - Present, 8x8 Inc
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
import org.jitsi.nlj.RtpReceiver
import org.jitsi.nlj.util.safeShutdown
import org.jitsi.test_utils.Pcaps
import java.time.Duration
import java.util.concurrent.Executors
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

fun main(args: Array<String>) {
    val pcap = Pcaps.Incoming.ONE_PARTICIPANT_RTP_RTCP_SIM_RTX

    val producer = PcapPacketProducer(pcap.filePath)

    val backgroundExecutor = Executors.newSingleThreadScheduledExecutor()
    val executor = Executors.newSingleThreadExecutor()
    val numReceivers = 1
    val receivers = mutableListOf<RtpReceiver>()
    repeat(numReceivers) {
        val receiver = ReceiverFactory.createReceiver(
            executor, backgroundExecutor, pcap.srtpData,
            pcap.payloadTypes, pcap.headerExtensions, pcap.ssrcAssociations)

        receivers.add(receiver)
    }

    producer.subscribe { pkt ->
        val packetInfo = PacketInfo(pkt)
        packetInfo.receivedTime = System.currentTimeMillis()
        receivers.forEach { it.enqueuePacket(packetInfo.clone()) }
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
