package org.jitsi.nlj

import io.kotlintest.specs.ShouldSpec
import io.pkts.Pcap
import io.pkts.packet.UDPPacket
import io.pkts.protocol.Protocol
//import org.jitsi.nlj.transform.module.getMbps
import org.jitsi.rtp.Packet
import org.jitsi.rtp.RtpPacket
import java.lang.Thread.sleep
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlin.system.measureTimeMillis

//fun getSsrcsInPcap(filePath: String): Set<Long> {
//    val pcap = Pcap.openStream(filePath)
//    val ssrcs = mutableSetOf<Long>()
//    pcap.loop { pkt ->
//        if (pkt.hasProtocol(Protocol.UDP)) {
//            val udpPacket = pkt.getPacket(Protocol.UDP) as UDPPacket
//            val buf = ByteBuffer.wrap(udpPacket.payload.array)
//            try {
//                val p = Packet.parse(buf)
//                val ssrc = when (p) {
//                    is RtpPacket -> p.header.ssrc
//                    is org.jitsi.rtp.rtcp.RtcpPacket -> p.header.senderSsrc
//                    else -> throw Exception()
//                }
//                ssrcs.add(ssrc)
//            } catch (e: Exception) {
////                println(e.message)
//            } catch (e: Error) {
////                println(e.message)
//            }
//        }
//        true
//    }
//    return ssrcs
//}
//
//fun readAndSendPackets(pcapFile: String, receivers: Map<Long, RtpReceiverImpl>) {
//    val pcap = Pcap.openStream(pcapFile)
//    var numBytes: Long = 0
//    var numPackets = 0
//    val time = measureTimeMillis {
//        pcap.loop { pkt ->
//            if (pkt.hasProtocol(Protocol.UDP)) {
//                val udpPacket = pkt.getPacket(Protocol.UDP) as UDPPacket
//                val buf = ByteBuffer.wrap(udpPacket.payload.array)
//                try {
//                    val p = Packet.parse(buf)
//                    val ssrc = when (p) {
//                        is RtpPacket -> p.header.ssrc
//                        is org.jitsi.rtp.rtcp.RtcpPacket -> p.header.senderSsrc
//                        else -> throw Exception("can't get ssrc of packet")
//                    }
//                    numBytes += p.size
//                    receivers[ssrc]?.enqueuePacket(p)
//                } catch (e: Exception) {
//                    println(e.message)
//                } catch (e: Error) {
//                    println(e.message)
//                }
//            }
//            numPackets++
//            true
//        }
//    }
//    val bitRateMbps = getMbps(numBytes, Duration.ofMillis(time))
//    println("Finished writing $numPackets packets ($numBytes bytes) in $time ms ($bitRateMbps mbps)")
//}

//internal class ThreadingmainKtTest : ShouldSpec() {
//    init {
//        val trackExecutor = Executors.newFixedThreadPool(30)
//        val bridgeExecutor = Executors.newSingleThreadExecutor()
////        val b = Bridge(trackExecutor)
//        val b = Bridge(bridgeExecutor)
//
//        val ssrcs = getSsrcsInPcap("/Users/bbaldino/2_participants_sim_sending_to_bridge_5_mins.pcap")
//        println("ssrcs in trace: $ssrcs")
//        val receivers = ssrcs.map { ssrc ->
//            ssrc to RtpReceiverImpl(ssrc, trackExecutor, b::onIncomingPackets)
//        }.toMap()
//        val senders = ssrcs.map { ssrc ->
////            ssrc to RtpSenderImpl(ssrc, trackExecutor)
//            ssrc to RtpSenderImpl(ssrc, Executors.newSingleThreadExecutor())
//        }.toMap()
//
//        senders.forEach(b::addSender)
//
//        b.scheduleWorkDedicated()
//        var sendingPackets = true
//        var packetSender = thread {
//            var numIters = 0
//            while (sendingPackets) {
//                println("Send packet iteration ${++numIters}")
//                readAndSendPackets("/Users/bbaldino/2_participants_sim_sending_to_bridge_5_mins.pcap", receivers)
//            }
//            println("Packet sender stopped")
//        }
//
//        sleep(30000)
//
//        sendingPackets = false
//        receivers.forEach { _, receiver -> receiver.running = false }
//        b.running = false
//        senders.forEach { _, sender -> sender.running = false }
//
//        receivers.forEach { _, receiver ->
//            println(receiver.getStats())
//        }
//        println("=======")
//        println("Bridge:")
//        println("  received ${b.numIncomingPackets} packets")
//        println("  received ${b.numIncomingBytes.get()} bytes in ${b.lasttPacketWrittenTime.get() - b.firstPacketWrittenTime.get()} ms (${getMbps(b.numIncomingBytes.get(), Duration.ofMillis(b.lasttPacketWrittenTime.get() - b.firstPacketWrittenTime.get()))} mbps)")
//        println("  incomingQueue size: ${b.incomingPacketQueue.size}")
//        println("  num times incomingQueue empty: ${b.numTimesQueueEmpty}")
//        println("  read ${b.numReadPackets} packets from incomingQueue")
//        println("  read ${b.numReadBytes} bytes in ${b.lastPacketReadTime - b.firstPacketReadTime} ms (${getMbps(b.numReadBytes, Duration.ofMillis(b.lastPacketReadTime - b.firstPacketReadTime))} mbps)")
//        println("  forwarded ${b.numForwardedPackets} packets")
//        println("      per packet ssrc: ${b.processedPacketsPerSsrc}")
//        println("      per destination ssrc: ${b.packetsPerDestination}")
//        println("average job time: ${b.execTimes.average()} nanos")
//        println("processing time per packet: ${b.processingTime / b.numReadPackets} nanos")
//        println("=======")
//
//        senders.forEach { _, sender ->
//            println(sender.getStats())
//        }
//    }
//
//}
