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
package org.jitsi.nlj.transform.node.incoming

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import org.jitsi.nlj.EncodingLivenessTracker
import org.jitsi.nlj.MediaSourceDesc
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.RtpEncodingDesc
import org.jitsi.nlj.RtpLayerDesc
import org.jitsi.nlj.SetMediaSourcesEvent
import org.jitsi.nlj.resources.logging.StdoutLogger
import org.jitsi.nlj.rtp.ParsedVideoPacket
import org.jitsi.nlj.rtp.codec.vpx.VpxRtpLayerDesc
import org.jitsi.utils.secs
import org.jitsi.utils.time.FakeClock

class EncodingLivenessNodeTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode = IsolationMode.InstancePerLeaf

    private val clock = FakeClock()
    private val node = EncodingLivenessNode(StdoutLogger(), clock)

    /** Packets are sent in order; the liveness trackers tell frames apart by sequence number. */
    private var nextSequenceNumber = 0

    private fun send(ssrc: Long, timestamp: Long, isKeyframe: Boolean = false) {
        val packet = FakeLivenessPacket(ssrc, timestamp, isKeyframe)
        packet.sequenceNumber = nextSequenceNumber++
        node.processPacket(PacketInfo(packet))
    }

    private val lowSsrc = 0x1111L
    private val highSsrc = 0x2222L
    private val source = MediaSourceDesc(
        arrayOf(
            RtpEncodingDesc(lowSsrc, arrayOf<RtpLayerDesc>(VpxRtpLayerDesc(0, 0, -1, 180, 30.0))),
            RtpEncodingDesc(highSsrc, arrayOf<RtpLayerDesc>(VpxRtpLayerDesc(1, 0, -1, 720, 30.0)))
        ),
        "owner",
        "name"
    )

    init {
        node.handleEvent(SetMediaSourcesEvent(arrayOf(source), arrayOf(source)))

        context("encoding liveness") {
            val low = source.rtpEncodings[0]
            val high = source.rtpEncodings[1]
            should("record nothing before any packet is received") {
                low.liveness.lastPacketReceivedMs shouldBe EncodingLivenessTracker.NEVER_RECEIVED
                high.liveness.lastPacketReceivedMs shouldBe EncodingLivenessTracker.NEVER_RECEIVED
            }
            context("after a packet on one encoding") {
                clock.elapse(5.secs)
                send(lowSsrc, 1000)
                should("record the packet time on that encoding only") {
                    low.liveness.lastPacketReceivedMs shouldBe clock.millis()
                    high.liveness.lastPacketReceivedMs shouldBe EncodingLivenessTracker.NEVER_RECEIVED
                    source.isEncodingLive(0, clock.millis()) shouldBe true
                    source.isEncodingLive(1, clock.millis()) shouldBe false
                }
                should("no longer consider the encoding live once the timeout has passed") {
                    clock.elapse(2.secs)
                    source.isEncodingLive(0, clock.millis()) shouldBe false
                }
            }
        }
    }
}

/** A video packet with the properties the node reads, and nothing else. */
private class FakeLivenessPacket(ssrc: Long, timestamp: Long, override val isKeyframe: Boolean) :
    ParsedVideoPacket(ByteArray(100).also { it[0] = 0x80.toByte() }, 0, 100, 0) {
    init {
        this.ssrc = ssrc
        this.timestamp = timestamp
    }

    override val layerIds: Collection<Int> = listOf(0)
    override val isStartOfFrame: Boolean = true
    override val isEndOfFrame: Boolean = true
    override fun meetsRoutingNeeds() = true
}
