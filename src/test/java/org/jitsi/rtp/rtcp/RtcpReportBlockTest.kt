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

import io.kotlintest.IsolationMode
import io.kotlintest.should
import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec
import org.jitsi.rtp.extensions.put3Bytes
import org.jitsi.rtp.extensions.subBuffer
import org.jitsi.test_helpers.matchers.haveSameContentAs
import java.nio.ByteBuffer

internal class RtcpReportBlockTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

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
                should("leave the position of the buffer at the end of the parsed data") {
                    reportBlockData.position() shouldBe reportBlockData.limit()
                }
            }
            "from values" {
                val reportBlock = RtcpReportBlock(
                    ssrc = expectedSsrc,
                    fractionLost = expectedFractionLost,
                    cumulativePacketsLost = expectedCumulativeLost,
                    seqNumCycles = expectedSeqNumCycles,
                    seqNum = expectedSeqNum,
                    interarrivalJitter = expectedInterarrivalJitter,
                    lastSrTimestamp = expectedLastSrTimestamp,
                    delaySinceLastSr = expectedDelaySinceLastSr
                )
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
            val reportBlock = RtcpReportBlock(
                ssrc = expectedSsrc,
                fractionLost = expectedFractionLost,
                cumulativePacketsLost = expectedCumulativeLost,
                seqNumCycles = expectedSeqNumCycles,
                seqNum = expectedSeqNum,
                interarrivalJitter = expectedInterarrivalJitter,
                lastSrTimestamp = expectedLastSrTimestamp,
                delaySinceLastSr = expectedDelaySinceLastSr
            )
            val newBuf = reportBlock.getBuffer()
            should("write the values correctly") {
                newBuf should haveSameContentAs(reportBlockData)
            }
            "to an existing buffer" {
                val existingBuf = ByteBuffer.allocate(RtcpReportBlock.SIZE_BYTES + 20)
                existingBuf.position(10)
                reportBlock.serializeTo(existingBuf)
                should("write the data to the correct place") {
                    existingBuf.subBuffer(10, RtcpReportBlock.SIZE_BYTES) should haveSameContentAs(reportBlockData)
                }
                should("set the buffer position to the end of the written data") {
                    existingBuf.position() shouldBe (10 + RtcpReportBlock.SIZE_BYTES)

                }
            }
        }
    }
}
