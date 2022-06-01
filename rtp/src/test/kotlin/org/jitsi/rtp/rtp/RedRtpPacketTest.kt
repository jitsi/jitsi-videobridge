package org.jitsi.rtp.rtp

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeTypeOf
import org.jitsi.rtp.Packet
import org.jitsi.rtp.extensions.bytearray.byteArrayOf
import org.jitsi.test_helpers.matchers.haveSameFixedHeader
import org.jitsi.test_helpers.matchers.haveSamePayload

class RedRtpPacketTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode = IsolationMode.InstancePerLeaf

    init {
        context("Parsing block headers") {
            context("Primary") {
                val block = BlockHeader.parse(byteArrayOf(0x05), 0)
                block.shouldBeTypeOf<PrimaryBlockHeader>()
                block.pt shouldBe 5.toByte()
            }
            context("Redundancy") {
                val block = BlockHeader.parse(byteArrayOf(0x85, 0xff, 0xff, 0xff), 0)
                block.shouldBeTypeOf<RedundancyBlockHeader>()
                block.pt shouldBe 5.toByte()
                block.timestampOffset shouldBe 0x3fff
                block.length shouldBe 0x3ff
            }
        }
        context("Creating block headers") {
            context("Primary") {
                val buf = ByteArray(1)
                val blockHeader = PrimaryBlockHeader(111)
                blockHeader.write(buf, 0)

                val parsed = BlockHeader.parse(buf, 0)
                parsed.shouldBeTypeOf<PrimaryBlockHeader>()
                parsed.pt shouldBe 111.toByte()
            }
            context("Redundancy") {
                val buf = ByteArray(4)
                val blockHeader = RedundancyBlockHeader(111, 960, 55)
                blockHeader.write(buf, 0)

                val parsed = BlockHeader.parse(buf, 0)
                parsed.shouldBeTypeOf<RedundancyBlockHeader>()
                parsed.pt shouldBe 111.toByte()
                parsed.timestampOffset shouldBe 960
                parsed.length shouldBe 55
            }
            context("Redundancy with large timestamp diff") {
                shouldThrow<IllegalArgumentException> {
                    RedundancyBlockHeader(111, RedundancyBlockHeader.MAX_TIMESTAMP_OFFSET + 1, 55)
                }
            }
        }
        context("Parsing a RED packet with a single block") {
            val packet = RtpRedPacket(redPacketBytesSingleBlock.clone(), 0, redPacketBytesSingleBlock.size)
            packet.payloadType shouldBe 112
            packet.sequenceNumber shouldBe 0x7b2b
            packet.timestamp shouldBe 0x44b2836e
            packet.ssrc shouldBe 0x16493f2d
            packet.hasExtensions shouldBe true
            packet.getHeaderExtension(1).shouldHaveId1AndLen1()
            packet.payloadLength shouldBe 66

            context("Without reading the redundancy blocks") {
                packet.decapsulate(parseRedundancy = false)
                packet.payloadType shouldBe 111
                packet.sequenceNumber shouldBe 0x7b2b
                packet.timestamp shouldBe 0x44b2836e
                packet.ssrc shouldBe 0x16493f2d
                packet.hasExtensions shouldBe true
                packet.getHeaderExtension(1).shouldHaveId1AndLen1()
                packet.payloadLength shouldBe 65
            }
            context("And reading the redundancy blocks") {
                val redundantPackets = packet.decapsulate(parseRedundancy = true)
                redundantPackets.shouldBeEmpty()
                packet.payloadType shouldBe 111
                packet.sequenceNumber shouldBe 0x7b2b
                packet.timestamp shouldBe 0x44b2836e
                packet.ssrc shouldBe 0x16493f2d
                packet.hasExtensions shouldBe true
                packet.getHeaderExtension(1).shouldHaveId1AndLen1()
                packet.payloadLength shouldBe 65
            }
        }
        context("Parsing a RED packet with multiple blocks") {
            val packet = RtpRedPacket(redPacketBytes.clone(), 0, redPacketBytes.size)
            packet.payloadType shouldBe 112
            packet.sequenceNumber shouldBe 0x7b2b
            packet.timestamp shouldBe 0x44b2836e
            packet.ssrc shouldBe 0x16493f2d
            packet.hasExtensions shouldBe true
            packet.getHeaderExtension(1).shouldHaveId1AndLen1()
            packet.payloadLength shouldBe 4 + 4 + 1 + 65 + 68 + 62 // block headers + block lengths

            context("Without reading the redundancy blocks") {
                packet.decapsulate(parseRedundancy = false)
                packet.payloadType shouldBe 111
                packet.sequenceNumber shouldBe 0x7b2b
                packet.timestamp shouldBe 0x44b2836e
                packet.ssrc shouldBe 0x16493f2d
                packet.hasExtensions shouldBe true
                packet.getHeaderExtension(1).shouldHaveId1AndLen1()
                packet.payloadLength shouldBe 62
            }
            context("And reading the redundancy block") {
                // Make sure we test the case when the sequence numbers wrap
                packet.sequenceNumber = 1

                val redundancyPackets = packet.decapsulate(parseRedundancy = true)
                redundancyPackets.size shouldBe 2

                redundancyPackets.forEachIndexed { i, p ->
                    // Offset relative to the sequence number of the primary. The first redundancy packet has
                    // offset=-2, the second has previous has offset=-1
                    val offset = i - 2

                    p.payloadType shouldBe 111
                    p.sequenceNumber shouldBe ((1 + offset) and 0xffff)
                    p.timestamp shouldBe (0x44b2836e + offset * 960)
                    p.ssrc shouldBe packet.ssrc
                    p.hasExtensions shouldBe false
                }

                packet.payloadType shouldBe 111
                packet.sequenceNumber shouldBe 1
                packet.timestamp shouldBe 0x44b2836e
                packet.ssrc shouldBe 0x16493f2d
                packet.hasExtensions shouldBe true
                packet.getHeaderExtension(1).shouldHaveId1AndLen1()
                packet.payloadLength shouldBe 62
            }
        }
        context("Parsing a RED packet with short blocks") {
            val buffer = ByteArray(
                redPacketBytesShortBlocks.size +
                    RtpPacket.BYTES_TO_LEAVE_AT_START_OF_PACKET +
                    Packet.BYTES_TO_LEAVE_AT_END_OF_PACKET
            )
            redPacketBytesShortBlocks.copyInto(buffer, RtpPacket.BYTES_TO_LEAVE_AT_START_OF_PACKET)
            val packet = RtpRedPacket(
                buffer,
                RtpPacket.BYTES_TO_LEAVE_AT_START_OF_PACKET,
                redPacketBytesShortBlocks.size
            )
            packet.payloadType shouldBe 112
            packet.sequenceNumber shouldBe 35006
            packet.timestamp shouldBe 862774129L
            packet.ssrc shouldBe 624250097L
            packet.hasExtensions shouldBe true
            packet.getHeaderExtension(1).shouldHaveId1AndLen1()
            packet.payloadLength shouldBe 4 + 1 + 3 + 3 // block headers + block lengths

            context("Without reading the redundancy blocks") {
                packet.decapsulate(parseRedundancy = false)
                packet.payloadType shouldBe 111
                packet.sequenceNumber shouldBe 35006
                packet.timestamp shouldBe 862774129L
                packet.ssrc shouldBe 624250097L
                packet.hasExtensions shouldBe true
                packet.getHeaderExtension(1).shouldHaveId1AndLen1()
                packet.payloadLength shouldBe 3
            }
            context("And reading the redundancy block") {
                val redundancyPackets = packet.decapsulate(parseRedundancy = true)
                redundancyPackets.size shouldBe 1

                redundancyPackets[0].payloadType shouldBe 111
                redundancyPackets[0].sequenceNumber shouldBe 35005
                redundancyPackets[0].timestamp shouldBe 862774129L - 960L
                redundancyPackets[0].ssrc shouldBe 624250097L
                redundancyPackets[0].hasExtensions shouldBe false
                redundancyPackets[0].payloadLength shouldBe 3

                packet.payloadType shouldBe 111
                packet.sequenceNumber shouldBe 35006
                packet.timestamp shouldBe 862774129L
                packet.ssrc shouldBe 624250097L
                packet.hasExtensions shouldBe true
                packet.getHeaderExtension(1).shouldHaveId1AndLen1()
                packet.payloadLength shouldBe 3
            }
        }
        context("Parsing a RED packet with zero-length blocks") {
            val packet = RtpRedPacket(
                redPacketBytesZeroLengthBlocks.clone(),
                0,
                redPacketBytesZeroLengthBlocks.size
            )
            packet.payloadType shouldBe 112
            packet.sequenceNumber shouldBe 35006
            packet.timestamp shouldBe 862774129L
            packet.ssrc shouldBe 624250097L
            packet.hasExtensions shouldBe false
            packet.payloadLength shouldBe 4 + 1 // block headers

            context("Without reading the redundancy blocks") {
                packet.decapsulate(parseRedundancy = false)
                packet.payloadType shouldBe 0
                packet.sequenceNumber shouldBe 35006
                packet.timestamp shouldBe 862774129L
                packet.ssrc shouldBe 624250097L
                packet.hasExtensions shouldBe false
                packet.payloadLength shouldBe 0
            }
            context("And reading the redundancy block") {
                val redundancyPackets = packet.decapsulate(parseRedundancy = true)
                redundancyPackets.size shouldBe 1

                redundancyPackets[0].payloadType shouldBe 0
                redundancyPackets[0].sequenceNumber shouldBe 35005
                redundancyPackets[0].timestamp shouldBe 862774129L
                redundancyPackets[0].ssrc shouldBe 624250097L
                redundancyPackets[0].hasExtensions shouldBe false
                redundancyPackets[0].payloadLength shouldBe 0

                packet.payloadType shouldBe 0
                packet.sequenceNumber shouldBe 35006
                packet.timestamp shouldBe 862774129L
                packet.ssrc shouldBe 624250097L
                packet.hasExtensions shouldBe false
                packet.payloadLength shouldBe 0
            }
        }
        context("Parsing a truncated RED packet") {
            context("Without buffer padding") {
                val packet = RtpRedPacket(
                    redPacketBytesZeroLengthBlocks.clone(),
                    0,
                    redPacketBytesZeroLengthBlocks.size - 1
                )

                should("throw IllegalArgumentException") {
                    shouldThrow<IllegalArgumentException> {
                        packet.decapsulate(parseRedundancy = false)
                    }
                    shouldThrow<IllegalArgumentException> {
                        packet.decapsulate(parseRedundancy = true)
                    }
                }
            }
            context("With buffer padding") {
                val buffer = ByteArray(
                    redPacketBytesZeroLengthBlocks.size - 1 +
                        RtpPacket.BYTES_TO_LEAVE_AT_START_OF_PACKET +
                        Packet.BYTES_TO_LEAVE_AT_END_OF_PACKET
                )
                redPacketBytesZeroLengthBlocks.copyInto(
                    buffer,
                    RtpPacket.BYTES_TO_LEAVE_AT_START_OF_PACKET,
                    0,
                    redPacketBytesZeroLengthBlocks.size - 1
                )
                val packet = RtpRedPacket(
                    buffer,
                    RtpPacket.BYTES_TO_LEAVE_AT_START_OF_PACKET,
                    redPacketBytesZeroLengthBlocks.size - 1
                )

                should("throw IllegalArgumentException") {
                    shouldThrow<IllegalArgumentException> {
                        packet.decapsulate(parseRedundancy = false)
                    }
                    shouldThrow<IllegalArgumentException> {
                        packet.decapsulate(parseRedundancy = true)
                    }
                }
            }
            context("With a short buffer") {
                val buffer = ByteArray(redPacketBytesZeroLengthBlocks.size - 1)
                redPacketBytesZeroLengthBlocks.copyInto(buffer, 0, 0, redPacketBytesZeroLengthBlocks.size - 1)
                val packet = RtpRedPacket(buffer, 0, redPacketBytesZeroLengthBlocks.size - 1)

                should("throw IllegalArgumentException") {
                    shouldThrow<IllegalArgumentException> {
                        packet.decapsulate(parseRedundancy = false)
                    }
                    shouldThrow<IllegalArgumentException> {
                        packet.decapsulate(parseRedundancy = true)
                    }
                }
            }
        }
        context("Creating a RED packet without redundancy") {
            val rtpPacket = RtpPacket(redPacketBytesSingleBlock.clone(), 0, redPacketBytesSingleBlock.size).apply {
                payloadType = 111
            }

            val redPacket = RtpRedPacket.builder.build(112, rtpPacket, emptyList())
            redPacket.length shouldBe rtpPacket.length + 1
            redPacket.payloadType shouldBe 112

            redPacket.decapsulate(parseRedundancy = false)
            val reconstructed = RtpPacket(redPacket.buffer, redPacket.offset, redPacket.length)
            reconstructed should haveSameFixedHeader(rtpPacket)
            reconstructed should haveSamePayload(rtpPacket)
        }
        context("Creating a RED packet with redundancy") {
            val primary = RtpPacket(redPacketBytesSingleBlock.clone(), 0, redPacketBytesSingleBlock.size).apply {
                payloadType = 111
            }
            val redundancy1 = RtpPacket(redPacketBytesSingleBlock.clone(), 0, redPacketBytesSingleBlock.size).apply {
                payloadType = 111
                timestamp = primary.timestamp - 1920
                sequenceNumber = primary.sequenceNumber - 2
            }
            val redundancy2 = RtpPacket(redPacketBytesSingleBlock.clone(), 0, redPacketBytesSingleBlock.size).apply {
                payloadType = 111
                timestamp = primary.timestamp - 960
                sequenceNumber = primary.sequenceNumber - 1
            }

            val redPacket = RtpRedPacket.builder.build(112, primary, listOf(redundancy1, redundancy2))
            redPacket.length shouldBe primary.length + 1 + redundancy1.payloadLength + 4 + redundancy2.payloadLength + 4
            redPacket.payloadType shouldBe 112

            val reconstructedRedundancy = redPacket.decapsulate(parseRedundancy = true)
            val reconstructedPrimary = RtpPacket(redPacket.buffer, redPacket.offset, redPacket.length)

            reconstructedPrimary should haveSameFixedHeader(primary)
            reconstructedPrimary should haveSamePayload(primary)

            reconstructedRedundancy shouldHaveSize 2
            mapOf(
                redundancy1 to reconstructedRedundancy[0],
                redundancy2 to reconstructedRedundancy[1]
            ).forEach { (expected, reconstructed) ->

                reconstructed.hasPadding shouldBe expected.hasPadding
                reconstructed.hasExtensions shouldBe false
                reconstructed.csrcCount shouldBe 0
                reconstructed.payloadType shouldBe expected.payloadType
                reconstructed.sequenceNumber shouldBe expected.sequenceNumber
                reconstructed.ssrc shouldBe expected.ssrc
                reconstructed.timestamp shouldBe expected.timestamp

                reconstructed should haveSamePayload(expected)
            }
        }
        context("Creating a RED packet with a large timestamp delta") {
            val primary = RtpPacket(redPacketBytesSingleBlock.clone(), 0, redPacketBytesSingleBlock.size).apply {
                payloadType = 111
            }
            val redundancy1 = RtpPacket(redPacketBytesSingleBlock.clone(), 0, redPacketBytesSingleBlock.size).apply {
                payloadType = 111
                timestamp = primary.timestamp - RedundancyBlockHeader.MAX_TIMESTAMP_OFFSET - 1
                sequenceNumber = primary.sequenceNumber - 2
            }

            shouldThrow<IllegalArgumentException> { RtpRedPacket.builder.build(112, primary, listOf(redundancy1)) }
        }
    }
}

