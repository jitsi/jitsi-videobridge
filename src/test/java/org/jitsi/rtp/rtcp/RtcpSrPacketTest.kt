package org.jitsi.rtp.rtcp

import io.kotlintest.matchers.containAll
import io.kotlintest.matchers.haveSize
import io.kotlintest.should
import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec
import java.nio.ByteBuffer
import kotlin.math.exp

internal class RtcpSrPacketTest : ShouldSpec() {
    override fun isInstancePerTest(): Boolean = true

    private val expectedHeader = RtcpHeader.fromValues {
        version = 2
        hasPadding = false
        reportCount = 2
        payloadType = 200
        length = 42 // TODO make this accurate?
        senderSsrc = 12345
    }

    // Sender info
    private val expectedSenderInfo = SenderInfo.fromValues {
        ntpTimestamp = 0x7FFFFFFFFFFFFFFF
        rtpTimestamp = 0xFFFFFFFF
        sendersPacketCount = 0xFFFFFFFF
        sendersOctetCount = 0xFFFFFFFF
    }

    private val reportBlock1 = RtcpReportBlock.fromValues {
        ssrc = 12345
        fractionLost = 42
        cumulativePacketsLost = 4242
        seqNumCycles = 1
        seqNum = 42
        interarrivalJitter = 4242
        lastSrTimestamp = 23456
        delaySinceLastSr = 34567
    }
    private val reportBlock2 = RtcpReportBlock.fromValues {
        ssrc = 23456
        fractionLost = 42
        cumulativePacketsLost = 4242
        seqNumCycles = 1
        seqNum = 42
        interarrivalJitter = 4242
        lastSrTimestamp = 23456
        delaySinceLastSr = 34567
    }

    init {
        "creation" {
            "from a buffer" {
                val buf = ByteBuffer.allocate(1024)
                expectedSenderInfo.serializeToBuffer(buf)
                reportBlock1.serializeToBuffer(buf)
                reportBlock2.serializeToBuffer(buf)
                buf.rewind()
                val srPacket = RtcpSrPacket.fromBuffer(expectedHeader, buf)
                should("read everything correctly") {
                    srPacket.senderInfo.ntpTimestamp shouldBe expectedSenderInfo.ntpTimestamp
                    srPacket.senderInfo.rtpTimestamp shouldBe expectedSenderInfo.rtpTimestamp
                    srPacket.senderInfo.sendersPacketCount shouldBe expectedSenderInfo.sendersPacketCount
                    srPacket.senderInfo.sendersOctetCount shouldBe expectedSenderInfo.sendersOctetCount
                    srPacket.reportBlocks should haveSize(2)
                }
            }
            "from values" {
                val srPacket = RtcpSrPacket.fromValues {
                    this.header = expectedHeader
                    this.senderInfo = expectedSenderInfo
                    this.reportBlocks = listOf(
                        reportBlock1,
                        reportBlock2
                    )
                }
                should("set all values correctly") {
                    srPacket.header shouldBe expectedHeader
                    srPacket.senderInfo shouldBe expectedSenderInfo
                    srPacket.reportBlocks should containAll(reportBlock1, reportBlock2)
                }
            }
        }
        "serialization" {
            val expectedBuf = ByteBuffer.allocate(1024)
            expectedHeader.serializeToBuffer(expectedBuf)
            expectedSenderInfo.serializeToBuffer(expectedBuf)
            reportBlock1.serializeToBuffer(expectedBuf)
            reportBlock2.serializeToBuffer(expectedBuf)
            // Put the buf position past the header, since that's what RtcpSrPacket expects
            expectedBuf.position(8)
            val srPacket = RtcpSrPacket.fromBuffer(expectedHeader, expectedBuf)

            val actualBuf = ByteBuffer.allocate(expectedBuf.limit())
            srPacket.serializeToBuffer(actualBuf)
            should("write all values correctly") {
                actualBuf.rewind() shouldBe expectedBuf.rewind()
            }
        }
    }
}
