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

import io.kotlintest.IsolationMode
import io.kotlintest.matchers.doubles.plusOrMinus
import io.kotlintest.matchers.withClue
import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec
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
private val JitterPacketInfos = listOf(
    createJitterPacketInfo(0, 0, 10, 0.0),
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

class JitterStatsTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    init {
        "Jitter" {
            val jitterStats = JitterStats()
            should("update correctly") {
                // Handled separately since we want to verify the jitter at each
                // step along the way
                JitterPacketInfos.forEachIndexed { index, jitterPacketInfo ->
                    jitterStats.addPacket(
                        jitterPacketInfo.statPacketInfo.sentTimeMs,
                        jitterPacketInfo.statPacketInfo.packetInfo.receivedTime
                    )
                    withClue("After adding packet $index") {
                        jitterStats.jitter shouldBe (jitterPacketInfo.expectedJitter plusOrMinus .001)
                    }
                }
            }
        }
    }
}