private fun RtpPacket.HeaderExtension?.shouldHaveId1AndLen1() {
    this shouldNotBe null
    this as RtpPacket.HeaderExtension
    id shouldBe 1
    dataLengthBytes shouldBe 1
}

private val redPacketBytes = byteArrayOf(
    // RTP Header. PT=112, seq=0x7b2b
    0x90, 0x70, 0x7b, 0x2b,
    // TS
    0x44, 0xb2, 0x83, 0x6e,
    // SSRC
    0x16, 0x49, 0x3f, 0x2d,
    // Extension
    0xbe, 0xde, 0x00, 0x01,
    // ID=1, value=0x3d
    0x10, 0x3d, 0x00, 0x00,

    // RTP Payload
    // RED Header Block 1: follow=true, pt=111, ts_offset=1920, length=65
    0xef, 0x1e, 0x00, 0x41,
    // RED Header Block 2: follow=true, pt=111, ts_offset=960, length=68
    0xef, 0x0f, 0x00, 0x44,
    // RED Header Block 3: follow=false, pt=111 (remaining length=62)
    0x6f,
    // RED Block 1 (length=65):
    0x68, 0x2f, 0x3b, 0x25,
    0xab, 0x1a, 0x72, 0xfb,
    0x75, 0x2d, 0xf0, 0x9b,
    0xa2, 0xa3, 0xfc, 0x20,
    0x51, 0xf7, 0x9c, 0xe8,
    0x75, 0xe9, 0x02, 0xd6,
    0xc1, 0xe4, 0xa1, 0x51,
    0x02, 0x00, 0x59, 0x55,
    0x04, 0x56, 0x5e, 0xed,
    0x31, 0x55, 0xb5, 0x04,
    0x9d, 0xf6, 0x1c, 0x40,
    0x7b, 0xb7, 0x00, 0x0c,
    0xd9, 0x7b, 0x5d, 0x13,
    0x4c, 0xeb, 0x7d, 0xf1,
    0x74, 0xf8, 0xd5, 0xb9,
    0x07, 0xda, 0x18, 0x19,
    0x92,
    // RED Block 2 (length=68):
    0x68, 0x2f, 0x3b, 0x08,
    0xa5, 0x08, 0xb1, 0x93,
    0x5b, 0x65, 0x41, 0x30,
    0x55, 0xcd, 0xb7, 0xca,
    0xd2, 0x66, 0xc8, 0x53,
    0x18, 0x95, 0x9d, 0x49,
    0x81, 0x0a, 0xba, 0x67,
    0xf2, 0x42, 0xe3, 0xad,
    0x26, 0x73, 0x14, 0x52,
    0x62, 0x03, 0x3d, 0x1e,
    0xdd, 0x58, 0x44, 0x4e,
    0xc9, 0x56, 0x2f, 0x77,
    0xf9, 0x64, 0xf2, 0x6e,
    0x8c, 0x39, 0x35, 0x1a,
    0xba, 0x10, 0xe2, 0x85,
    0x1f, 0x28, 0x87, 0xb9,
    0x02, 0xa4, 0x68, 0x6b,
    // RED Block 3 (length is the remainder, 62):
    0x68, 0x2f, 0x3b, 0x2f,
    0xb9, 0x5f, 0xc4, 0x65,
    0x67, 0x7f, 0x5e, 0xa0,
    0x46, 0xb0, 0x93, 0xf5,
    0xc3, 0x5c, 0x69, 0xd7,
    0x1f, 0x75, 0xe9, 0xef,
    0xd1, 0x94, 0xdc, 0x47,
    0x24, 0x13, 0xf8, 0x6e,
    0xda, 0x18, 0xf3, 0x72,
    0x5b, 0x73, 0x03, 0x8c,
    0xc6, 0x2c, 0x7a, 0xab,
    0x38, 0xad, 0x87, 0x6c,
    0x9b, 0x08, 0x85, 0x08,
    0x8f, 0xa3, 0x72, 0xf1,
    0xf4, 0x7d, 0x88, 0xc3,
    0x13, 0x84
)

