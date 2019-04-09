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

package org.jitsi.rtp.rtcp

import io.kotlintest.should
import io.kotlintest.specs.ShouldSpec
import io.kotlintest.shouldBe
import org.jitsi.rtp.extensions.put3Bytes
import org.jitsi.rtp.extensions.unsigned.toPositiveLong
import org.jitsi.test_helpers.matchers.haveSameContentAs
import java.nio.ByteBuffer

class RtcpReportBlockTest : ShouldSpec() {
    private val expectedSsrc: Long = 12345
    private val expectedFractionLost: Int = 42
    private val expectedCumulativeLost: Int = 4242
    private val expectedSeqNumCycles: Int = 1
    private val expectedSeqNum: Int = 42
    private val expectedHighestSeqNum = ((expectedSeqNumCycles shl 16) + expectedSeqNum.toShort()).toPositiveLong()
    private val expectedInterarrivalJitter: Long = 4242
    private val expectedLastSrTimestamp: Long = 23456
    private val expectedDelaySinceLastSr: Long = 34567

    val reportBlockData = with(ByteBuffer.allocate(24)) {
        putInt(expectedSsrc.toInt())
        put(expectedFractionLost.toByte()); put3Bytes(expectedCumulativeLost)
        putShort(expectedSeqNumCycles.toShort()); putShort(expectedSeqNum.toShort())
        putInt(expectedInterarrivalJitter.toInt())
        putInt(expectedLastSrTimestamp.toInt())
        putInt(expectedDelaySinceLastSr.toInt())
        this.rewind() as ByteBuffer
    }

    init {
        "creation" {
            "from a buffer" {
                val reportBlock = RtcpReportBlock.fromBuffer(reportBlockData.array(), reportBlockData.arrayOffset())
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
                val reportBlock = RtcpReportBlock(
                    ssrc = expectedSsrc,
                    fractionLost = expectedFractionLost,
                    cumulativePacketsLost = expectedCumulativeLost,
                    extendedHighestSeqNum = expectedHighestSeqNum,
                    interarrivalJitter = expectedInterarrivalJitter,
                    lastSrTimestamp = expectedLastSrTimestamp,
                    delaySinceLastSr = expectedDelaySinceLastSr
                )
                should("set the values correctly") {
                    reportBlock.ssrc shouldBe expectedSsrc
                    reportBlock.fractionLost shouldBe expectedFractionLost
                    reportBlock.cumulativePacketsLost shouldBe expectedCumulativeLost
                    reportBlock.extendedHighestSeqNum shouldBe expectedHighestSeqNum
                    reportBlock.seqNumCycles shouldBe expectedSeqNumCycles
                    reportBlock.seqNum shouldBe expectedSeqNum
                    reportBlock.interarrivalJitter shouldBe expectedInterarrivalJitter
                    reportBlock.lastSrTimestamp shouldBe expectedLastSrTimestamp
                    reportBlock.delaySinceLastSr shouldBe expectedDelaySinceLastSr
                }
                should("write the data correctly") {
                    val buf = ByteArray(RtcpReportBlock.SIZE_BYTES)
                    reportBlock.writeTo(buf, 0)
                    buf should haveSameContentAs(reportBlockData.array())
                }
            }
        }
    }
}