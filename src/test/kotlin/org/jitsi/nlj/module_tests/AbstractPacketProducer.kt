/*
 * Copyright @ 2018 - Present, 8x8 Inc
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

import io.pkts.Pcap
import io.pkts.packet.Packet
import io.pkts.packet.UDPPacket
import io.pkts.protocol.Protocol
import org.jitsi.rtp.UnparsedPacket
import java.util.concurrent.TimeUnit

abstract class AbstractPacketProducer : PacketProducer {
    private val handlers = mutableListOf<PacketReceiver>()

    override fun subscribe(handler: PacketReceiver) {
        handlers.add(handler)
    }

    protected fun onPacket(packet: org.jitsi.rtp.Packet) {
        handlers.forEach { it(packet) }
    }
}

/**
 * Read data from a PCAP file and play it out at a rate consistent with the packet arrival times.  I.e. if the PCAP
 * file captured data flowing at 2mbps, this producer will play it out at 2mbps
 */
class PcapPacketProducer(
    pcapFilePath: String
) : AbstractPacketProducer() {
    private val pcap = Pcap.openStream(pcapFilePath)
    var running: Boolean = true

    companion object {
        private fun translateToUnparsedPacket(pktsPacket: Packet): UnparsedPacket {
            // We always allocate a buffer with capacity 1500, so the packet has room to 'grow'
            val packetBuf = ByteArray(1500)
            return if (pktsPacket.hasProtocol(Protocol.UDP)) {
                val udpPacket = pktsPacket.getPacket(Protocol.UDP) as UDPPacket
                System.arraycopy(udpPacket.payload.array, 0, packetBuf, 0, udpPacket.payload.array.size)
                UnparsedPacket(packetBuf, 0, udpPacket.payload.array.size)
            } else {
                // When capturing on the loopback interface, the packets have a null ethernet
                // frame which messes up the pkts libary's parsing, so instead use a hack to
                // grab the buffer directly
                System.arraycopy(pktsPacket.payload.rawArray, 32, packetBuf, 0, pktsPacket.payload.rawArray.size - 32)
                UnparsedPacket(packetBuf, 0, pktsPacket.payload.rawArray.size - 32)
            }
        }

        private fun nowMicros(): Long = System.nanoTime() / 1000
    }

    fun run() {
        var firstPacketArrivalTime = -1L
        val startTime = nowMicros()
        while (running) {
            pcap.loop { pkt ->
                if (firstPacketArrivalTime == -1L) {
                    firstPacketArrivalTime = pkt.arrivalTime
                }
                val expectedSendTime = pkt.arrivalTime - firstPacketArrivalTime
                val nowClockTime = nowMicros() - startTime
                if (expectedSendTime > nowClockTime) {
                    TimeUnit.MICROSECONDS.sleep(expectedSendTime - nowClockTime)
                }

                val packet = translateToUnparsedPacket(pkt)
                onPacket(packet)
                true
            }
            running = false
        }
    }
}
