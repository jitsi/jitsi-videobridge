package org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.ccfb

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.Matcher
import io.kotest.matchers.MatcherResult
import io.kotest.matchers.collections.shouldMatchEach
import io.kotest.matchers.shouldBe
import org.jitsi.rtp.rtcp.RtcpHeaderBuilder
import org.jitsi.utils.abs
import org.jitsi.utils.div
import org.jitsi.utils.isFinite
import org.jitsi.utils.isInfinite
import org.jitsi.utils.ms
import org.jitsi.utils.secs

class RtcpFbCcfbPacketTest : ShouldSpec() {
    init {
        context("An empty CCFB") {
            val builder = RtcpFbCcfbPacketBuilder(RtcpHeaderBuilder(), 0xdeadbeef, listOf(), 100_000)
            val packet = builder.build()
            packet.packetLength shouldBe
                4 + // RTCP header
                4 + // common FB header
                4 + // sender ssrc
                4 // timestamp
            packet.mediaSourceSsrc shouldBe 0xdeadbeef
            packet.reportTimestampCompactNtp shouldBe 100_000
            packet.packets shouldBe listOf()
        }

        context("A CCFB with two ssrcs, one packet each") {
            val packets = listOf<PacketInfo>(
                ReceivedPacketInfo(ssrc = 1, sequenceNumber = 1),
                ReceivedPacketInfo(ssrc = 2, sequenceNumber = 1)
            )
            val builder = RtcpFbCcfbPacketBuilder(RtcpHeaderBuilder(), 1, packets, 1)
            val packet = builder.build()
            packet.packetLength shouldBe
                4 + // RTCP header
                4 + // common FB header
                4 + // sender ssrc
                2 * (
                    8 + // per ssrc header
                        1 * 2 + // packet info
                        2 // padding
                    ) +
                4 // timestamp
            packet.mediaSourceSsrc shouldBe 1
            packet.reportTimestampCompactNtp shouldBe 1
            packet.packets.shouldMatch(packets)
        }

        context("A CCFB with two ssrcs, two packets each") {
            val packets = listOf<PacketInfo>(
                ReceivedPacketInfo(ssrc = 1, sequenceNumber = 1),
                ReceivedPacketInfo(ssrc = 1, sequenceNumber = 2),
                ReceivedPacketInfo(ssrc = 2, sequenceNumber = 1),
                ReceivedPacketInfo(ssrc = 2, sequenceNumber = 2)

            )
            val builder = RtcpFbCcfbPacketBuilder(RtcpHeaderBuilder(), 1, packets, 1)
            val packet = builder.build()
            packet.packetLength shouldBe
                4 + // RTCP header
                4 + // common FB header
                4 + // sender ssrc
                2 * (
                    8 + // per ssrc header
                        2 * 2 // packet info
                    ) +
                4 // timestamp
            packet.mediaSourceSsrc shouldBe 1
            packet.reportTimestampCompactNtp shouldBe 1
            packet.packets.shouldMatch(packets)
        }

        context("A CCFB with missing packets") {
            val packets = listOf<PacketInfo>(
                ReceivedPacketInfo(ssrc = 1, sequenceNumber = 1, arrivalTimeOffset = 1.ms),
                UnreceivedPacketInfo(ssrc = 1, sequenceNumber = 2),
                UnreceivedPacketInfo(ssrc = 1, sequenceNumber = 3),
                ReceivedPacketInfo(ssrc = 1, sequenceNumber = 4, arrivalTimeOffset = 2.ms)
            )
            val builder = RtcpFbCcfbPacketBuilder(RtcpHeaderBuilder(), 1, packets, 1)
            val packet = builder.build()
            packet.packetLength shouldBe
                4 + // RTCP header
                4 + // common FB header
                4 + // sender ssrc
                1 * (
                    8 + // per ssrc header
                        4 * 2 // packet info
                    ) +
                4 // timestamp
            packet.mediaSourceSsrc shouldBe 1
            packet.reportTimestampCompactNtp shouldBe 1
            packet.packets.shouldMatch(packets)
        }

        context("A CCFB with ECN marks") {
            val packets = listOf<PacketInfo>(
                ReceivedPacketInfo(ssrc = 1, sequenceNumber = 1, arrivalTimeOffset = 1.ms, ecn = EcnMarking.kEct1),
                UnreceivedPacketInfo(ssrc = 1, sequenceNumber = 2),
                UnreceivedPacketInfo(ssrc = 1, sequenceNumber = 3),
                ReceivedPacketInfo(ssrc = 1, sequenceNumber = 4, arrivalTimeOffset = 2.ms, ecn = EcnMarking.kCe)
            )
            val builder = RtcpFbCcfbPacketBuilder(RtcpHeaderBuilder(), 1, packets, 1)
            val packet = builder.build()
            packet.packetLength shouldBe
                4 + // RTCP header
                4 + // common FB header
                4 + // sender ssrc
                1 * (
                    8 + // per ssrc header
                        4 * 2 // packet info
                    ) +
                4 // timestamp
            packet.mediaSourceSsrc shouldBe 1
            packet.reportTimestampCompactNtp shouldBe 1
            packet.packets.shouldMatch(packets)
        }
    }
}

class PacketInfoMatcher(private val expected: PacketInfo) : Matcher<PacketInfo> {
    override fun test(value: PacketInfo): MatcherResult {
        return when (expected) {
            is ReceivedPacketInfo -> if (value !is ReceivedPacketInfo) {
                MatcherResult(
                    false,
                    { "Value should be $expected but was $value" },
                    { "Value should not be $expected" }
                )
            } else {
                // PacketInfo is equal after serializing-deserializing if members are equal
                // except for arrival time offset that may differ because of conversion back and
                // forth to CompactNtp.

                val arrivalTimeOffsetEqual =
                    (
                        value.arrivalTimeOffset.isInfinite() && expected.arrivalTimeOffset.isInfinite() &&
                            value == expected
                        ) ||
                        (
                            value.arrivalTimeOffset.isFinite() && expected.arrivalTimeOffset.isFinite() &&
                                abs(value.arrivalTimeOffset - expected.arrivalTimeOffset) < 1.secs / 1024
                            )
                MatcherResult(
                    value.ssrc == expected.ssrc &&
                        value.sequenceNumber == expected.sequenceNumber &&
                        arrivalTimeOffsetEqual &&
                        value.ecn == expected.ecn,
                    { "Value should be $expected but was $value" },
                    { "Value should not be $expected" }
                )
            }

            is UnreceivedPacketInfo -> MatcherResult(
                value is UnreceivedPacketInfo &&
                    value.ssrc == expected.ssrc &&
                    value.sequenceNumber == expected.sequenceNumber,
                { "Value should be $expected but was $value" },
                { "Value should not be $expected" }
            )
        }
    }
}

fun match(expected: PacketInfo) = PacketInfoMatcher(expected)

fun List<PacketInfo>.shouldMatch(expected: List<PacketInfo>) =
    this.shouldMatchEach(expected.map { { p -> match(it).test(p) } })
