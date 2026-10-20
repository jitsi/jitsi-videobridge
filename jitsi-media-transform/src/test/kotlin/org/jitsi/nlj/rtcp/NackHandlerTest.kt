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
import org.jitsi.config.withNewConfig
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
 * endpoint send queue. By default, [NackHandler] must not retransmit packets first sent more than max(3 s, 9 * RTT)
 * ago.
 */
class NackHandlerTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode = IsolationMode.InstancePerLeaf

    private val numSsrcs = 10
    private val packetsPerSsrc = 500

    private val clock = FakeClock()
    private val packetCache = PacketCache(clock = clock)
    private val retransmitted = mutableListOf<PacketInfo>()
    private fun createNackHandler() = NackHandler(
        packetCache,
        object : PacketHandler {
            override fun processPacket(packetInfo: PacketInfo) {
                retransmitted.add(packetInfo)
            }
        },
        StdoutLogger(),
        clock
    ).apply { onRttUpdate(100.0) }

    private var nackHandler = createNackHandler()

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
        context("A NACK for packets that were sent 2500 ms ago (less than the 3 s minimum)") {
            fillCache()
            clock.elapse(2500.ms)
            nackEverything()
            should("retransmit all of them") {
                retransmitted.size shouldBe numSsrcs * packetsPerSsrc
            }
        }
        context("With an RTT of 500 ms (the limit is 9 * RTT = 4500 ms)") {
            nackHandler.onRttUpdate(500.0)
            fillCache()
            context("A NACK for packets that were sent 4000 ms ago") {
                clock.elapse(4000.ms)
                nackEverything()
                should("retransmit all of them") {
                    retransmitted.size shouldBe numSsrcs * packetsPerSsrc
                }
            }
            context("A NACK for packets that were sent 5000 ms ago") {
                clock.elapse(5000.ms)
                nackEverything()
                should("not retransmit them") {
                    retransmitted.size shouldBe 0
                }
            }
        }
        context("With an RTT of 300 ms, a second NACK for a packet whose retransmission was lost") {
            // The receiver repeats a NACK about once per RTT. Its NACK for a lost retransmission can arrive more than
            // 1 s after the first transmission.
            nackHandler.onRttUpdate(300.0)
            fillCache()
            // NackHandler does not retransmit a packet sent less than about 1 RTT ago.
            clock.elapse(300.ms)
            nackEverything()
            retransmitted.size shouldBe numSsrcs * packetsPerSsrc
            retransmitted.clear()

            clock.elapse(1000.ms)
            nackEverything()
            should("retransmit it again") {
                retransmitted.size shouldBe numSsrcs * packetsPerSsrc
            }
        }
        context("A packet that was already retransmitted") {
            fillCache()
            clock.elapse(2000.ms)
            nackEverything()
            retransmitted.size shouldBe numSsrcs * packetsPerSsrc
            retransmitted.clear()

            // The last retransmission was 1500 ms ago, but the first transmission was 3500 ms ago.
            clock.elapse(1500.ms)
            nackEverything()
            should("not be retransmitted again when the first transmission is too old") {
                retransmitted.size shouldBe 0
                numTooOld() shouldBe numSsrcs * packetsPerSsrc
            }
        }
        context("With a max age of max(2 s, 3 * RTT) in the configuration") {
            withNewConfig(
                """
                jmt.nack.max-packet-age = 2 seconds
                jmt.nack.max-packet-age-rtts = 3
                """.trimIndent()
            ) {
                nackHandler = createNackHandler()
            }
            fillCache()
            context("A NACK for packets that were sent 1500 ms ago") {
                clock.elapse(1500.ms)
                nackEverything()
                should("retransmit all of them") {
                    retransmitted.size shouldBe numSsrcs * packetsPerSsrc
                }
            }
            context("A NACK for packets that were sent 2500 ms ago") {
                clock.elapse(2500.ms)
                nackEverything()
                should("not retransmit them") {
                    retransmitted.size shouldBe 0
                }
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
