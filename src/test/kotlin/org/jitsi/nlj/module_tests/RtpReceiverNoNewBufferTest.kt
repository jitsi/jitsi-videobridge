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
import org.jitsi.test_utils.Pcaps
import org.jitsi.utils.secs
import java.time.Clock
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue

/**
 * Verify that the same buffer we feed into the receive pipeline is the one we get
 * out (in other words: we don't re-allocate a new buffer).  Verifying this means
 * we can easily pull the original buffer from a pool and know that we can return
 * the array coming out the other side.
 * NOTE: we don't technically care about the ByteBuffer instance itself, but
 * the backing byte array.
 */
// TODO: turn this into a real test (as well as the rest) and tag them as 'slow'
fun main() {
    val pcap = Pcaps.Incoming.ONE_PARTICIPANT_RTP_RTCP_SIM_RTX

    val producer = PcapPacketProducer(pcap.filePath)

    val backgroundExecutor = Executors.newSingleThreadScheduledExecutor()
    val executor = Executors.newSingleThreadExecutor()

    val receiver = ReceiverFactory.createReceiver(
        executor, backgroundExecutor, pcap.srtpData,
        pcap.payloadTypes, pcap.headerExtensions, pcap.ssrcAssociations
    )

    val sentArrays = LinkedBlockingQueue<Int>()

    /**
     * Verify that the arrayId (acquired via calling System.identifyHashCode
     * on the array) matches one that we went out.
     */
    var numNewArrays = 0
    fun verifyArray(arrayId: Int) {
        if (!sentArrays.any { it == arrayId }) {
            println("Array $arrayId was not among the sent out arrays!")
            numNewArrays++
        }
    }

    receiver.packetHandler = object : PacketHandler {
        override fun processPacket(packetInfo: PacketInfo) {
            verifyArray(System.identityHashCode(packetInfo.packet.buffer))
        }
    }

    producer.subscribe { pkt ->
        sentArrays.offer(System.identityHashCode(pkt.buffer))
        val packetInfo = PacketInfo(pkt)
        packetInfo.receivedTime = Clock.systemUTC().instant()
        receiver.enqueuePacket(packetInfo)
    }

    producer.run()

    receiver.stop()
    executor.safeShutdown(10.secs)
    backgroundExecutor.safeShutdown(10.secs)

    println("Num new arrays: $numNewArrays")
}
