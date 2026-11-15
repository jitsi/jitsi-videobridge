/*
 * Copyright @ 2026 - present 8x8, Inc.
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
package org.jitsi.nlj.transform.node.outgoing

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.jitsi.nlj.PacketHandler
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.SetLocalSsrcEvent
import org.jitsi.nlj.format.RtxPayloadType
import org.jitsi.nlj.format.Vp8PayloadType
import org.jitsi.nlj.resources.logging.StdoutLogger
import org.jitsi.nlj.rtp.VideoRtpPacket
import org.jitsi.nlj.util.BufferPool
import org.jitsi.nlj.util.PacketCache
import org.jitsi.nlj.util.StreamInformationStoreImpl
import org.jitsi.rtp.Packet
import org.jitsi.rtp.rtp.RtpHeader
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.utils.MediaType
import org.jitsi.utils.logging.DiagnosticContext

/**
 * Checks that the packets a [ProbingDataSender] puts out leave [RtpPacket.BYTES_TO_LEAVE_AT_START_OF_PACKET] bytes
 * before their data and [Packet.BYTES_TO_LEAVE_AT_END_OF_PACKET] bytes after it, like other packets. The rest of the
 * send pipeline counts on the room after the data, for example for the SRTP authentication tag, and uses the room
 * before it for header extensions.
 */
class ProbingDataSenderTest : ShouldSpec() {
    /** The sizes of the buffers of the pool of the bridge. */
    private fun sizeClass(size: Int) = when {
        size <= 220 -> 220
        size <= 775 -> 775
        size <= 1500 -> 1500
        else -> size
    }

    private class Capture : PacketHandler {
        val packets = mutableListOf<Packet>()

        override fun processPacket(packetInfo: PacketInfo) {
            packets.add(packetInfo.packet)
        }

        /** The lengths of the packets which do not have the room before and after their data. */
        fun lengthsWithoutRoom() = packets
            .filter {
                it.offset < RtpPacket.BYTES_TO_LEAVE_AT_START_OF_PACKET ||
                    it.buffer.size - it.offset - it.length < Packet.BYTES_TO_LEAVE_AT_END_OF_PACKET
            }
            .map { it.length }
    }

    private class Sender(val cache: PacketCache = PacketCache()) {
        val rtx = Capture()
        val padding = Capture()
        private val store = StreamInformationStoreImpl().apply {
            addRtpPayloadType(Vp8PayloadType(100))
            addRtpPayloadType(RtxPayloadType(96, mapOf("apt" to "100")))
        }
        val probingDataSender = ProbingDataSender(cache, rtx, padding, DiagnosticContext(), store, StdoutLogger())
            .apply { handleEvent(SetLocalSsrcEvent(MediaType.VIDEO, 123)) }
    }

    init {
        val savedGetBuffer = BufferPool.getBuffer
        val savedGetArray = org.jitsi.rtp.util.BufferPool.getArray
        beforeSpec {
            BufferPool.getBuffer = { size -> ByteArray(sizeClass(size)) }
            org.jitsi.rtp.util.BufferPool.getArray = { size -> ByteArray(sizeClass(size)) }
        }
        afterSpec {
            BufferPool.getBuffer = savedGetBuffer
            org.jitsi.rtp.util.BufferPool.getArray = savedGetArray
        }

        context("Probing sent as padding") {
            should("leave room before and after the data of each packet") {
                val sender = Sender()

                // A padding packet has at most 255 bytes of padding, so this covers every length of them.
                for (numBytes in 12..600) {
                    sender.probingDataSender.sendProbing(listOf(MEDIA_SSRC), numBytes, null)
                }

                sender.rtx.packets.shouldBeEmpty()
                sender.padding.packets.map { it.length }.toSet() shouldBe (12..267).toSet()
                sender.padding.lengthsWithoutRoom().shouldBeEmpty()
            }
        }
        context("The sequence numbers of padding packets") {
            should("wrap at 16 bits") {
                val sender = Sender()

                // The first sequence number is random, so send enough packets to pass 0xFFFF from any start.
                repeat(0x10000 + 10) {
                    sender.probingDataSender.sendProbing(listOf(MEDIA_SSRC), 12, null)
                }

                val sequenceNumbers = sender.padding.packets.map { (it as RtpPacket).sequenceNumber }
                val first = sequenceNumbers.first()
                sequenceNumbers shouldBe List(0x10000 + 10) { (first + it) and 0xFFFF }
                // RFC 8888 feedback is matched with the sequence number that is in the header.
                sender.padding.packets.count {
                    RtpHeader.getSequenceNumber(it.buffer, it.offset) != (it as RtpPacket).sequenceNumber
                } shouldBe 0
            }
        }
        context("Probing sent as retransmissions") {
            should("leave room before and after the data of each packet") {
                val sender = Sender()
                // Packets at the very end of their buffers, with lengths on both sides of the sizes of the pool.
                for ((index, length) in (180..230 step 2).withIndex()) {
                    val packet = VideoRtpPacket(ByteArray(length), 0, length).apply {
                        version = 2
                        payloadType = 100
                        ssrc = MEDIA_SSRC
                        // The cache does not give out a packet with the index 0 again.
                        sequenceNumber = index + 1
                    }
                    sender.cache.insert(packet)

                    sender.probingDataSender.sendProbing(listOf(MEDIA_SSRC), 3 * length, null)
                }

                sender.rtx.packets.shouldNotBeEmpty()
                sender.rtx.packets.map { it.length }.toSet() shouldBe (180..230 step 2).toSet()
                sender.rtx.lengthsWithoutRoom().shouldBeEmpty()
                sender.padding.lengthsWithoutRoom().shouldBeEmpty()
            }
        }
    }

    companion object {
        private const val MEDIA_SSRC = 1000L
    }
}