private val redPacketBytesSingleBlock = byteArrayOf(
    // RTP Header. PT=112, seq=0x7b2b
    0x90, 0x70, 0x7b, 0x2b,
    // TS
    0x44, 0xb2, 0x83, 0x6e,
    // SSRC
    0x16, 0x49, 0x3f, 0x2d,
    // Extension
    0xbe, 0xde, 0x00, 0x01,
    // ID=1, value=0x3d
    0x10, 0x3d, 0x00, 0x00,

    // RTP Payload
    // RED Header Block 1: follow=false, pt=111
    0x6f,
    // RED Block 1 (length=65):
    0x68, 0x2f, 0x3b, 0x25,
    0xab, 0x1a, 0x72, 0xfb,
    0x75, 0x2d, 0xf0, 0x9b,
    0xa2, 0xa3, 0xfc, 0x20,
    0x51, 0xf7, 0x9c, 0xe8,
    0x75, 0xe9, 0x02, 0xd6,
    0xc1, 0xe4, 0xa1, 0x51,
    0x02, 0x00, 0x59, 0x55,
    0x04, 0x56, 0x5e, 0xed,
    0x31, 0x55, 0xb5, 0x04,
    0x9d, 0xf6, 0x1c, 0x40,
    0x7b, 0xb7, 0x00, 0x0c,
    0xd9, 0x7b, 0x5d, 0x13,
    0x4c, 0xeb, 0x7d, 0xf1,
    0x74, 0xf8, 0xd5, 0xb9,
    0x07, 0xda, 0x18, 0x19,
    0x92
)

