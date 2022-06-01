/*
 * Copyright @ 2018 - present 8x8, Inc.
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

package org.jitsi.rtp.rtcp

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import org.jitsi.rtp.Packet
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbPliPacketBuilder
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbRembPacketBuilder

internal class CompoundRtcpPacketTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    init {
        context("Creating a compound packet from a list of packets") {
            val rr = RtcpRrPacketBuilder(RtcpHeaderBuilder(), mutableListOf()).build()
            val remb = RtcpFbRembPacketBuilder(RtcpHeaderBuilder(), listOf(123), 12345).build()
            val pli = RtcpFbPliPacketBuilder(RtcpHeaderBuilder(), 456).build()

            val packets = listOf(rr, remb, pli)
            val compoundRtcpPacket = CompoundRtcpPacket(packets)
            compoundRtcpPacket.packets.size shouldBe packets.size
            compoundRtcpPacket.packets.forEachIndexed { i, packet ->
                packet.length shouldBe packets[i].length
                packet::class shouldBe packets[i]::class
            }
        }
        context("Parsing a compound RTCP packet which contains invalid data") {
            val packet = DummyPacket(rtcpByeNoReason + invalidRtcpData)
            shouldThrow<CompoundRtcpContainedInvalidDataException> {
                CompoundRtcpPacket.parse(packet.buffer, packet.offset, packet.length)
            }
        }

        context("Creating multiple compound packets by limiting to MTU") {
            val rr = RtcpRrPacketBuilder(RtcpHeaderBuilder(), mutableListOf()).build()
            val compoundPackets = CompoundRtcpPacket.createWithMtu(generateSequence { rr }.take(200).toList())
            // rr without report blocks is 8 bytes, so 200 is 2000 bytes; default MTU value is 1500.
            compoundPackets.size shouldBe 2
        }
    }

    private val invalidRtcpData = byteArrayOf(
        0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00
    )

    private val rtcpByeNoReason = RtcpHeaderBuilder(
        packetType = RtcpByePacket.PT,
        reportCount = 1,
        senderSsrc = 12345L,
        length = 1
    ).build()
}

private class DummyPacket(
    buf: ByteArray
) : Packet(buf, 0, buf.size) {
    override fun clone(): Packet = DummyPacket(cloneBuffer(0))
}
