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

package org.jitsi.nlj.transform.node

import io.kotlintest.IsolationMode
import io.kotlintest.Spec
import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec
import org.jitsi.nlj.PacketInfo
import org.jitsi.rtp.Packet
import org.jitsi.rtp.PacketPredicate
import org.jitsi.rtp.rtcp.RtcpHeader
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.rtp.rtp.RtpPacket

internal class ExclusivePathDemuxerTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    private class DummyHandler(name: String) : Node(name) {
        var numReceived = 0
        override fun doProcessPackets(p: List<PacketInfo>) {
            numReceived += p.size
        }
    }

    private class DummyRtcpPacket : RtcpPacket(RtcpHeader(), null) {
        override val sizeBytes: Int = 0
        override fun clone(): Packet {
            return DummyRtcpPacket()
        }
    }

    private val rtpPath = ConditionalPacketPath()
    private val rtpHandler = DummyHandler("RTP")
    private val rtcpPath = ConditionalPacketPath()
    private val rtcpHandler = DummyHandler("RTCP")

    private val demuxer = ExclusivePathDemuxer("test")

    private val rtpPacket = PacketInfo(RtpPacket())
    private val rtcpPacket = PacketInfo(DummyRtcpPacket())

    override fun beforeSpec(spec: Spec) {
        super.beforeSpec(spec)
        rtpPath.name = "RTP"
        rtpPath.predicate = PacketPredicate { it is RtpPacket }
        rtpPath.path = rtpHandler

        rtcpPath.name = "RTCP"
        rtcpPath.predicate = PacketPredicate { it is RtcpPacket }
        rtcpPath.path = rtcpHandler

        demuxer.addPacketPath(rtpPath)
        demuxer.addPacketPath(rtcpPath)
    }

    init {
        "a packet which matches only one predicate" {
            demuxer.processPackets(listOf(rtcpPacket))
            should("only be demuxed to one path") {
                rtpHandler.numReceived shouldBe 0
                rtcpHandler.numReceived shouldBe 1
            }
        }
        "a packet which matches more than one predicate" {
            val rtpPath2 = ConditionalPacketPath()
            val handler = DummyHandler("RTP 2")
            rtpPath2.name = "RTP 2"
            rtpPath2.predicate = PacketPredicate { it is RtpPacket }
            rtpPath2.path = handler
            demuxer.addPacketPath(rtpPath2)
            demuxer.processPackets(listOf(rtpPacket))
            should("only be demuxed to one path") {
                rtpHandler.numReceived shouldBe 1
                rtcpHandler.numReceived shouldBe 0
                handler.numReceived shouldBe 0
            }
        }
    }
}
