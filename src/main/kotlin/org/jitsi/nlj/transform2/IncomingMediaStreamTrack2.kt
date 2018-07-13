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

import org.jitsi.nlj.IncomingMediaStreamTrack
import org.jitsi.rtp.Packet
import java.lang.Thread.yield
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import kotlin.system.measureNanoTime

class IncomingMediaStreamTrack2(val id: Long, val executor: ExecutorService) : IncomingMediaStreamTrack {
    val moduleChain: ModuleChain
    val incomingPacketQueue = LinkedBlockingQueue<Packet>()
    var tempNumPackets = 0

    init {
        moduleChain = chain {
            name("Incoming chain")
            module(PacketStatsModule())
            module(SrtpDecrypt())
            demux {
                name = "RTP/RTCP demuxer"
                packetPath {
                    predicate = Packet::isRtp
                    path = chain {
                        name("RTP chain")
                        module(PacketLossMonitorModule())
                        module(FecReceiver())
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
//        moduleChain.findFirst(FecReceiver::class)?.let {
//            println("found fec receiver")
//            (it as FecReceiver).handlers.add {
//                println("fec receiver handler sees it recoevered packet $it")
//            }
//        }
    }

    fun start() {
        scheduleWork()
    }

    private fun scheduleWork() {
        // Rescheduling this job to allow other threads to run doesn't seem
        // to scale all that well, but doing this in a while (true) loop
        // holds a single thread exclusively, making it impossible to play
        // with things like sharing threads across tracks.  Processing a
        // max amount of packets at a time seems to work as a nice
        // compromise between the two
        executor.execute {
//            while (true) {

//            println("=====Incoming $id reading packet")
                val packets = mutableListOf<Packet>()
                do {
                    val packet = incomingPacketQueue.poll()
                    if (packet != null) packets += packet
                } while (packet != null && packets.size <= 5)
//            println("=====Incoming $id received packet")
            processPackets(packets)
//            println("=====Incoming $id processing packet finished")
            scheduleWork()
//            }
        }
    }

    override fun processPackets(pkts: List<Packet>) {
//        tempNumPackets += pkts.size
        val time = measureNanoTime {
            moduleChain.processPackets(pkts)
        }
//        println("Entire transform chain took $time nanos")
    }

    override fun getStats(): String {
        return with (StringBuffer()) {
            appendln("Track $id")
            appendln("packet rx: $tempNumPackets")
            append(moduleChain.getStats())
            toString()
        }
    }

    override fun enqueuePacket(p: Packet) {
        incomingPacketQueue.add(p)
    }
}
