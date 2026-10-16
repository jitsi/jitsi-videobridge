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
package org.jitsi.nlj.rtcp

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import org.jitsi.nlj.PacketHandler
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.resources.logging.StdoutLogger
import org.jitsi.nlj.rtp.VideoRtpPacket
import org.jitsi.nlj.util.PacketCache
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.RtcpFbNackPacketBuilder
import org.jitsi.utils.ms
import org.jitsi.utils.secs
import org.jitsi.utils.time.FakeClock

/**
 * A receiver that comes back after a network outage of a few seconds NACKs every packet that it lost. The
 * [PacketCache] holds 500 packets for each SSRC, so one receiver of 10 video streams can cause about 5000
 * retransmissions at the same time. Packets this old are no longer useful to the receiver, and the burst fills the
 * endpoint send queue. [NackHandler] must not retransmit packets older than max(1 s, 3 * RTT).
 */
class NackHandlerTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode = IsolationMode.InstancePerLeaf

    private val numSsrcs = 10
    private val packetsPerSsrc = 500

    private val clock = FakeClock()
    private val packetCache = PacketCache(clock = clock)
    private val retransmitted = mutableListOf<PacketInfo>()
    private val nackHandler = NackHandler(
        packetCache,
        object : PacketHandler {
            override fun processPacket(packetInfo: PacketInfo) {
                retransmitted.add(packetInfo)
            }
        },
        StdoutLogger(),
        clock
    ).apply { onRttUpdate(100.0) }

    private fun fillCache() {
        for (ssrc in 1L..numSsrcs) {
            for (seq in 0 until packetsPerSsrc) {
                packetCache.insert(videoPacket(ssrc, seq))
            }
        }
    }

    private fun nackEverything() {
        for (ssrc in 1L..numSsrcs) {
            val nack = RtcpFbNackPacketBuilder(
                mediaSourceSsrc = ssrc,
                missingSeqNums = (0 until packetsPerSsrc).toSortedSet()
            ).build()
            nackHandler.rtcpPacketReceived(nack, null)
        }
    }

    private fun numTooOld() = nackHandler.getNodeStats().getNumber("num_packets_not_retransmitted_too_old")

    init {
        context("A NACK for packets that were sent 5 seconds ago") {
            fillCache()
            clock.elapse(5.secs)
            nackEverything()
            should("not retransmit them") {
                retransmitted.size shouldBe 0
                numTooOld() shouldBe numSsrcs * packetsPerSsrc
            }
        }
        context("A NACK for packets that were sent 200 ms ago") {
            fillCache()
            clock.elapse(200.ms)
            nackEverything()
            should("retransmit all of them") {
                retransmitted.size shouldBe numSsrcs * packetsPerSsrc
                numTooOld() shouldBe 0
            }
        }
        context("With an RTT of 600 ms (the limit is 3 * RTT = 1800 ms)") {
            nackHandler.onRttUpdate(600.0)
            fillCache()
            context("A NACK for packets that were sent 1500 ms ago") {
                clock.elapse(1500.ms)
                nackEverything()
                should("retransmit all of them") {
                    retransmitted.size shouldBe numSsrcs * packetsPerSsrc
                }
            }
            context("A NACK for packets that were sent 2000 ms ago") {
                clock.elapse(2000.ms)
                nackEverything()
                should("not retransmit them") {
                    retransmitted.size shouldBe 0
                }
            }
        }
        context("A packet that was already retransmitted") {
            fillCache()
            clock.elapse(800.ms)
            nackEverything()
            retransmitted.size shouldBe numSsrcs * packetsPerSsrc
            retransmitted.clear()

            // The last retransmission was 700 ms ago, but the original transmission was 1500 ms ago.
            clock.elapse(700.ms)
            nackEverything()
            should("not be retransmitted again when the original transmission is too old") {
                retransmitted.size shouldBe 0
                numTooOld() shouldBe numSsrcs * packetsPerSsrc
            }
        }
    }

    private fun videoPacket(ssrc: Long, seq: Int): VideoRtpPacket {
        val length = 1000
        return VideoRtpPacket(ByteArray(length).also { it[0] = 0x80.toByte() }, 0, length).apply {
            this.ssrc = ssrc
            this.sequenceNumber = seq
        }
    }
}
