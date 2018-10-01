package org.jitsi.nlj.transform.node.incoming

import io.kotlintest.matchers.plusOrMinus
import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec

data class PacketInfo(
    val sentTime: Int,
    val receivedTime: Int,
    val expectedJitter: Double
)

// Values taken from this example: http://toncar.cz/Tutorials/VoIP/VoIP_Basics_Jitter.html
val initialPacketInfo = PacketInfo(0, 10, 0.0)
val packetInfos = listOf(
    PacketInfo(20, 30, 0.0),
    PacketInfo(40, 49, .0625),
    PacketInfo(60, 74, .3711),
    PacketInfo(80, 90, .5979),
    PacketInfo(100, 111, .6230),
    PacketInfo(120, 139, 1.0841),
    PacketInfo(140, 150, 1.5788),
    PacketInfo(160, 170, 1.4802),
    PacketInfo(180, 191, 1.4501),
    PacketInfo(200, 210, 1.4220),
    PacketInfo(220, 229, 	1.3956),
    PacketInfo(240, 250, 1.3709),
    PacketInfo(260, 271, 1.3477)
)

internal class StreamStatisticsTest : ShouldSpec() {
    init {
        "Jitter calculations" {
            "when receiving a sequence of packets" {
                var jitter = 0.0
                var previousPacketInfo = initialPacketInfo
                packetInfos.forEach {
                    jitter = StreamStatistics.calculateJitter(
                        jitter,
                        previousPacketInfo.sentTime,
                        previousPacketInfo.receivedTime,
                        it.sentTime,
                        it.receivedTime
                    )
                    jitter shouldBe (it.expectedJitter plusOrMinus .001)
                    previousPacketInfo = it
                }
            }
        }
    }
}
