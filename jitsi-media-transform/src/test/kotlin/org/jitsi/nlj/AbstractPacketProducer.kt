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

import io.pkts.Pcap
import io.pkts.packet.Packet
import io.pkts.packet.UDPPacket
import io.pkts.protocol.Protocol
import org.jitsi.nlj.srtp.SrtpProfileInformation
import org.jitsi.nlj.srtp.TlsRole
import org.jitsi.rtp.UnparsedPacket
import java.lang.Thread.sleep
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import kotlin.math.exp


abstract class AbstractPacketProducer : PacketProducer {
    private val handlers = mutableListOf<PacketReceiver>()

    override fun subscribe(handler: PacketReceiver) {
        handlers.add(handler)
    }

    protected fun onPacket(packet: org.jitsi.rtp.Packet) {
        handlers.forEach { it(packet) }
    }
}

data class SrtpInformation(
    val srtpProfileInformation: SrtpProfileInformation,
    val keyingMaterial: ByteArray,
    val tlsRole: TlsRole
)

data class PcapFileInformation(
    val filePath: String,
    val srtpInformation: SrtpInformation
)

class PcapPacketProducer(
    pcapFileInformation: PcapFileInformation
) : AbstractPacketProducer() {
    private val pcap = Pcap.openStream(pcapFile)
    var running: Boolean = true

    companion object {
        private fun translateToUnparsedPacket(pktsPacket: Packet): UnparsedPacket {
            val buf = if (pktsPacket.hasProtocol(Protocol.UDP)) {
                val udpPacket = pktsPacket.getPacket(Protocol.UDP) as UDPPacket
                ByteBuffer.wrap(udpPacket.payload.array)
            } else {
                // When capturing on the loopback interface, the packets have a null ethernet
                // frame which messes up the pkts libary's parsing, so instead use a hack to
                // grab the buffer directly
                ByteBuffer.wrap(pktsPacket.payload.array, 32, (pktsPacket.payload.array.size - 32)).slice()
            }
            return UnparsedPacket(buf)
        }

        private fun now(): Long = System.nanoTime()

        private fun nowMicros(): Long = System.nanoTime() / 1000

        private fun getMillisNanos(micros: Long): Pair<Long, Int> {
            val millis = micros / 1000
            val nanos = (micros - (millis * 1000)) * 1000

            return Pair(millis, nanos.toInt())
        }

        private fun microSleep(micros: Long) {
            val (millis, nanos) = getMillisNanos(micros)
            sleep(millis, nanos)
        }
    }

//    fun run(loop: Boolean = false) {
//        var prevPacketArrivalTime: Long = -1
//        var prevPacketSentTime: Long = -1
//        var totalSize = 0
//        while (running) {
//            pcap.loop { pkt ->
//                totalSize += pkt.payload.readableBytes
//                val currPacketArrivalTime = pkt.arrivalTime
//                println("packet arrival time $currPacketArrivalTime")
//                if (prevPacketArrivalTime == -1L) {
//                    prevPacketArrivalTime = currPacketArrivalTime
//                }
//                if (prevPacketSentTime != -1L) {
//                    // Sleep so we get the correct pacing
//                    val interPacketDelta = currPacketArrivalTime - prevPacketArrivalTime
//                    val currDelta = nowMicros() - prevPacketSentTime
//                    if (interPacketDelta > currDelta) {
//                        microSleep(interPacketDelta - currDelta)
//                    }
//                }
//                val packet = translateToUnparsedPacket(pkt)
//                prevPacketSentTime = nowMicros()
//                onPacket(packet)
//
//                prevPacketArrivalTime = currPacketArrivalTime
//                true
//            }
//            if (!loop) {
//                running = false
//            }
//        }
//        println("total bytes: ${totalSize}")
//    }

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
//                    println("Want to send at $expectedSendTime, current time is $nowClockTime, sleeping for ${expectedSendTime - nowClockTime}")
                    TimeUnit.MICROSECONDS.sleep(expectedSendTime - nowClockTime)
                }
                val sendTime = nowMicros() - startTime
//                println("woke up at $sendTime wanted to wake up at $expectedSendTime")
                val delta = sendTime - expectedSendTime
//                if (delta < -1000) {
//                    println("packet is $delta micros behind schedule")
//                } else if (delta > 1000) {
//                    println("packet is $delta micros ahead of schedule")
//                }

                val packet = translateToUnparsedPacket(pkt)
                onPacket(packet)
                true
            }
            running = false
        }
    }
}
