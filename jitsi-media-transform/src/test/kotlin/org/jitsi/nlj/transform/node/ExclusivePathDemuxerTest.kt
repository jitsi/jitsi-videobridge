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

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.Spec
import io.kotest.matchers.shouldBe
import io.kotest.core.spec.style.ShouldSpec
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.util.PacketPredicate
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.rtp.rtp.RtpPacket

internal class ExclusivePathDemuxerTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    private class DummyHandler(name: String) : ConsumerNode(name) {
        var numReceived = 0
        override fun consume(packetInfo: PacketInfo) {
            numReceived++
        }

        override fun trace(f: () -> Unit) = f.invoke()
    }

    private class DummyRtcpPacket : RtcpPacket(ByteArray(50), 0, 50) {
        override fun clone(): DummyRtcpPacket = DummyRtcpPacket()
    }

    private val rtpPath = ConditionalPacketPath()
    private val rtpHandler = DummyHandler("RTP")
    private val rtcpPath = ConditionalPacketPath()
    private val rtcpHandler = DummyHandler("RTCP")

    private val demuxer = ExclusivePathDemuxer("test")

    private val rtpPacket = PacketInfo(RtpPacket(ByteArray(50), 0, 50))
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
        context("a packet which matches only one predicate") {
            demuxer.processPacket(rtcpPacket)
            should("only be demuxed to one path") {
                rtpHandler.numReceived shouldBe 0
                rtcpHandler.numReceived shouldBe 1
            }
        }
        context("a packet which matches more than one predicate") {
            val rtpPath2 = ConditionalPacketPath()
            val handler = DummyHandler("RTP 2")
            rtpPath2.name = "RTP 2"
            rtpPath2.predicate = PacketPredicate { it is RtpPacket }
            rtpPath2.path = handler
            demuxer.addPacketPath(rtpPath2)
            demuxer.processPacket(rtpPacket)
            should("only be demuxed to one path") {
                rtpHandler.numReceived shouldBe 1
                rtcpHandler.numReceived shouldBe 0
                handler.numReceived shouldBe 0
            }
        }
    }
}
