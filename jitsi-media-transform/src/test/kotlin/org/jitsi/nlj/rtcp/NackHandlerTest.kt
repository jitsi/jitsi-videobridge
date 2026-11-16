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

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import org.jitsi.config.withNewConfig
import org.jitsi.nlj.PacketHandler
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.resources.logging.StdoutLogger
import org.jitsi.nlj.rtp.VideoRtpPacket
import org.jitsi.nlj.util.Bandwidth
import org.jitsi.nlj.util.PacketCache
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.RtcpFbNackPacketBuilder
import org.jitsi.utils.ms
import org.jitsi.utils.secs
import org.jitsi.utils.time.FakeClock
import java.time.Duration
import org.jitsi.rtp.util.BufferPool as RtpBufferPool

/**
 * Tests of the limits that [NackHandler] applies to retransmissions:
 * - The age limit. A receiver that comes back after a network outage of a few seconds NACKs every packet that it
 *   lost. The [PacketCache] holds 500 packets for each SSRC, so one receiver of 10 video streams can cause about 5000
 *   retransmissions at the same time. Packets this old are no longer useful to the receiver, and the burst fills the
 *   endpoint send queue. By default, [NackHandler] must not retransmit packets first sent more than
 *   max(3 s, 9 * RTT) ago.
 * - The interval between two transmissions of a packet. It depends on the RTT, and has a minimum for a packet that
 *   was already retransmitted.
 * - The retransmission budget, which limits the bytes that one [NackHandler] retransmits.
 * - A packet that is not retransmitted is not copied out of the cache.
 */
class NackHandlerTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode = IsolationMode.InstancePerLeaf

    private val numSsrcs = 10
    private val packetsPerSsrc = 500

    private val clock = FakeClock()
    private val packetCache = PacketCache(clock = clock)
    private val retransmitted = mutableListOf<PacketInfo>()

    /**
     * By default the retransmission budget is much larger than what the tests send, so that it does not interfere
     * with the tests of the age limit and of the resend interval.
     */
    private fun createNackHandler(
        rtt: Double? = 100.0,
        maxRetransmissionBitrate: Bandwidth = Bandwidth(100_000_000_000L),
        retransmissionBurst: Duration = 1.secs,
        minResendInterval: Duration = 20.ms
    ) = NackHandler(
        packetCache,
        object : PacketHandler {
            override fun processPacket(packetInfo: PacketInfo) {
                retransmitted.add(packetInfo)
            }
        },
        StdoutLogger(),
        clock,
        maxRetransmissionBitrate = maxRetransmissionBitrate,
        retransmissionBurst = retransmissionBurst,
        minResendInterval = minResendInterval
    ).apply { rtt?.let { onRttUpdate(it) } }

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

    private fun insertPackets(ssrc: Long) {
        for (seq in 0 until packetsPerSsrc) {
            packetCache.insert(videoPacket(ssrc, seq))
        }
    }

    private fun nackSsrc(ssrc: Long, seqNums: IntRange = 0 until packetsPerSsrc) {
        val nack = RtcpFbNackPacketBuilder(
            mediaSourceSsrc = ssrc,
            missingSeqNums = seqNums.toSortedSet()
        ).build()
        nackHandler.rtcpPacketReceived(nack, null)
    }

    /**
     * Run [block], and return the number of packets that it copied out of the cache. A copy of a packet gets a buffer
     * of at least the length of the packet from the buffer pool. The buffer of a NACK is smaller.
     */
    private fun countPacketCopies(block: () -> Unit): Int {
        val getArray = RtpBufferPool.getArray
        var copies = 0
        RtpBufferPool.getArray = { size ->
            if (size >= PACKET_LENGTH) copies++
            getArray(size)
        }
        try {
            block()
        } finally {
            RtpBufferPool.getArray = getArray
        }
        return copies
    }

    private fun numRateLimited() = nackHandler.getNodeStats().getNumber("num_packets_not_retransmitted_rate_limited")

    private fun numCacheMisses() = nackHandler.getNodeStats().getNumber("num_cache_misses")

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
        context("When the RTT is unknown") {
            nackHandler = createNackHandler(rtt = null)
            fillCache()
            clock.elapse(100.ms)
            nackSsrc(1)
            retransmitted.size shouldBe packetsPerSsrc
            retransmitted.clear()
            context("A repeated NACK at the same time") {
                nackSsrc(1)
                should("not retransmit the packets again") {
                    retransmitted.size shouldBe 0
                }
            }
            context("A repeated NACK 5 ms later") {
                clock.elapse(5.ms)
                nackSsrc(1)
                should("not retransmit the packets again") {
                    retransmitted.size shouldBe 0
                }
            }
            context("A repeated NACK 25 ms later") {
                clock.elapse(25.ms)
                nackSsrc(1)
                should("retransmit the packets again") {
                    retransmitted.size shouldBe packetsPerSsrc
                }
            }
        }
        context("With an RTT of 100 ms, a repeated NACK 50 ms after a retransmission") {
            // The RTT-based interval (90 ms) is longer than the minimum resend interval (20 ms).
            fillCache()
            clock.elapse(100.ms)
            nackSsrc(1)
            retransmitted.size shouldBe packetsPerSsrc
            retransmitted.clear()
            clock.elapse(50.ms)
            nackSsrc(1)
            should("not retransmit the packets again") {
                retransmitted.size shouldBe 0
            }
        }
        context("A NACK for packets that are not all in the cache") {
            fillCache()
            clock.elapse(100.ms)
            // The cache has the sequence numbers 0 to 499.
            nackSsrc(1, 490..520)
            should("retransmit the packets that are in the cache") {
                retransmitted.size shouldBe 10
                numCacheMisses() shouldBe 21
            }
        }
        context("With an RTT of 2 ms (the RTT-based interval is negative)") {
            nackHandler = createNackHandler(rtt = 2.0)
            fillCache()
            clock.elapse(100.ms)
            nackSsrc(1)
            retransmitted.size shouldBe packetsPerSsrc
            retransmitted.clear()
            context("A repeated NACK 5 ms later") {
                clock.elapse(5.ms)
                nackSsrc(1)
                should("not retransmit the packets again") {
                    retransmitted.size shouldBe 0
                }
            }
            context("A repeated NACK 25 ms later") {
                clock.elapse(25.ms)
                nackSsrc(1)
                should("retransmit the packets again") {
                    retransmitted.size shouldBe packetsPerSsrc
                }
            }
        }
        context("The first NACK for a packet, less than the minimum resend interval after the packet was sent") {
            // A receiver with a small RTT sends its first NACK a few milliseconds after we sent the packet. The
            // minimum resend interval applies only to a packet that we already retransmitted.
            context("When the RTT is unknown") {
                nackHandler = createNackHandler(rtt = null)
                fillCache()
                clock.elapse(5.ms)
                nackSsrc(1)
                should("retransmit the packets") {
                    retransmitted.size shouldBe packetsPerSsrc
                }
            }
            context("With an RTT of 10 ms") {
                nackHandler = createNackHandler(rtt = 10.0)
                fillCache()
                clock.elapse(12.ms)
                nackSsrc(1)
                should("retransmit the packets") {
                    retransmitted.size shouldBe packetsPerSsrc
                }
                context("And a repeated NACK 12 ms later") {
                    retransmitted.clear()
                    clock.elapse(12.ms)
                    nackSsrc(1)
                    should("not retransmit the packets again") {
                        retransmitted.size shouldBe 0
                    }
                }
            }
        }
        context("With an RTT of 100 ms, the first NACK for packets that were sent 50 ms ago") {
            fillCache()
            clock.elapse(50.ms)
            nackSsrc(1)
            should("not retransmit them (the RTT-based interval applies to the first retransmission)") {
                retransmitted.size shouldBe 0
            }
        }
        context("Packets that are not retransmitted are not copied out of the cache") {
            context("When they were retransmitted less than the minimum resend interval ago") {
                nackHandler = createNackHandler(rtt = null)
                fillCache()
                clock.elapse(100.ms)
                nackSsrc(1)
                clock.elapse(5.ms)
                val copies = countPacketCopies { nackSsrc(1) }
                should("copy no packets for the second NACK") {
                    retransmitted.size shouldBe packetsPerSsrc
                    copies shouldBe 0
                }
            }
            context("When they are over the retransmission budget") {
                nackHandler = createNackHandler(
                    maxRetransmissionBitrate = Bandwidth(8_000_000),
                    retransmissionBurst = 1.secs
                )
                fillCache()
                clock.elapse(100.ms)
                val copies = countPacketCopies { nackEverything() }
                should("copy only the packets that the budget allows") {
                    retransmitted.size shouldBe 1000
                    copies shouldBe 1000
                }
            }
            context("When they are too old") {
                fillCache()
                clock.elapse(5.secs)
                val copies = countPacketCopies { nackEverything() }
                should("copy nothing") {
                    retransmitted.size shouldBe 0
                    copies shouldBe 0
                }
            }
        }
        context("With a retransmission budget of 1 MB burst and 1 MB/s") {
            // The packets are 1000 bytes long, so the burst is 1000 packets.
            nackHandler =
                createNackHandler(maxRetransmissionBitrate = Bandwidth(8_000_000), retransmissionBurst = 1.secs)
            fillCache()
            clock.elapse(100.ms)
            nackEverything()
            should("retransmit only as many packets as the burst allows") {
                retransmitted.size shouldBe 1000
                numRateLimited() shouldBe numSsrcs * packetsPerSsrc - 1000
            }
            context("A NACK 500 ms later") {
                retransmitted.clear()
                clock.elapse(500.ms)
                nackEverything()
                should("retransmit as many packets as were added to the budget") {
                    retransmitted.size shouldBe 500
                }
            }
            context("A NACK 5 s later (the packets are too old)") {
                retransmitted.clear()
                clock.elapse(5.secs)
                nackEverything()
                should("not retransmit anything") {
                    retransmitted.size shouldBe 0
                }
            }
        }
        context("A packet that was not retransmitted because of the budget") {
            // The minimum resend interval is longer than the time between the two NACKs. It applies only to a packet
            // that was retransmitted, so it must not apply to the packets of SSRC 10.
            nackHandler = createNackHandler(
                maxRetransmissionBitrate = Bandwidth(8_000_000),
                retransmissionBurst = 1.secs,
                minResendInterval = 2.secs
            )
            fillCache()
            clock.elapse(100.ms)
            nackEverything()
            retransmitted.clear()
            clock.elapse(1.secs)
            nackSsrc(10)
            should("be retransmitted when the budget allows it, with no wait for the minimum resend interval") {
                retransmitted.size shouldBe packetsPerSsrc
            }
        }
        context("With a retransmission burst of 500 ms at 1 MB/s") {
            nackHandler =
                createNackHandler(maxRetransmissionBitrate = Bandwidth(8_000_000), retransmissionBurst = 500.ms)
            fillCache()
            clock.elapse(100.ms)
            nackEverything()
            should("retransmit only the 500 packets that the burst allows") {
                retransmitted.size shouldBe 500
            }
        }
        context("A packet that is not retransmitted does not use the budget") {
            context("When it was retransmitted less than the minimum resend interval ago") {
                nackHandler = createNackHandler(
                    rtt = null,
                    maxRetransmissionBitrate = Bandwidth(8_000_000),
                    retransmissionBurst = 1.secs
                )
                fillCache()
                clock.elapse(100.ms)
                // This uses half of the budget.
                nackSsrc(1)
                clock.elapse(5.ms)
                nackSsrc(1)
                retransmitted.size shouldBe packetsPerSsrc
                retransmitted.clear()
                nackSsrc(2)
                should("leave the budget for the packets of another SSRC") {
                    retransmitted.size shouldBe packetsPerSsrc
                }
            }
            context("When it is too old") {
                nackHandler =
                    createNackHandler(maxRetransmissionBitrate = Bandwidth(8_000_000), retransmissionBurst = 1.secs)
                fillCache()
                clock.elapse(5.secs)
                nackEverything()
                retransmitted.size shouldBe 0
                insertPackets(11)
                clock.elapse(100.ms)
                nackSsrc(11)
                should("leave the budget for new packets") {
                    retransmitted.size shouldBe packetsPerSsrc
                }
            }
        }
        context("A retransmission budget that can not hold one packet") {
            should("be refused when the bitrate is 0") {
                shouldThrow<IllegalArgumentException> {
                    createNackHandler(maxRetransmissionBitrate = Bandwidth(0))
                }
            }
            context("When the burst is smaller than one packet") {
                // 100 kbps for 50 ms is 625 bytes, and a packet is 1000 bytes.
                nackHandler =
                    createNackHandler(maxRetransmissionBitrate = Bandwidth(100_000), retransmissionBurst = 50.ms)
                fillCache()
                clock.elapse(100.ms)
                nackSsrc(1)
                should("hold at least one packet") {
                    retransmitted.size shouldBe 1
                }
            }
        }
        context("With a retransmission budget in the configuration") {
            withNewConfig(
                """
                jmt.nack.max-retransmission-bitrate = 8 mbps
                jmt.nack.retransmission-burst = 500 ms
                """.trimIndent()
            ) {
                nackHandler = createNackHandlerFromConfig()
            }
            fillCache()
            clock.elapse(100.ms)
            nackEverything()
            should("apply the budget (1 MB/s for 500 ms is 500 packets)") {
                retransmitted.size shouldBe 500
            }
        }
        context("With a minimum resend interval of 50 ms in the configuration") {
            // The budget is large, so that it does not limit the retransmissions of this test.
            withNewConfig(
                """
                jmt.nack.max-retransmission-bitrate = 800 mbps
                jmt.nack.min-resend-interval = 50 ms
                """.trimIndent()
            ) {
                nackHandler = createNackHandlerFromConfig()
            }
            fillCache()
            clock.elapse(100.ms)
            nackSsrc(1)
            retransmitted.size shouldBe packetsPerSsrc
            context("A repeated NACK for packets that were retransmitted 40 ms ago") {
                retransmitted.clear()
                clock.elapse(40.ms)
                nackSsrc(1)
                should("not retransmit them") {
                    retransmitted.size shouldBe 0
                }
            }
            context("A repeated NACK for packets that were retransmitted 60 ms ago") {
                retransmitted.clear()
                clock.elapse(60.ms)
                nackSsrc(1)
                should("retransmit them") {
                    retransmitted.size shouldBe packetsPerSsrc
                }
            }
        }
    }

    /** Create a [NackHandler] that reads its settings from the configuration, and has no RTT. */
    private fun createNackHandlerFromConfig() = NackHandler(
        packetCache,
        object : PacketHandler {
            override fun processPacket(packetInfo: PacketInfo) {
                retransmitted.add(packetInfo)
            }
        },
        StdoutLogger(),
        clock
    )

    private fun videoPacket(ssrc: Long, seq: Int): VideoRtpPacket {
        val length = PACKET_LENGTH
        return VideoRtpPacket(ByteArray(length).also { it[0] = 0x80.toByte() }, 0, length).apply {
            this.ssrc = ssrc
            this.sequenceNumber = seq
        }
    }

    companion object {
        private const val PACKET_LENGTH = 1000
    }
}
