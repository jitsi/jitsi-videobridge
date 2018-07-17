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
import org.jitsi.nlj.transform2.module.RtcpHandlerModule
import org.jitsi.nlj.transform2.module.outgoing.FecSenderModule
import org.jitsi.nlj.transform2.module.outgoing.SrtpEncryptModule
import org.jitsi.rtp.Packet
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue

class RtpSenderImpl(val id: Long, val executor: ExecutorService) : RtpSender() {
    private val moduleChain: ModuleChain
    private val outgoingRtpChain: ModuleChain
    private val outgoingRtcpChain: ModuleChain
    val incomingPacketQueue = LinkedBlockingQueue<Packet>()
    var running = true
    init {
        outgoingRtpChain = chain {
            name("Outgoing RTP chain")
            module(FecSenderModule())
        }
        outgoingRtcpChain = chain {
            name("Outgoing RTCP chain")
            module(RtcpHandlerModule())
        }

        moduleChain = chain {
            demux {
                name("RTP/RTCP demuxer")
                packetPath {
                    predicate = Packet::isRtp
                    path = outgoingRtpChain
                }
                packetPath {
                    predicate = Packet::isRtcp
                    path = outgoingRtcpChain
                }
            }
            mux {
                attachInput(outgoingRtpChain)
                attachInput(outgoingRtcpChain)
            }
            module(SrtpEncryptModule())
            attach(packetSender)
        }
        scheduleWork()
    }

    override fun sendPackets(pkts: List<Packet>) {
        incomingPacketQueue.addAll(pkts)
    }

    private fun scheduleWork() {
        executor.execute {
            if (running) {
                val packetsToProcess = mutableListOf<Packet>()
                while (packetsToProcess.size < 5) {
                    val packet = incomingPacketQueue.poll() ?: break
                    packetsToProcess += packet
                }
                moduleChain.processPackets(packetsToProcess)

                scheduleWork()
            }
        }
    }

    override fun getStats(): String {
        return with (StringBuffer()) {
            appendln("Track $id")
            appendln("queue size: ${incomingPacketQueue.size}")
            append(moduleChain.getStats())
            toString()
        }
    }
}
