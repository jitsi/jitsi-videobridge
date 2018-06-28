package org.jitsi.rtp.rtcp

import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec
import org.jitsi.rtp.extensions.put3Bytes
import java.nio.ByteBuffer

internal class RtcpReportBlockTest : ShouldSpec() {
    override fun isInstancePerTest(): Boolean = true

    private val ssrc: Long = 12345
    private val fractionLost: Int = 42
    private val cumulativeLost: Int = 4242
    private val seqNumCycles: Int = 1
    private val seqNum: Int = 42
    private val interarrivalJitter: Long = 4242
    private val lastSrTimestamp: Long = 23456
    private val delaySinceLastSr: Long = 34567

    init {
        val reportBlockData = with (ByteBuffer.allocate(24)) {
            putInt(ssrc.toInt())
            put(fractionLost.toByte()); put3Bytes(cumulativeLost)
            putShort(seqNumCycles.toShort()); putShort(seqNum.toShort())
            putInt(interarrivalJitter.toInt())
            putInt(lastSrTimestamp.toInt())
            putInt(delaySinceLastSr.toInt())
            this.rewind() as ByteBuffer
        }

        "parsing" {
            val reportBlock = RtcpReportBlock(reportBlockData)
            should("parse the values correctly") {
                reportBlock.ssrc shouldBe ssrc
                reportBlock.fractionLost shouldBe fractionLost
                reportBlock.cumulativePacketsLost shouldBe cumulativeLost
                reportBlock.seqNumCycles shouldBe seqNumCycles
                reportBlock.seqNum shouldBe seqNum
                reportBlock.interarrivalJitter shouldBe interarrivalJitter
                reportBlock.lastSrTimestamp shouldBe lastSrTimestamp
                reportBlock.delaySinceLastSr shouldBe delaySinceLastSr
            }
        }
    }
}
