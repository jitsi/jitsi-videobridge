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
import java.time.Duration

class RtcpFbCcfbPacketTest : ShouldSpec() {
    init {
        context("An empty CCFB") {
            val builder = RtcpFbCcfbPacketBuilder(RtcpHeaderBuilder(senderSsrc = 0xdeadbeef), listOf(), 100_000)
            val packet = builder.build()
            packet.packetLength shouldBe
                4 + // RTCP header
                4 + // sender ssrc
                4 // timestamp
            packet.senderSsrc shouldBe 0xdeadbeef
            packet.reportTimestampCompactNtp shouldBe 100_000
            packet.packets shouldBe listOf()
        }

        context("A CCFB with two ssrcs, one packet each") {
            val packets = listOf<PacketInfo>(
                ReceivedPacketInfo(ssrc = 1, sequenceNumber = 1),
                ReceivedPacketInfo(ssrc = 2, sequenceNumber = 1)
            )
            val builder = RtcpFbCcfbPacketBuilder(RtcpHeaderBuilder(), packets, 1)
            val packet = builder.build()
            packet.packetLength shouldBe
                4 + // RTCP header
                4 + // sender ssrc
                2 * (
                    8 + // per ssrc header
                        1 * 2 + // packet info
                        2 // padding
                    ) +
                4 // timestamp
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
            val builder = RtcpFbCcfbPacketBuilder(RtcpHeaderBuilder(), packets, 1)
            val packet = builder.build()
            packet.packetLength shouldBe
                4 + // RTCP header
                4 + // sender ssrc
                2 * (
                    8 + // per ssrc header
                        2 * 2 // packet info
                    ) +
                4 // timestamp
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
            val builder = RtcpFbCcfbPacketBuilder(RtcpHeaderBuilder(), packets, 1)
            val packet = builder.build()
            packet.packetLength shouldBe
                4 + // RTCP header
                4 + // sender ssrc
                1 * (
                    8 + // per ssrc header
                        4 * 2 // packet info
                    ) +
                4 // timestamp
            packet.reportTimestampCompactNtp shouldBe 1
            packet.packets.shouldMatch(packets)
        }

        context("The wire format of RFC 8888 section 3.1") {
            // One stream, three reports starting at sequence number 0xFFFE: received as ECT(1) half a second before
            // the report timestamp, not received, and received as CE 1/1024 s before it.
            val wire = byteArrayOf(
                0x8B.toByte(), 0xCD.toByte(), 0x00, 0x06, // V=2, FMT=11, PT=205, length
                0x01, 0x02, 0x03, 0x04, // SSRC of RTCP packet sender
                0x0A, 0x0B, 0x0C, 0x0D, // SSRC of 1st RTP stream
                0xFF.toByte(), 0xFE.toByte(), 0x00, 0x03, // begin_seq, num_reports
                0xA2.toByte(), 0x00, 0x00, 0x00, // R=1 ECN=01 ATO=512; R=0
                0xE0.toByte(), 0x01, 0x00, 0x00, // R=1 ECN=11 ATO=1; padding
                0x00, 0x01, 0x00, 0x00 // report timestamp
            )
            val packets = listOf(
                ReceivedPacketInfo(
                    ssrc = 0x0A0B0C0D,
                    sequenceNumber = 0xFFFE,
                    arrivalTimeOffset = 500.ms,
                    ecn = EcnMarking.kEct1
                ),
                UnreceivedPacketInfo(ssrc = 0x0A0B0C0D, sequenceNumber = 0xFFFF),
                ReceivedPacketInfo(
                    ssrc = 0x0A0B0C0D,
                    sequenceNumber = 0,
                    arrivalTimeOffset = Duration.ofNanos(976_563),
                    ecn = EcnMarking.kCe
                )
            )

            should("be what the builder writes") {
                val built = RtcpFbCcfbPacketBuilder(
                    RtcpHeaderBuilder(senderSsrc = 0x01020304),
                    packets,
                    0x10000
                ).build()
                built.packetLength shouldBe wire.size
                built.buffer.copyOfRange(built.offset, built.offset + built.packetLength).toList() shouldBe
                    wire.toList()
            }
            should("be parsed") {
                val packet = RtcpFbCcfbPacket(wire, 0, wire.size)
                packet.senderSsrc shouldBe 0x01020304
                packet.reportTimestampCompactNtp shouldBe 0x10000
                packet.packets.shouldMatch(packets)
            }
            should("be parsed when it is not at the start of the buffer") {
                val buffer = ByteArray(3) { 0x55 } + wire + ByteArray(5) { 0x55 }
                val packet = RtcpFbCcfbPacket(buffer, 3, wire.size)
                packet.senderSsrc shouldBe 0x01020304
                packet.reportTimestampCompactNtp shouldBe 0x10000
                packet.packets.shouldMatch(packets)
            }
        }

        context("A CCFB with ECN marks") {
            val packets = listOf<PacketInfo>(
                ReceivedPacketInfo(ssrc = 1, sequenceNumber = 1, arrivalTimeOffset = 1.ms, ecn = EcnMarking.kEct1),
                UnreceivedPacketInfo(ssrc = 1, sequenceNumber = 2),
                UnreceivedPacketInfo(ssrc = 1, sequenceNumber = 3),
                ReceivedPacketInfo(ssrc = 1, sequenceNumber = 4, arrivalTimeOffset = 2.ms, ecn = EcnMarking.kCe)
            )
            val builder = RtcpFbCcfbPacketBuilder(RtcpHeaderBuilder(), packets, 1)
            val packet = builder.build()
            packet.packetLength shouldBe
                4 + // RTCP header
                4 + // sender ssrc
                1 * (
                    8 + // per ssrc header
                        4 * 2 // packet info
                    ) +
                4 // timestamp
            packet.reportTimestampCompactNtp shouldBe 1
            packet.packets.shouldMatch(packets)
        }
    }
}

class PacketInfoMatcher(private val expected: PacketInfo) : Matcher<PacketInfo> {
    override fun test(value: PacketInfo): MatcherResult = when (expected) {
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

fun match(expected: PacketInfo) = PacketInfoMatcher(expected)

fun List<PacketInfo>.shouldMatch(expected: List<PacketInfo>) =
    this.shouldMatchEach(expected.map { { p -> match(it).test(p) } })
