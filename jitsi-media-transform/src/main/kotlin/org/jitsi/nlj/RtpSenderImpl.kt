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

import org.jitsi.nlj.srtp_og.SinglePacketTransformer
import org.jitsi.nlj.transform.chain
import org.jitsi.nlj.transform.module.Module
import org.jitsi.nlj.transform.module.ModuleChain
import org.jitsi.nlj.transform.module.RtcpHandlerModule
import org.jitsi.nlj.transform.module.getMbps
import org.jitsi.nlj.transform.module.outgoing.SrtpTransformerWrapperEncrypt
import org.jitsi.rtp.Packet
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue

class RtpSenderImpl(
    val id: Long,
    val executor: ExecutorService = Executors.newSingleThreadExecutor()
) : RtpSender() {
    private val moduleChain: ModuleChain
    private val outgoingRtpChain: ModuleChain
    private val outgoingRtcpChain: ModuleChain
    val incomingPacketQueue = LinkedBlockingQueue<Packet>()
    var numIncomingBytes: Long = 0
    var firstPacketWrittenTime = -1L
    var lastPacketWrittenTime = -1L
    var running = true

    private val encryptWrapper = SrtpTransformerWrapperEncrypt()
    init {
        outgoingRtpChain = chain {
            name("Outgoing RTP chain")
            addModule(encryptWrapper)
        }
        outgoingRtcpChain = chain {
            name("Outgoing RTCP chain")
            addModule(RtcpHandlerModule())
        }

        moduleChain = chain {
            //TODO: for now just hard-code the rtp path
            addModule(encryptWrapper)
            addModule(object : Module("Packet sender") {
                override fun doProcessPackets(p: List<Packet>) {
                    packetSender.invoke(p)
                }
            })
        }
        scheduleWork()
    }

    override fun sendPackets(pkts: List<Packet>) {
        println("BRIAN: sender got ${pkts.size} packets to send")
        incomingPacketQueue.addAll(pkts)
        pkts.forEach { numIncomingBytes += it.size }
        if (firstPacketWrittenTime == -1L) {
            firstPacketWrittenTime = System.currentTimeMillis()
        }
        lastPacketWrittenTime = System.currentTimeMillis()
    }

    override fun setSrtpTransformer(srtpTransformer: SinglePacketTransformer) {
        encryptWrapper.srtpTransformer = srtpTransformer
    }

    private fun scheduleWork() {
        executor.execute {
            if (running) {
//                println("BRIAN: rtp sender job running")
                val packetsToProcess = mutableListOf<Packet>()
                incomingPacketQueue.drainTo(packetsToProcess, 20)
//                println("BRIAN: rtp sender job got ${packetsToProcess.size} packets to give to chain")
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
