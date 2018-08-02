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
package org.jitsi.nlj.transform2

import org.jitsi.nlj.transform2.module.ModuleChain
import org.jitsi.nlj.transform2.module.PacketHandler
import org.jitsi.nlj.transform2.module.PacketStatsModule
import org.jitsi.nlj.transform2.module.RtcpHandlerModule
import org.jitsi.nlj.transform2.module.TimeTagExtensionReader
import org.jitsi.nlj.transform2.module.TimeTagReader
import org.jitsi.nlj.transform2.module.TimeTaggerModule
import org.jitsi.nlj.transform2.module.getMbps
import org.jitsi.nlj.transform2.module.incoming.FecReceiverModule
import org.jitsi.nlj.transform2.module.incoming.PacketLossMonitorModule
import org.jitsi.nlj.transform2.module.incoming.SrtpDecryptModule
import org.jitsi.rtp.Packet
import org.jitsi.rtp.util.BitBuffer
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue

class RtpReceiverImpl(
    val id: Long,
    val executor: ExecutorService,
    val packetHandler: PacketHandler
) : RtpReceiver() {
    private val moduleChain: ModuleChain
    private val incomingPacketQueue = LinkedBlockingQueue<Packet>()
    var running = true

    var firstPacketWrittenTime: Long = 0
    var lastPacketWrittenTime: Long = 0
    var bytesReceived: Long = 0

    init {
        moduleChain = chain {
            name("Incoming chain")
            module(TimeTaggerModule("Start of incoming chain"))
            module(PacketStatsModule())
            module(SrtpDecryptModule())
//            demux {
//                name = "DTLS/SRTP demuxer"
//                packetPath {
//                    predicate = { packet ->
//                        // https://tools.ietf.org/html/rfc5764#section-5.1.2
//                        val firstByte = packet.buf.get(0)
//                        when (firstByte.toInt()) {
//                            in 20..63 -> true
//                            else -> false
//                        }
//                    }
//                    path = chain {
//                        name("DTLS chain")
//                    }
//                }
//            }
            demux {
                name = "RTP/RTCP demuxer"
                packetPath {
                    predicate = Packet::isRtp
                    path = chain {
                        name("RTP chain")
                        module(PacketLossMonitorModule())
                        module(FecReceiverModule())
                        module(TimeTagReader())
                        module(TimeTaggerModule("End of incoming chain"))
                        attach(packetHandler)
                    }
                }
                packetPath {
                    predicate = Packet::isRtcp
                    path = chain {
                        name("RTCP chain")
                        module(RtcpHandlerModule())
                    }
                }
            }
        }
//        moduleChain.findFirst(FecReceiverModule::class)?.let {
//            println("found fec receiver")
//            (it as FecReceiverModule).handlers.add {
//                println("fec receiver handler sees it recoevered packet $it")
//            }
//        }
        scheduleWork()
    }

    private fun scheduleWork() {
        // Rescheduling this job after reading a single packet to allow
        // other threads to run doesn't seem  to scale all that well,
        // but doing this in a while (true) loop
        // holds a single thread exclusively, making it impossible to play
        // with things like sharing threads across tracks.  Processing a
        // max amount of packets at a time seems to work as a nice
        // compromise between the two.  It would be nice to be able to
        // avoid the busy-loop style polling for a new packet though
        executor.execute {
            val packets = mutableListOf<Packet>()
            while (packets.size < 5) {
                val packet = incomingPacketQueue.poll() ?: break
                packets += packet
            }
            if (packets.isNotEmpty()) processPackets(packets)
            if (running) {
                scheduleWork()
            }
        }
    }

    override fun processPackets(pkts: List<Packet>) = moduleChain.processPackets(pkts)

    override fun getStats(): String {
        return with (StringBuffer()) {
            appendln("RTP Receiver $id")
            appendln("queue size: ${incomingPacketQueue.size}")
            appendln("Received $bytesReceived bytes in ${lastPacketWrittenTime - firstPacketWrittenTime}ms (${getMbps(bytesReceived, Duration.ofMillis(lastPacketWrittenTime - firstPacketWrittenTime))} mbps)")
            append(moduleChain.getStats())
            toString()
        }
    }

    override fun enqueuePacket(p: Packet) {
        incomingPacketQueue.add(p)
        bytesReceived += p.size
        if (firstPacketWrittenTime == 0L) {
            firstPacketWrittenTime = System.currentTimeMillis()
        }
        lastPacketWrittenTime = System.currentTimeMillis()
    }
}
