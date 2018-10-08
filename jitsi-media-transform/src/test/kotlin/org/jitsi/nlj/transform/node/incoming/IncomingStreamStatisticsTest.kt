package org.jitsi.nlj.transform.node.incoming

import io.kotlintest.matchers.plusOrMinus
import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec

data class PacketInfo(
    val seqNum: Int,
    val sentTime: Int,
    val receivedTime: Int
)

data class JitterPacketInfo(
    val packetInfo: PacketInfo,
    val expectedJitter: Double
)

// Values taken from this example: http://toncar.cz/Tutorials/VoIP/VoIP_Basics_Jitter.html
val initialJitterPacketInfo = JitterPacketInfo(PacketInfo(0, 0, 10), 0.0)
val JitterPacketInfos = listOf(
    JitterPacketInfo(PacketInfo(1, 20, 30), 0.0),
    JitterPacketInfo(PacketInfo(2, 40, 49), .0625),
    JitterPacketInfo(PacketInfo(3, 60, 74), .3711),
    JitterPacketInfo(PacketInfo(4, 80, 90), .5979),
    JitterPacketInfo(PacketInfo(5, 100, 111), .6230),
    JitterPacketInfo(PacketInfo(6, 120, 139), 1.0841),
    JitterPacketInfo(PacketInfo(7, 140, 150), 1.5788),
    JitterPacketInfo(PacketInfo(8, 160, 170), 1.4802),
    JitterPacketInfo(PacketInfo(9, 180, 191), 1.4501),
    JitterPacketInfo(PacketInfo(10, 200, 210), 1.4220),
    JitterPacketInfo(PacketInfo(11, 220, 229), 	1.3956),
    JitterPacketInfo(PacketInfo(12, 240, 250), 1.3709),
    JitterPacketInfo(PacketInfo(13, 260, 271), 1.3477)
)

internal class IncomingStreamStatisticsTest : ShouldSpec() {
    override fun isInstancePerTest(): Boolean = true

    init {
        "Jitter" {
            should("update correctly") {
                // Handled separately since we want to verify the jitter at each
                // step along the way
                var jitter = 0.0
                var previousJitterPacketInfo = initialJitterPacketInfo
                JitterPacketInfos.forEach {
                    jitter = IncomingStreamStatistics.calculateJitter(
                        jitter,
                        previousJitterPacketInfo.packetInfo.sentTime,
                        previousJitterPacketInfo.packetInfo.receivedTime,
                        it.packetInfo.sentTime,
                        it.packetInfo.receivedTime
                    )
                    jitter shouldBe (it.expectedJitter plusOrMinus .001)
                    previousJitterPacketInfo = it
                }
            }
        }
        "Expected packet count" {
            should("Handle cases with no rollover") {
                IncomingStreamStatistics.calculateExpectedPacketCount(
                    0,
                    1,
                    0,
                    10
                ) shouldBe 10
            }
            should("handle cases with 1 rollover") {
                IncomingStreamStatistics.calculateExpectedPacketCount(
                    0,
                    65530,
                    1,
                    10
                ) shouldBe 17
            }
            should("handle cases with > 1 rollover") {
                // Same as the scenario above but with another rollover
                IncomingStreamStatistics.calculateExpectedPacketCount(
                    0,
                    65530,
                    2,
                    10
                ) shouldBe 17 + 65536
            }
        }
        "When receiving a series of packets with loss" {
            // 17 packets between base and max sequence number, 6 packets lost
            val packetSequence = listOf(
                PacketInfo(0, 0, 0),
                PacketInfo(1, 0, 0),
                PacketInfo(3, 0, 0),
                PacketInfo(5, 0, 0),
                PacketInfo(6, 0, 0),
                PacketInfo(7, 0, 0),
                PacketInfo(8, 0, 0),
                PacketInfo(9, 0, 0),
                PacketInfo(11, 0, 0),
                PacketInfo(13, 0, 0),
                PacketInfo(16, 0, 0)
            )
            val streamStatistics = IncomingStreamStatistics(123L, packetSequence.first().seqNum)
            packetSequence.forEach {
                streamStatistics.packetReceived(
                    it.seqNum,
                    it.sentTime,
                    it.receivedTime
                )
            }
            val statSnapshot = streamStatistics.getSnapshot()
            "expected packets" {
                should("be calculated correctly") {
                    statSnapshot.numExpectedPackets shouldBe 17
                }
            }
            "cumulative lost" {
                should("be calculated correctly") {
                    statSnapshot.cumulativePacketsLost shouldBe 6
                }
            }
        }
    }
}
