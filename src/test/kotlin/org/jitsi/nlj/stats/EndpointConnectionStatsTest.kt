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

package org.jitsi.nlj.stats

import io.kotest.core.spec.IsolationMode
import io.kotest.matchers.shouldBe
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.mockk.every
import io.mockk.mockk
import java.time.Duration
import org.jitsi.nlj.resources.logging.StdoutLogger
import org.jitsi.test.time.FakeClock
import org.jitsi.nlj.test_utils.timeline
import org.jitsi.rtp.rtcp.RtcpReportBlock
import org.jitsi.rtp.rtcp.RtcpRrPacket
import org.jitsi.rtp.rtcp.RtcpSrPacket
import org.jitsi.utils.ms

class EndpointConnectionStatsTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    private val clock: FakeClock = FakeClock()

    private var mostRecentPublishedRtt: Double = -1.0
    private var numRttUpdates: Int = 0
    private val stats = EndpointConnectionStats(StdoutLogger(), clock).also {
        it.addListener(object : EndpointConnectionStats.EndpointConnectionStatsListener {
            override fun onRttUpdate(newRttMs: Double) {
                mostRecentPublishedRtt = newRttMs
                numRttUpdates++
            }
        })
    }

    init {
        context("after an SR is sent") {
            context("with a non-zero timestamp") {
                val srPacket = createSrPacket(senderInfoTs = 100)
                stats.rtcpPacketSent(srPacket)

                context("and an RR is received which refers to it") {
                    clock.elapse(60.ms)

                    val rrPacket = createRrPacket(lastSrTimestamp = 100, delaySinceLastSr = 50.ms)

                    stats.rtcpPacketReceived(rrPacket, clock.instant().toEpochMilli())
                    context("the rtt") {
                        should("be updated correctly") {
                            mostRecentPublishedRtt shouldBe(10.0 plusOrMinus .1)
                        }
                    }
                }
            }
            context("with a timestamp of 0") {
                val srPacket = createSrPacket(senderInfoTs = 0)
                stats.rtcpPacketSent(srPacket)

                context("and an RR is received which refers to it") {
                    context("with a non-zero delaySinceLastSr") {
                        val rrPacket = createRrPacket(lastSrTimestamp = 0, delaySinceLastSr = 50.ms)
                        clock.elapse(60.ms)

                        stats.rtcpPacketReceived(rrPacket, clock.instant().toEpochMilli())
                        context("the rtt") {
                            should("be updated correctly") {
                                mostRecentPublishedRtt shouldBe(10.0.plusOrMinus(.1))
                            }
                        }
                    }
                    context("with a delaySinceLastSr value of 0") {
                        val rrPacket = createRrPacket(lastSrTimestamp = 0, delaySinceLastSr = 0.ms)

                        clock.elapse(60.ms)

                        stats.rtcpPacketReceived(rrPacket, clock.instant().toEpochMilli())

                        // This case is indistinguishable from no SR being received
                        context("the rtt") {
                            should("not have been updated") {
                                numRttUpdates shouldBe 0
                            }
                        }
                    }
                }
            }
        }
        context("when a report block is received for which we can't find an SR") {
            val rrPacket = createRrPacket(lastSrTimestamp = 100, delaySinceLastSr = 50.ms)

            stats.rtcpPacketReceived(rrPacket, clock.instant().toEpochMilli())

            context("the rtt") {
                should("not have been updated") {
                    numRttUpdates shouldBe 0
                }
            }
        }
        context("when a report block is received with a lastSrTimestamp of 0 but a non-zero delaySinceLastSr") {
            timeline(clock) {
                run { stats.rtcpPacketSent(createSrPacket(senderInfoTs = 0)) }
                elapse(60.ms)
                run {
                    stats.rtcpPacketReceived(
                        createRrPacket(lastSrTimestamp = 0, delaySinceLastSr = 50.ms),
                        clock.instant().toEpochMilli()
                    )
                }
            }.run()

            context("the rtt") {
                should("be updated correctly") {
                    mostRecentPublishedRtt shouldBe(10.0.plusOrMinus(.1))
                }
            }
        }
    }

    // Takes a Duration and converts it to 1/65536ths of a second
    private fun Duration.toDelaySinceLastSrFraction(): Long = (toNanos() * .000065536).toLong()

    private fun createSrPacket(senderInfoTs: Long): RtcpSrPacket {
        val mockSenderInfo = mockk<RtcpSrPacket.SenderInfo> {
            every { compactedNtpTimestamp } returns senderInfoTs
        }

        return mockk {
            every { senderSsrc } returns 1234L
            every { senderInfo } returns mockSenderInfo
        }
    }

    private fun createRrPacket(lastSrTimestamp: Long, delaySinceLastSr: Duration): RtcpRrPacket {
        val reportBlock = mockk<RtcpReportBlock> {
            every { ssrc } returns 1234L
            every { this@mockk.lastSrTimestamp } returns lastSrTimestamp
            every { this@mockk.delaySinceLastSr } returns delaySinceLastSr.toDelaySinceLastSrFraction()
        }

        return mockk {
            every { reportBlocks } returns listOf(reportBlock)
        }
    }
}
