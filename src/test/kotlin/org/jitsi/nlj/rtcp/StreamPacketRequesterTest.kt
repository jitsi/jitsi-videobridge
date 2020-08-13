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

package org.jitsi.nlj.rtcp

import io.kotest.core.spec.IsolationMode
import io.kotest.matchers.shouldBe
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.mockk.spyk
import org.jitsi.nlj.resources.logging.StdoutLogger
import org.jitsi.nlj.test_utils.FakeScheduledExecutorService
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.RtcpFbNackPacket

class StreamPacketRequesterTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    private val scheduler: FakeScheduledExecutorService = spyk()

    private val nackPacketsSent = mutableListOf<RtcpPacket>()
    private fun rtcpSender(rtcpPacket: RtcpPacket) {
        nackPacketsSent.add(rtcpPacket)
    }

    private val streamPacketRequester = RetransmissionRequester.StreamPacketRequester(
            123L, scheduler, scheduler.clock, ::rtcpSender, StdoutLogger()
    )

    init {
        context("receiving the first packet") {
            streamPacketRequester.packetReceived(1)
            should("not schedule any work") {
                scheduler.numPendingJobs() shouldBe 0
            }
            context("and then a packet sequentially after that") {
                streamPacketRequester.packetReceived(2)
                should("not schedule any work") {
                    scheduler.numPendingJobs() shouldBe 0
                }
            }
            context("and then a packet with a gap") {
                streamPacketRequester.packetReceived(3)
                should("schedule work") {
                    scheduler.numPendingJobs() shouldBe 1
                }
                context("the scheduled work") {
                    scheduler.runOne()
                    should("send a nack") {
                        nackPacketsSent shouldHaveSize 1
                        val nackPacket = nackPacketsSent.first() as RtcpFbNackPacket
                        nackPacket.missingSeqNums shouldContainExactly listOf(2)
                    }
                }
            }
        }
        context("a nack for a specific packet") {
            streamPacketRequester.packetReceived(1)
            streamPacketRequester.packetReceived(3)
            should("be resent 10 times") {
                repeat(10) { scheduler.runOne() }
                nackPacketsSent shouldHaveSize 10
                // It shouldn't reschedule the job after the 10th time
                scheduler.numPendingJobs() shouldBe 0
            }
            should("not be sent after the packet has been received") {
                // Have it send a few nacks
                repeat(3) { scheduler.runOne() }
                // Now have the missing packet come in
                streamPacketRequester.packetReceived(2)
                scheduler.numPendingJobs() shouldBe 0
            }
        }
        context("multiple missing packets") {
            context("all at once") {
                streamPacketRequester.packetReceived(1)
                streamPacketRequester.packetReceived(10)
                should("all be nacked at once") {
                    scheduler.runOne()
                    nackPacketsSent shouldHaveSize 1
                    val nackPacket = nackPacketsSent.first() as RtcpFbNackPacket
                    nackPacket.missingSeqNums shouldContainExactly (2..9).toSortedSet()
                }
                should("all be nacked 10 times") {
                    repeat(10) { scheduler.runOne() }
                    nackPacketsSent shouldHaveSize 10
                    nackPacketsSent.forEach { packet ->
                        packet as RtcpFbNackPacket
                        packet.missingSeqNums shouldContainExactly (2..9).toSortedSet()
                    }
                    scheduler.numPendingJobs() shouldBe 0
                }
            }
            context("spread out over time") {
                streamPacketRequester.packetReceived(1)
                streamPacketRequester.packetReceived(3)
                repeat(5) { scheduler.runOne() }
                streamPacketRequester.packetReceived(5)
                repeat(5) { scheduler.runOne() }
                streamPacketRequester.packetReceived(7)
                repeat(10) { scheduler.runOne() }

                should("all be nacked 10 times each over time") {
                    for (missingSeqNum in listOf(2, 4, 6)) {
                        val numNacksForSeqNum = nackPacketsSent.map { it as RtcpFbNackPacket }
                                .filter { it.missingSeqNums.contains(missingSeqNum) }
                                .size
                        numNacksForSeqNum shouldBe 10
                    }
                    scheduler.numPendingJobs() shouldBe 0
                }
            }
        }
        context("a packet jump of larger than the max") {
            streamPacketRequester.packetReceived(1)
            streamPacketRequester.packetReceived(3)
            should("reset and clear all pending nacks") {
                scheduler.numPendingJobs() shouldBe 1
                streamPacketRequester.packetReceived(3000)
                scheduler.numPendingJobs() shouldBe 0
            }
        }
    }
}
