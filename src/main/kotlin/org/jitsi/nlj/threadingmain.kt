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

import org.jitsi.nlj.transform2.RtpReceiver
import org.jitsi.nlj.transform2.OutgoingMediaStreamTrack2
import org.jitsi.nlj.transform2.forEachAs
import org.jitsi.rtp.Packet
import org.jitsi.rtp.RtpPacket
import java.lang.Thread.sleep
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

class Bridge(val executor: ExecutorService) {
    val senders = mutableMapOf<Long, RtpSender>()
    val incomingPacketQueue = LinkedBlockingQueue<Packet>()
    var numIncomingPackets = AtomicInteger(0)
    var numReadPackets = 0
    var numForwardedPackets = 0
    var processedPacketsPerSsrc = mutableMapOf<Long, Int>()
    var packetsPerDestination = mutableMapOf<Long, Int>()

    init {
        scheduleWork()
    }

    fun addSender(ssrc: Long, sender: RtpSender) {
        senders.put(ssrc, sender)
    }

    fun onIncomingPackets(pkts: List<Packet>) {
        if (!incomingPacketQueue.addAll(pkts)) {
            println("Bridge: error adding packets to queue")
        }
        numIncomingPackets.addAndGet(pkts.size)
//        println("bridge got packet")
    }

    private fun scheduleWork() {
        executor.execute {
            val packetsToProcess = mutableListOf<Packet>()
            while (packetsToProcess.size < 5) {
                val packet = incomingPacketQueue.poll() ?: break
                packetsToProcess += packet
                numReadPackets++
            }
            packetsToProcess.forEachAs<RtpPacket> { pkt ->
                val packetSsrc = pkt.header.ssrc
                processedPacketsPerSsrc[packetSsrc] = processedPacketsPerSsrc.getOrDefault(packetSsrc, 0) + 1
                senders.forEach { senderSsrc, sender ->
                    if (senderSsrc != packetSsrc) {
//                        println("packet ssrc: $packetSsrc, current sender ssrc: $senderSsrc")
                        sender.sendPackets(listOf(pkt))
                        packetsPerDestination[senderSsrc] = packetsPerDestination.getOrDefault(senderSsrc, 0) + 1
                        numForwardedPackets++
                    }
                }
            }
            scheduleWork()
        }
    }
}

fun main(args: Array<String>) {
    val trackExecutor = Executors.newFixedThreadPool(1)
    val p = PacketProducer()
    val b = Bridge(trackExecutor)


    p.addSource(123)
    val receiver1 = RtpReceiver(123, trackExecutor, b::onIncomingPackets)
    val sender1 = OutgoingMediaStreamTrack2(123, trackExecutor)
    b.addSender(123, sender1)
    receiver1.start()
    p.addDestination({ pkt -> pkt.isRtp && (pkt as RtpPacket).header.ssrc == 123L }, receiver1::enqueuePacket)

    p.addSource(456)
    val receiver2 = RtpReceiver(456, trackExecutor, b::onIncomingPackets)
    val sender2 = OutgoingMediaStreamTrack2(456, trackExecutor)
    b.addSender(456, sender2)
    receiver2.start()
    p.addDestination({ pkt -> pkt.isRtp && (pkt as RtpPacket).header.ssrc == 456L }, receiver2::enqueuePacket)

//    p.addSource(789)
//    val stream3 = RtpReceiver(789, trackExecutor)
//    stream3.start()
//    p.addDestination({ pkt -> pkt.isRtp && (pkt as RtpPacket).header.ssrc == 789L }, stream3::enqueuePacket)


    val startTime = System.currentTimeMillis()
    p.run(5_000_000)

    CompletableFuture.allOf(sender1.done, sender2.done).thenAccept {
        val endTime = System.currentTimeMillis()
        println("Senders are all done.  Took ${endTime - startTime}ms")

        sender1.running = false
        sender2.running = false

        println("Producer wrote ${p.packetsWritten} packets")
        println(receiver1.getStats())
        println(receiver2.getStats())
//    println(stream3.getStats())

        println("=======")
        println("Bridge:")
        println("  received ${b.numIncomingPackets} packets")
        println("  read ${b.numIncomingPackets} packets from queue")
        println("  forwarded ${b.numForwardedPackets} packets")
        println("      per packet ssrc: ${b.processedPacketsPerSsrc}")
        println("      per destination ssrc: ${b.packetsPerDestination}")
        println(sender1.getStats())
        println(sender2.getStats())

        val totalPacketsSent = listOf(sender1, sender2).map(RtpSender::numPacketsSent).sum()
        println("Transmitted $totalPacketsSent packets")

        exitProcess(0)
    }


}