private val redPacketBytesShortBlocks = byteArrayOf(
    // RTP Header. PT=112, seq=35006
    0x90, 0x70, 0x88, 0xBE,
    // TS
    0x33, 0x6C, 0xE3, 0x71,
    // SSRC
    0x25, 0x35, 0x4C, 0xF1,
    // Extension
    0xBE, 0xDE, 0x00, 0x02,
    // ID=5, val=0xEA4D; ID=1, val=0xFF
    0x51, 0xEA, 0x4D, 0x10, 0xFF, 0x00, 0x00, 0x00,
    // RED Header block 1: follow=true, pt=111, ts_offset=960, length=3
    0xEF, 0x0F, 0x00, 0x03,
    // RED Header block 2: follow=false, pt=111 (remaining length=3)
    0x6F,
    // Block 1
    0xD8, 0xFF, 0xFE,
    // Block 2
    0xD8, 0xFF, 0xFE
)

private val redPacketBytesZeroLengthBlocks = byteArrayOf(
    // RTP Header. PT=112, seq=35006
    0x80, 0x70, 0x88, 0xBE,
    // TS
    0x33, 0x6C, 0xE3, 0x71,
    // SSRC
    0x25, 0x35, 0x4C, 0xF1,
    // RED Header block 1: follow=true, pt=0, ts_offset=0, length=0
    0x80, 0x00, 0x00, 0x00,
    // RED Header block 2: follow=false, pt=0 (remaining length=0)
    0x00
)
