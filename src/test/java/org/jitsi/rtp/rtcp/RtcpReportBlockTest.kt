package org.jitsi.rtp.rtcp

import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec
import org.jitsi.rtp.extensions.put3Bytes
import org.jitsi.rtp.extensions.toHex
import java.nio.ByteBuffer

internal class RtcpReportBlockTest : ShouldSpec() {
    override fun isInstancePerTest(): Boolean = true

    private val expectedSsrc: Long = 12345
    private val expectedFractionLost: Int = 42
    private val expectedCumulativeLost: Int = 4242
    private val expectedSeqNumCycles: Int = 1
    private val expectedSeqNum: Int = 42
    private val expectedInterarrivalJitter: Long = 4242
    private val expectedLastSrTimestamp: Long = 23456
    private val expectedDelaySinceLastSr: Long = 34567

    init {
        val reportBlockData = with (ByteBuffer.allocate(24)) {
            putInt(expectedSsrc.toInt())
            put(expectedFractionLost.toByte()); put3Bytes(expectedCumulativeLost)
            putShort(expectedSeqNumCycles.toShort()); putShort(expectedSeqNum.toShort())
            putInt(expectedInterarrivalJitter.toInt())
            putInt(expectedLastSrTimestamp.toInt())
            putInt(expectedDelaySinceLastSr.toInt())
            this.rewind() as ByteBuffer
        }

        "creation" {
            "from a buffer" {
                val reportBlock = RtcpReportBlock.fromBuffer(reportBlockData)
                should("read the values correctly") {
                    reportBlock.ssrc shouldBe expectedSsrc
                    reportBlock.fractionLost shouldBe expectedFractionLost
                    reportBlock.cumulativePacketsLost shouldBe expectedCumulativeLost
                    reportBlock.seqNumCycles shouldBe expectedSeqNumCycles
                    reportBlock.seqNum shouldBe expectedSeqNum
                    reportBlock.interarrivalJitter shouldBe expectedInterarrivalJitter
                    reportBlock.lastSrTimestamp shouldBe expectedLastSrTimestamp
                    reportBlock.delaySinceLastSr shouldBe expectedDelaySinceLastSr
                }
            }
            "from values" {
                val reportBlock = RtcpReportBlock.fromValues {
                    ssrc = expectedSsrc
                    fractionLost = expectedFractionLost
                    cumulativePacketsLost = expectedCumulativeLost
                    seqNumCycles = expectedSeqNumCycles
                    seqNum = expectedSeqNum
                    interarrivalJitter = expectedInterarrivalJitter
                    lastSrTimestamp = expectedLastSrTimestamp
                    delaySinceLastSr = expectedDelaySinceLastSr
                }
                should("set the values correctly") {
                    reportBlock.ssrc shouldBe expectedSsrc
                    reportBlock.fractionLost shouldBe expectedFractionLost
                    reportBlock.cumulativePacketsLost shouldBe expectedCumulativeLost
                    reportBlock.seqNumCycles shouldBe expectedSeqNumCycles
                    reportBlock.seqNum shouldBe expectedSeqNum
                    reportBlock.interarrivalJitter shouldBe expectedInterarrivalJitter
                    reportBlock.lastSrTimestamp shouldBe expectedLastSrTimestamp
                    reportBlock.delaySinceLastSr shouldBe expectedDelaySinceLastSr
                }
            }
        }
        "serialization" {
            val reportBlock = RtcpReportBlock.fromValues {
                ssrc = expectedSsrc
                fractionLost = expectedFractionLost
                cumulativePacketsLost = expectedCumulativeLost
                seqNumCycles = expectedSeqNumCycles
                seqNum = expectedSeqNum
                interarrivalJitter = expectedInterarrivalJitter
                lastSrTimestamp = expectedLastSrTimestamp
                delaySinceLastSr = expectedDelaySinceLastSr
            }
            val newBuf = ByteBuffer.allocate(24)
            reportBlock.serializeToBuffer(newBuf)
            should("write the values correctly") {
                println(newBuf.toHex())
                println(reportBlockData.toHex())
                newBuf.rewind() shouldBe reportBlockData.rewind()
            }
        }
    }
}
