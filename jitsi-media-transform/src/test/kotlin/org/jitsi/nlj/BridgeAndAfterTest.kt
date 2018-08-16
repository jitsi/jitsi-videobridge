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

import io.kotlintest.specs.ShouldSpec
import io.pkts.Pcap
import io.pkts.packet.UDPPacket
import io.pkts.protocol.Protocol
import org.jitsi.nlj.transform.module.getMbps
import org.jitsi.rtp.Packet
import org.jitsi.rtp.RtpPacket
import java.lang.Thread.sleep
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.Executors
import kotlin.system.measureTimeMillis

//internal class BridgeAndAfterTest : ShouldSpec() {
//    init {
//        val trackExecutor = Executors.newFixedThreadPool(12)
//        val bridgeExecutor = Executors.newSingleThreadExecutor()
//        val b = Bridge(bridgeExecutor)
//
//        val pcap = Pcap.openStream("/Users/bbaldino/2_participants_sim_sending_to_bridge_5_mins.pcap")
//
//        val ssrcs = getSsrcsInPcap("/Users/bbaldino/2_participants_sim_sending_to_bridge_5_mins.pcap")
//        println("ssrcs in trace: $ssrcs")
//        val senders = ssrcs.map { ssrc ->
//            ssrc to RtpSenderImpl(ssrc, trackExecutor)
//        }.toMap()
//        senders.forEach(b::addSender)
//
//        var numPackets = 0
//        var numBytes = 0
//        val time = measureTimeMillis {
//            pcap.loop { pkt ->
//                if (pkt.hasProtocol(Protocol.UDP)) {
//                    val udpPacket = pkt.getPacket(Protocol.UDP) as UDPPacket
//                    val buf = ByteBuffer.wrap(udpPacket.payload.array)
//                    try {
//                        val p = Packet.parse(buf)
////                    println(p)
//                        val ssrc = when(p) {
//                            is RtpPacket -> p.header.ssrc
//                            is org.jitsi.rtp.rtcp.RtcpPacket -> p.header.senderSsrc
//                            else -> throw Exception("can't get ssrc of packet")
//                        }
//                        numBytes += p.size
//                        b.onIncomingPackets(listOf(p))
//                    } catch (e: Exception) {
////                    println(e.message)
//                    } catch (e: Error) {
////                    println(e.message)
//                    }
//                }
//                numPackets++
//                true
////            numPackets++ < 1000
//            }
//        }
//        val bitRateMbps = ((numBytes * 8) / (time / 1000.0)) / 1000000.0
//        println("Finished writing $numPackets packets ($numBytes bytes) in $time ms (${"%.2f".format(bitRateMbps)} mbps)")
//
//        sleep(30000)
//        b.running = false
//        senders.forEach { _, sender -> sender.running = false }
//
//        println("=======")
//        println("Bridge:")
//        println("  received ${b.numIncomingPackets} packets")
//        println("  received ${b.numIncomingBytes.get()} bytes in ${b.lasttPacketWrittenTime.get() - b.firstPacketWrittenTime.get()} ms (${getMbps(b.numIncomingBytes.get(), Duration.ofMillis(b.lasttPacketWrittenTime.get() - b.firstPacketWrittenTime.get()))} mbps)")
//        println("  incomingQueue size: ${b.incomingPacketQueue.size}")
//        println("  read ${b.numReadPackets} packets from incomingQueue")
//        println("  read ${b.numReadBytes} bytes in ${b.lastPacketReadTime - b.firstPacketReadTime} ms (${getMbps(b.numReadBytes, Duration.ofMillis(b.lastPacketReadTime - b.firstPacketReadTime))} mbps)")
//        println("  forwarded ${b.numForwardedPackets} packets")
//        println("      per packet ssrc: ${b.processedPacketsPerSsrc}")
//        println("      per destination ssrc: ${b.packetsPerDestination}")
//        println("average job time: ${b.execTimes.average()}")
//        println("=======")
//
//        senders.forEach { _, sender ->
//            println(sender.getStats())
//        }
//
//    }
//}
