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

import org.jitsi.nlj.transform.chain
import org.jitsi.nlj.transform.module.ModuleChain
import org.jitsi.nlj.transform.module.RtcpHandlerModule
import org.jitsi.nlj.transform.module.TimeTagReader
import org.jitsi.nlj.transform.module.getMbps
import org.jitsi.nlj.transform.module.outgoing.FecSenderModule
import org.jitsi.nlj.transform.module.outgoing.SrtpEncryptModule
import org.jitsi.nlj.transform.packetPath
import org.jitsi.rtp.Packet
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue

class RtpSenderImpl(val id: Long, val executor: ExecutorService) : RtpSender() {
    private val moduleChain: ModuleChain
    private val outgoingRtpChain: ModuleChain
    private val outgoingRtcpChain: ModuleChain
    val incomingPacketQueue = LinkedBlockingQueue<Packet>()
    var numIncomingBytes: Long = 0
    var firstPacketWrittenTime = -1L
    var lastPacketWrittenTime = -1L
    var running = true
    init {
        outgoingRtpChain = chain {
            name("Outgoing RTP chain")
            addModule(FecSenderModule())
        }
        outgoingRtcpChain = chain {
            name("Outgoing RTCP chain")
            addModule(RtcpHandlerModule())
        }

        moduleChain = chain {
            addModule(TimeTagReader())
//            demux {
//                name("RTP/RTCP demuxer")
//                packetPath {
//                    predicate = Packet::isRtp
//                    path = outgoingRtpChain
//                }
//                packetPath {
//                    predicate = Packet::isRtcp
//                    path = outgoingRtcpChain
//                }
//            }
//            mux {
//                attachInput(outgoingRtpChain)
//                attachInput(outgoingRtcpChain)
//            }
//            addModule(SrtpEncryptModule())
//            addModule(TimeTagReader())
            attach(packetSender)
        }
        scheduleWork()
//        scheduleWorkDedicated()
    }

    override fun sendPackets(pkts: List<Packet>) {
        incomingPacketQueue.addAll(pkts)
        pkts.forEach { numIncomingBytes += it.size }
        if (firstPacketWrittenTime == -1L) {
            firstPacketWrittenTime = System.currentTimeMillis()
        }
        lastPacketWrittenTime = System.currentTimeMillis()
    }

    private fun scheduleWorkDedicated() {
        executor.execute {
            while (running) {
                val packetsToProcess = mutableListOf<Packet>()
                while (packetsToProcess.size < 5) {
                    packetsToProcess.add(incomingPacketQueue.take())
                }
                moduleChain.processPackets(packetsToProcess)
            }
        }
    }

    private fun scheduleWork() {
        executor.execute {
            if (running) {
                val packetsToProcess = mutableListOf<Packet>()
                incomingPacketQueue.drainTo(packetsToProcess, 20)
                if (packetsToProcess.isNotEmpty()) moduleChain.processPackets(packetsToProcess)

                scheduleWork()
            }
        }
    }

    override fun getStats(): String {
        val bitRateMbps = getMbps(numBytesSent, Duration.ofMillis(lastPacketSentTime - firstPacketSentTime))
        return with (StringBuffer()) {
            appendln("RTP Sender $id")
            appendln("queue size: ${incomingPacketQueue.size}")
            appendln("$numIncomingBytes incoming bytes in ${lastPacketWrittenTime - firstPacketWrittenTime} (${getMbps(numIncomingBytes, Duration.ofMillis(lastPacketWrittenTime - firstPacketWrittenTime))} mbps)")
            appendln("Sent $numPacketsSent packets in ${lastPacketSentTime - firstPacketSentTime} ms")
            appendln("Sent $numBytesSent bytes in ${lastPacketSentTime - firstPacketSentTime} ms ($bitRateMbps mbps)")
            append(moduleChain.getStats())
            toString()
        }
    }
}
