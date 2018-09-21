package org.jitsi.rtp.rtcp

import io.kotlintest.matchers.containAll
import io.kotlintest.matchers.haveSize
import io.kotlintest.should
import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec
import org.jitsi.rtp.extensions.toHex
import java.nio.ByteBuffer
import kotlin.math.exp

internal class RtcpSrPacketTest : ShouldSpec() {
    override fun isInstancePerTest(): Boolean = true

    private val expectedHeader = RtcpHeader(
        version = 2,
        hasPadding = false,
        reportCount = 2,
        packetType = 200,
        length = 42, // TODO make this accurate?
        senderSsrc = 12345
    )

    private val expectedSenderInfo = SenderInfo(
        ntpTimestamp = 0x7FFFFFFFFFFFFFFF,
        rtpTimestamp = 0xFFFFFFFF,
        sendersPacketCount = 0xFFFFFFFF,
        sendersOctetCount = 0xFFFFFFFF
    )

    private val reportBlock1 = RtcpReportBlock(
        ssrc = 12345,
        fractionLost = 42,
        cumulativePacketsLost = 4242,
        seqNumCycles = 1,
        seqNum = 42,
        interarrivalJitter = 4242,
        lastSrTimestamp = 23456,
        delaySinceLastSr = 34567
    )
    private val reportBlock2 = RtcpReportBlock(
        ssrc = 23456,
        fractionLost = 42,
        cumulativePacketsLost = 4242,
        seqNumCycles = 1,
        seqNum = 42,
        interarrivalJitter = 4242,
        lastSrTimestamp = 23456,
        delaySinceLastSr = 34567
    )

    init {
        "f:creation" {
            "!from a buffer" {
                val buf = ByteBuffer.allocate(1024)
                buf.put(expectedHeader.getBuffer())
                buf.put(expectedSenderInfo.getBuffer())
                buf.put(reportBlock1.getBuffer())
                buf.put(reportBlock2.getBuffer())
                buf.rewind()
                val srPacket = RtcpSrPacket(buf)
                should("read everything correctly") {
                    srPacket.senderInfo.ntpTimestamp shouldBe expectedSenderInfo.ntpTimestamp
                    srPacket.senderInfo.rtpTimestamp shouldBe expectedSenderInfo.rtpTimestamp
                    srPacket.senderInfo.sendersPacketCount shouldBe expectedSenderInfo.sendersPacketCount
                    srPacket.senderInfo.sendersOctetCount shouldBe expectedSenderInfo.sendersOctetCount
                    srPacket.reportBlocks should haveSize(2)
                }
            }
            "blah" {
                val pktBuf = ByteBuffer.wrap(byteArrayOf(
                    0x80.toByte(), 0xC8.toByte(), 0x00.toByte(), 0x06.toByte(),
                    0xF0.toByte(), 0xA7.toByte(), 0x6B.toByte(), 0x36.toByte(),
                    0xDF.toByte(), 0x29.toByte(), 0xBB.toByte(), 0x6C.toByte(),
                    0x0C.toByte(), 0xC2.toByte(), 0xF8.toByte(), 0x38.toByte(),
                    0x87.toByte(), 0x52.toByte(), 0x0D.toByte(), 0x00.toByte(),
                    0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x28.toByte(),
                    0x00.toByte(), 0x00.toByte(), 0x7F.toByte(), 0x49.toByte(),
                    0x81.toByte(), 0xCA.toByte(), 0x00.toByte(), 0x06.toByte(),
                    0xF0.toByte(), 0xA7.toByte(), 0x6B.toByte(), 0x36.toByte(),
                    0x01.toByte(), 0x10.toByte(), 0x77.toByte(), 0x69.toByte(),
                    0x4E.toByte(), 0x74.toByte(), 0x6F.toByte(), 0x4B.toByte(),
                    0x39.toByte(), 0x67.toByte(), 0x6F.toByte(), 0x79.toByte(),
                    0x58.toByte(), 0x4F.toByte(), 0x39.toByte(), 0x58.toByte(),
                    0x49.toByte(), 0x77.toByte(), 0x00.toByte(), 0x00.toByte()
                ))
                val sr = RtcpSrPacket(pktBuf)
                val newBuf = sr.getBuffer()
                val newBuf2 = sr.getBuffer()
            }
            "!from values" {
                val srPacket = RtcpSrPacket(
                    header = expectedHeader,
                    senderInfo = expectedSenderInfo,
                    reportBlocks = mutableListOf(
                        reportBlock1,
                        reportBlock2
                    )
                )
                should("set all values correctly") {
                    srPacket.header shouldBe expectedHeader
                    srPacket.senderInfo shouldBe expectedSenderInfo
                    srPacket.reportBlocks should containAll(reportBlock1, reportBlock2)
                }
            }
        }
        "serialization" {
            val expectedBuf = ByteBuffer.allocate(1024)
            expectedBuf.put(expectedHeader.getBuffer())
            expectedBuf.put(expectedSenderInfo.getBuffer())
            expectedBuf.put(reportBlock1.getBuffer())
            expectedBuf.put(reportBlock2.getBuffer())
            expectedBuf.position(0)
            val srPacket = RtcpSrPacket(expectedBuf)

            val actualBuf = srPacket.getBuffer()
            should("write all values correctly") {
                for (i in 0 until actualBuf.limit()) {
                    actualBuf.get(i) shouldBe expectedBuf.get(i)
                }
            }
        }
    }
}
