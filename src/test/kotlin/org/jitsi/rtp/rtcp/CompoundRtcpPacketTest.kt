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

import io.kotlintest.IsolationMode
import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbPliPacketBuilder
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbRembPacketBuilder

internal class CompoundRtcpPacketTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    init {
        "Creating a compound packet from a list of packets" {
            val rr = RtcpRrPacketBuilder(RtcpHeaderBuilder(), mutableListOf()).build()
            val remb = RtcpFbRembPacketBuilder(RtcpHeaderBuilder(), listOf(123), 12345).build()
            val pli = RtcpFbPliPacketBuilder(RtcpHeaderBuilder(), 456).build()

            val packets = arrayOf(rr, remb, pli)
            val compoundRtcpPacket = CompoundRtcpPacket(*packets)
            compoundRtcpPacket.packets.size shouldBe packets.size
            compoundRtcpPacket.packets.forEachIndexed { i, packet ->
                packet.length shouldBe packets[i].length
                packet::class shouldBe packets[i]::class
            }
        }
    }
}
