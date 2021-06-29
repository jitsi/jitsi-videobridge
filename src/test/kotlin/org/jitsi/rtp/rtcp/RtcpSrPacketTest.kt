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

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.jitsi.rtp.util.byteBufferOf
import java.nio.ByteBuffer

internal class RtcpSrPacketTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    private val expectedSenderInfo = SenderInfoBuilder(
        ntpTimestampMsw = 0x7FFFFFFF,
        ntpTimestampLsw = 0xFFFFFFFF,
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

    private val srPacket = RtcpSrPacketBuilder(
        RtcpHeaderBuilder(
            senderSsrc = 12345
        ),
        expectedSenderInfo,
        mutableListOf(reportBlock1, reportBlock2)
    ).build()

    val srPacketData = byteBufferOf(
        // V=2,P=false,RC=0,PT=200,L=6(28 bytes)
        0x80, 0xc8, 0x00, 0x06,
        // Sender SSRC: 1829790331
        0x6d, 0x10, 0x62, 0x7b,
        // Timestamp, MSW: 3761595357
        0xe0, 0x35, 0x63, 0xdd,
        // Timestamp, LSW: 17218523
        0x01, 0x06, 0xbb, 0xdb,
        // RTP timestamp: 3960153838
        0xec, 0x0b, 0x26, 0xee,
        // Sender's packet count: 72
        0x00, 0x00, 0x00, 0x48,
        // Sender's octet count: 76643
        0x00, 0x01, 0x2b, 0x63
    )

    val srPacketBuffer = ByteBuffer.wrap(srPacket.buffer, srPacket.offset, srPacket.length)

    init {
        context("creation") {
            context("from a buffer") {
//                val srPacket = RtcpSrPacket(srPacketBuffer.array(), srPacketBuffer.arrayOffset(), srPacketBuffer.limit())
                val srPacket = RtcpSrPacket(srPacketData.array(), srPacketData.arrayOffset(), srPacketData.limit())
                should("read everything correctly") {
                    srPacket.version shouldBe 2
                    srPacket.hasPadding shouldBe false
                    srPacket.reportCount shouldBe 0
                    srPacket.reportBlocks shouldHaveSize 0
                    srPacket.packetType shouldBe 200
                    srPacket.lengthField shouldBe 6
                    srPacket.senderSsrc shouldBe 1829790331
                    srPacket.senderInfo.ntpTimestampMsw shouldBe 3761595357
                    srPacket.senderInfo.ntpTimestampLsw shouldBe 17218523
                    srPacket.senderInfo.compactedNtpTimestamp shouldBe 1675428102
                    srPacket.senderInfo.rtpTimestamp shouldBe 3960153838
                    srPacket.senderInfo.sendersPacketCount shouldBe 72
                    srPacket.senderInfo.sendersOctetCount shouldBe 76643

//                    srPacket.packetType shouldBe RtcpSrPacket.PT
//                    srPacket.reportCount shouldBe 2
//                    srPacket.lengthField shouldBe 18
//                    srPacket.senderInfo.ntpTimestampMsw shouldBe expectedSenderInfo.ntpTimestampMsw
//                    srPacket.senderInfo.ntpTimestampLsw shouldBe expectedSenderInfo.ntpTimestampLsw
//                    srPacket.senderInfo.compactedNtpTimestamp shouldBe 0xFFFFFFFF.toPositiveLong()
//                    srPacket.senderInfo.rtpTimestamp shouldBe expectedSenderInfo.rtpTimestamp
//                    srPacket.senderInfo.sendersPacketCount shouldBe expectedSenderInfo.sendersPacketCount
//                    srPacket.senderInfo.sendersOctetCount shouldBe expectedSenderInfo.sendersOctetCount
//                    srPacket.reportBlocks should haveSize(2)
                    // TODO: verify report block parse
//                    srPacket.reportBlocks[0] shouldBe reportBlock1
//                    srPacket.reportBlocks[1] shouldBe reportBlock2
                }
            }
            context("cloneWithoutReportBlocks") {
                context("when there were report blocks") {
                    val clone = srPacket.cloneWithoutReportBlocks()
                    should("clone everything correctly") {
                        clone.version shouldBe srPacket.version
                        clone.hasPadding shouldBe srPacket.hasPadding
                        clone.reportCount shouldBe 0
                        clone.packetType shouldBe srPacket.packetType
                        clone.length shouldBe RtcpHeader.SIZE_BYTES + SenderInfoParser.SIZE_BYTES
                        clone.senderSsrc shouldBe srPacket.senderSsrc
                        clone.buffer shouldNotBe srPacket.buffer
                    }
                }
                context("when there were no report blocks") {
                    val srPacketNoReportBlocks = RtcpSrPacketBuilder(
                        RtcpHeaderBuilder(
                            senderSsrc = 12345
                        ),
                        expectedSenderInfo
                    ).build()
                    val clone = srPacketNoReportBlocks.cloneWithoutReportBlocks()
                    should("clone everything correctly") {
                        clone.version shouldBe srPacket.version
                        clone.hasPadding shouldBe srPacket.hasPadding
                        clone.reportCount shouldBe 0
                        clone.packetType shouldBe srPacket.packetType
                        clone.length shouldBe RtcpHeader.SIZE_BYTES + SenderInfoParser.SIZE_BYTES
                        clone.senderSsrc shouldBe srPacket.senderSsrc
                        clone.buffer shouldNotBe srPacket.buffer
                    }
                }
            }
//            context("from explicit values") {
//                val srPacket = RtcpSrPacket(
//                    header = expectedHeader,
//                    senderInfo = expectedSenderInfo,
//                    reportBlocks = mutableListOf(
//                        reportBlock1,
//                        reportBlock2
//                    )
//                )
//                should("set all values correctly") {
//                    srPacket.header shouldBe expectedHeader
//                    srPacket.senderInfo shouldBe expectedSenderInfo
//                    srPacket.reportBlocks should containAll(reportBlock1, reportBlock2)
//                }
//            }
//            context("from an incomplete set of values") {
//                val srPacket = RtcpSrPacket(
//                    header = RtcpHeader(reportCount = 2, senderSsrc = 12345),
//                    senderInfo = expectedSenderInfo,
//                    reportBlocks = mutableListOf(
//                        reportBlock1,
//                        reportBlock2
//                    )
//                )
//                val parsedPacket = RtcpSrPacket.fromBuffer(srPacket.getBuffer())
//                should("set all values correctly") {
//                    RtcpHeaderTest.rtcpHeaderEquals(parsedPacket.header, expectedHeader)
//                    parsedPacket.senderInfo shouldBe expectedSenderInfo
//                    parsedPacket.reportBlocks should containAll(reportBlock1, reportBlock2)
//                }
//            }
        }
//        context("serialization") {
//            val expectedBuf = ByteBuffer.allocate(1024)
//            expectedBuf.put(expectedHeader.getBuffer())
//            expectedBuf.put(expectedSenderInfo.getBuffer())
//            expectedBuf.put(reportBlock1.getBuffer())
//            expectedBuf.put(reportBlock2.getBuffer())
//            expectedBuf.flip()
//            val srPacket = RtcpSrPacket.fromBuffer(expectedBuf)
//
//            context("via getting its buffer") {
//                val actualBuf = srPacket.getBuffer()
//                should("write all values correctly") {
//                    actualBuf should haveSameContentAs(expectedBuf)
//                }
//            }
//            context("to an existing buffer") {
//                val existingBuf = ByteBuffer.allocate(1024)
//                existingBuf.position(8)
//                srPacket.serializeTo(existingBuf)
//                should("write the data to the proper place") {
//                    val subBuf = existingBuf.subBuffer(8, expectedBuf.limit())
//                    subBuf should haveSameContentAs(expectedBuf)
//                }
//                should("leave the buffer's position after the field it just wrote") {
//                    existingBuf.position() shouldBe (8 + expectedBuf.limit())
//                }
//            }
//        }
    }
}
