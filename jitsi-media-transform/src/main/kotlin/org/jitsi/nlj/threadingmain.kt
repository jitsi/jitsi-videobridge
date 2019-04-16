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

// /*
// * Copyright @ 2018 Atlassian Pty Ltd
// *
// * Licensed under the Apache License, Version 2.0 (the "License");
// * you may not use this file except in compliance with the License.
// * You may obtain a copy of the License at
// *
// *     http://www.apache.org/licenses/LICENSE-2.0
// *
// * Unless required by applicable law or agreed to in writing, software
// * distributed under the License is distributed on an "AS IS" BASIS,
// * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// * See the License for the specific language governing permissions and
// * limitations under the License.
// */
// package org.jitsi.nlj
//
// import org.jitsi.nlj.transform.module.forEachAs
// import org.jitsi.rtp.Packet
// import org.jitsi.rtp.RtpPacket
// import java.util.concurrent.ExecutorService
// import java.util.concurrent.Executors
// import java.util.concurrent.LinkedBlockingQueue
// import java.util.concurrent.atomic.AtomicInteger
// import java.util.concurrent.atomic.AtomicLong
// import kotlin.system.measureNanoTime
//
// class Bridge(val executor: ExecutorService) {
//    val senders = mutableMapOf<Long, RtpSender>()
//    val incomingPacketQueue = LinkedBlockingQueue<Packet>()
//    var numReadPackets = 0
//    var numForwardedPackets = 0
//    var processedPacketsPerSsrc = mutableMapOf<Long, Int>()
//    var packetsPerDestination = mutableMapOf<Long, Int>()
//    var firstPacketReadTime: Long = -1
//    var lastPacketReadTime: Long = -1
//    val execTimes = mutableListOf<Long>()
//    var numReadBytes: Long = 0
//    var numTimesQueueEmpty = 0
//    var processingTime: Long = 0
//
//    var numIncomingPackets = AtomicInteger(0)
//    var numIncomingBytes = AtomicLong(0)
//    var firstPacketWrittenTime = AtomicLong(0)
//    var lasttPacketWrittenTime = AtomicLong(0)
//
//    var running = true
//
//    init {
// //        scheduleWork()
// //        scheduleWorkDedicated()
//    }
//
//    fun addSender(ssrc: Long, sender: RtpSender) {
//        senders.put(ssrc, sender)
//    }
//
//    fun onIncomingPackets(pkts: List<Packet>) {
//        if (firstPacketWrittenTime.get() == 0L) {
//            firstPacketWrittenTime.set(System.currentTimeMillis())
//        }
//        lasttPacketWrittenTime.set(System.currentTimeMillis())
//        if (!incomingPacketQueue.addAll(pkts)) {
//            println("Bridge: error adding packets to queue")
//        }
//        pkts.forEach { numIncomingBytes.addAndGet(it.size.toLong()) }
//        numIncomingPackets.addAndGet(pkts.size)
//    }
//
//    private fun doWork() {
//        val packetsToProcess = mutableListOf<Packet>()
//        val time = measureNanoTime {
//            incomingPacketQueue.drainTo(packetsToProcess, 20)
//            if (firstPacketReadTime == -1L) {
//                firstPacketReadTime = System.currentTimeMillis()
//            }
//            if (packetsToProcess.isEmpty()) {
//                numTimesQueueEmpty++
//                return
//            }
//            lastPacketReadTime = System.currentTimeMillis()
//            numReadPackets += packetsToProcess.size
//            packetsToProcess.forEach { numReadBytes += it.size }
//            packetsToProcess.forEachAs<RtpPacket> { pkt ->
//                val packetSsrc = pkt.header.ssrc
//                processedPacketsPerSsrc[packetSsrc] = processedPacketsPerSsrc.getOrDefault(packetSsrc, 0) + 1
//                senders.forEach { senderSsrc, sender ->
//                    if (senderSsrc != packetSsrc) {
// //                        println("packet ssrc: $packetSsrc, current sender ssrc: $senderSsrc")
//                        sender.sendPackets(listOf(pkt))
//                        packetsPerDestination[senderSsrc] = packetsPerDestination.getOrDefault(senderSsrc, 0) + 1
//                        numForwardedPackets++
//                    }
//                }
//            }
//        }
//        processingTime += time
//    }
//
//    fun scheduleWork() {
//        executor.execute {
//            val time = measureNanoTime {
//                doWork()
//            }
//            execTimes.add(time)
//            execTimes.dropLastWhile { execTimes.size > 100 }
//            if (running) {
//                scheduleWork()
//            }
//        }
//    }
//
//    fun scheduleWorkDedicated() {
//        executor.execute {
//            while (running) {
//                val time = measureNanoTime {
//                    doWork()
//                }
//                execTimes.add(time)
//                execTimes.dropLastWhile { execTimes.size > 100 }
//            }
//        }
//    }
// }
//
//
// fun main(args: Array<String>) {
//    val trackExecutor = Executors.newFixedThreadPool(4)
//    val p = PacketProducer()
//    val b = Bridge(trackExecutor)
//
//
//    p.addSource(123)
// //    val receiver1 = RtpReceiverImpl(123, trackExecutor, b::onIncomingPackets)
//    val sender1 = RtpSenderImpl(123, trackExecutor)
//    b.addSender(123, sender1)
// //    p.addDestination({ pkt -> RtpProtocol.isRtp(pkt.buf) && (pkt as RtpPacket).header.ssrc == 123L }, receiver1::enqueuePacket)
//
//    p.addSource(456)
// //    val receiver2 = RtpReceiverImpl(456, trackExecutor, b::onIncomingPackets)
//    val sender2 = RtpSenderImpl(456, trackExecutor)
//    b.addSender(456, sender2)
// //    p.addDestination({ pkt -> RtpProtocol.isRtp(pkt.buf) && (pkt as RtpPacket).header.ssrc == 456L }, receiver2::enqueuePacket)
//
// //    p.addSource(789)
// //    val stream3 = RtpReceiverImpl(789, trackExecutor)
// //    stream3.start()
// //    p.addDestination({ pkt -> pkt.isRtp && (pkt as RtpPacket).header.ssrc == 789L }, stream3::enqueuePacket)
//
//
//    val startTime = System.currentTimeMillis()
//    p.run(5_000_000)
//
// //    CompletableFuture.allOf(sender1.done, sender2.done).thenAccept {
// //        val endTime = System.currentTimeMillis()
// //        println("Senders are all done.  Took ${endTime - startTime}ms")
// //
// //        sender1.running = false
// //        receiver1.running = false
// //        sender2.running = false
// //        receiver2.running = false
// //
// //        println("Producer wrote ${p.packetsWritten} packets")
// //        println(receiver1.getNodeStats())
// //        println(receiver2.getNodeStats())
// ////    println(stream3.getNodeStats())
// //
// //        println("=======")
// //        println("Bridge:")
// //        println("  received ${b.numIncomingPackets} packets")
// //        println("  read ${b.numIncomingPackets} packets from queue")
// //        println("  forwarded ${b.numForwardedPackets} packets")
// //        println("      per packet ssrc: ${b.processedPacketsPerSsrc}")
// //        println("      per destination ssrc: ${b.packetsPerDestination}")
// //        println(sender1.getNodeStats())
// //        println(sender2.getNodeStats())
// //
// //        val totalPacketsSent = listOf(sender1, sender2).map(RtpSender::numPacketsSent).sum()
// //        println("Transmitted $totalPacketsSent packets")
// //
// //        exitProcess(0)
// //    }
//
//
// }
