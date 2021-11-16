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

package org.jitsi.nlj.transform.node.incoming

import io.kotest.core.spec.IsolationMode
import io.kotest.matchers.shouldBe
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldNotBe
import org.jitsi.nlj.PacketInfo
import org.jitsi.rtp.rtp.RtpPacket

private data class StatPacketInfo(
    val packetInfo: PacketInfo,
    val sentTimeMs: Long
)

private data class JitterPacketInfo(
    val statPacketInfo: StatPacketInfo,
    val expectedJitter: Double
)

private fun createStatPacketInfo(seqNum: Int, sentTime: Long, receivedTime: Long): StatPacketInfo {
    val packetInfo = PacketInfo(RtpPacket(ByteArray(50), 0, 50))
    packetInfo.packetAs<RtpPacket>().sequenceNumber = seqNum
    packetInfo.receivedTime = receivedTime
    return StatPacketInfo(packetInfo, sentTime)
}

private fun createJitterPacketInfo(
    seqNum: Int,
    sentTime: Long,
    receivedTime: Long,
    expectedJitter: Double
): JitterPacketInfo {
    val statPacketInfo = createStatPacketInfo(seqNum, sentTime, receivedTime)
    return JitterPacketInfo(statPacketInfo, expectedJitter)
}

// Values taken from this example: http://toncar.cz/Tutorials/VoIP/VoIP_Basics_Jitter.html
private val initialJitterPacketInfo = createJitterPacketInfo(0, 0, 10, 0.0)
private val JitterPacketInfos = listOf(
    createJitterPacketInfo(1, 20, 30, 0.0),
    createJitterPacketInfo(2, 40, 49, .0625),
    createJitterPacketInfo(3, 60, 74, .3711),
    createJitterPacketInfo(4, 80, 90, .5979),
    createJitterPacketInfo(5, 100, 111, .6230),
    createJitterPacketInfo(6, 120, 139, 1.0841),
    createJitterPacketInfo(7, 140, 150, 1.5788),
    createJitterPacketInfo(8, 160, 170, 1.4802),
    createJitterPacketInfo(9, 180, 191, 1.4501),
    createJitterPacketInfo(10, 200, 210, 1.4220),
    createJitterPacketInfo(11, 220, 229, 1.3956),
    createJitterPacketInfo(12, 240, 250, 1.3709),
    createJitterPacketInfo(13, 260, 271, 1.3477)
)

internal class IncomingSsrcStatsTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    init {
        context("Expected packet count") {
            should("Handle cases with no rollover") {
                IncomingSsrcStats.calculateExpectedPacketCount(
                    0,
                    1,
                    0,
                    10
                ) shouldBe 10
            }
            should("handle cases with 1 rollover") {
                IncomingSsrcStats.calculateExpectedPacketCount(
                    0,
                    65530,
                    1,
                    10
                ) shouldBe 17
            }
            should("handle cases with > 1 rollover") {
                // Same as the scenario above but with another rollover
                IncomingSsrcStats.calculateExpectedPacketCount(
                    0,
                    65530,
                    2,
                    10
                ) shouldBe 17 + 65536L
            }
        }
        context("When receiving a series of packets with loss") {
            // 17 packets between base and max sequence number, 6 packets lost
            val packetSequence = listOf(
                createStatPacketInfo(0, 0, 0),
                createStatPacketInfo(1, 0, 0),
                createStatPacketInfo(3, 0, 0),
                createStatPacketInfo(5, 0, 0),
                createStatPacketInfo(6, 0, 0),
                createStatPacketInfo(7, 0, 0),
                createStatPacketInfo(8, 0, 0),
                createStatPacketInfo(9, 0, 0),
                createStatPacketInfo(11, 0, 0),
                createStatPacketInfo(13, 0, 0),
                createStatPacketInfo(16, 0, 0)
            )
            val streamStatistics = IncomingSsrcStats(
                123L,
                packetSequence.first().packetInfo.packetAs<RtpPacket>().sequenceNumber
            )
            packetSequence.forEach {
                streamStatistics.packetReceived(
                    it.packetInfo.packetAs(),
                    it.sentTimeMs,
                    it.packetInfo.receivedTime
                )
            }
            val statSnapshot = streamStatistics.getSnapshotIfActive()
            context("expected packets") {
                should("be calculated correctly") {
                    statSnapshot?.numExpectedPackets shouldBe 17
                }
            }
            context("cumulative lost") {
                should("be calculated correctly") {
                    statSnapshot?.cumulativePacketsLost shouldBe 6
                }
            }
            context("querying a second time with no update") {
                should("be null") {
                    streamStatistics.getSnapshotIfActive() shouldBe null
                }
            }

            val packetSequence2 = listOf(
                createStatPacketInfo(17, 0, 0)
            )
            packetSequence2.forEach {
                streamStatistics.packetReceived(
                    it.packetInfo.packetAs<RtpPacket>(),
                    it.sentTimeMs,
                    it.packetInfo.receivedTime
                )
            }

            context("querying after a new update") {
                should("not be null") {
                    streamStatistics.getSnapshotIfActive() shouldNotBe null
                }
            }
        }
    }
}
