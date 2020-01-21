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

package org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc

import io.kotlintest.matchers.beInstanceOf
import io.kotlintest.matchers.maps.shouldContainKey
import io.kotlintest.matchers.withClue
import io.kotlintest.should
import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec
import java.time.Duration
import org.jitsi.rtp.rtcp.RtcpHeaderBuilder
import org.jitsi.rtp.util.byteBufferOf

class RtcpFbTccPacketTest : ShouldSpec() {
    fun Int.toTicks(): Short = (this * 4).toShort()
    private val tccRleData = byteBufferOf(
        // V=2,P=false,FMT=15,PT=205,L=7(32 bytes)
        0x8f, 0xcd, 0x00, 0x07,
        // Sender SSRC = 839852602
        0x32, 0x0f, 0x22, 0x3a,
        // Media source SSRC = 2397376430
        0x8e, 0xe5, 0x0f, 0xae,
        // Base seq num = 0xfffa, packet status count = 9
        0xff, 0xfa, 0x00, 0x09,
        // Reference Time: 1683633 = 107752512ms, feedback packet count = 87
        0x19, 0xb0, 0xb1, 0x57,
        // Chunks
        // RLE, small delta, length = 9
        0x20, 0x09,
        // Deltas (9), one byte each
        0xd8, 0x00,
        0x18, 0x14, 0x18, 0x14,
        0x18, 0x14, 0x18,
        //  Recv delta padding
        0x00
    )

    /**
     * These correspond to the Deltas section above.
     */
    val expectedTccRlePacketInfo = mapOf<Int, Short> (
        0xfffa to 0xd8,
        0xfffb to 0x00,
        0xfffc to 0x18,
        0xfffd to 0x14,
        0xfffe to 0x18,
        0xffff to 0x14,
        0x0000 to 0x18,
        0x0001 to 0x14,
        0x0002 to 0x18
    )

    // This also has a negative delta
    private val tccMixedChunkTypeData = byteBufferOf(
        // V=2,P=false,FMT=15,PT=205,L=9(40 bytes)
        0x8f, 0xcd, 0x00, 0x09,
        // Sender SSRC = 839852602
        0x32, 0x0f, 0x22, 0x3a,
        // Media source SSRC = 2397376430
        0x8e, 0xe5, 0x0f, 0xae,
        // Base seq num = 5376, packet status count = 12
        0x15, 0x00, 0x00, 0x0c,
        // Reference Time: 1684065 = 107780160ms, feedback packet count = 88
        0x19, 0xb2, 0x61, 0x58,
        // Chunks
        // RLE: small delta, length = 9
        0x20, 0x09,
        // SV, 2 bit symbols: LD, SD, SD
        0xe5, 0x00,
        // Deltas (12)
        // 2, 0, 0, 0
        0x08, 0x00, 0x00, 0x00,
        // 22, 1, 0, 0
        0x58, 0x04, 0x00, 0x00,
        // 8, -1, 1
        0x20, 0xff, 0xfc, 0x04,
        // 0
        0x00,
        // Recv delta padding
        0x00, 0x00, 0x00
    )
    val expectedTccMixedChunkTypePacketInfo = mapOf<Int, Short> (
        5376 to 2.toTicks(),
        5377 to 0.toTicks(),
        5378 to 0.toTicks(),
        5379 to 0.toTicks(),
        5380 to 22.toTicks(),
        5381 to 1.toTicks(),
        5382 to 0.toTicks(),
        5383 to 0.toTicks(),
        5384 to 8.toTicks(),
        5385 to (-1).toTicks(),
        5386 to 1.toTicks(),
        5387 to 0.toTicks()
    )

    private val tccSvChunkData = byteBufferOf(
        // V=2,P=false,FMT=15,PT=205,length=5(24 bytes)
        0x8f, 0xcd, 0x00, 0x05,
        // Sender SSRC: 839852602
        0x32, 0x0f, 0x22, 0x3a,
        // Media source SSRC: 2397376430
        0x8e, 0xe5, 0x0f, 0xae,
        // Base seq num = 6227, packet status count = 2
        0x18, 0x53, 0x00, 0x02,
        // Reference Time: 1684126 (107784064ms), feedback packet count = 162
        0x19, 0xb2, 0x9e, 0xa2,
        // Chunks
        // SV chunk, 2 bit symbols: NR, SD
        0xc4, 0x00,
        // Deltas (1)
        // 00
        0x00,
        // Recv delta padding
        0x00
    )
    val expectedTccSvChunkPacketInfo = mapOf<Int, Long> (
        6227 to -1,
        6228 to 107784064 + 27
    )

    init {
        "Parsing an RtcpFbTccPacket" {
            "with RLE" {
                val rtcpFbTccPacket = RtcpFbTccPacket(tccRleData.array(), tccRleData.arrayOffset(), tccRleData.limit())
                should("parse the values correctly") {
                    rtcpFbTccPacket.forEach {
                        it should beInstanceOf<ReceivedPacketReport>()
                        it as ReceivedPacketReport
                        expectedTccRlePacketInfo shouldContainKey it.seqNum
                        withClue("seqNum ${it.seqNum} deltaTicks") {
                            it.deltaTicks shouldBe expectedTccRlePacketInfo[it.seqNum]
                            it.deltaDuration shouldBe Duration.ofNanos(it.deltaTicks * 250 * 1000L)
                        }
                    }
                }
            }
            "with mixed chunk types and a negative delta" {
                val rtcpFbTccPacket = RtcpFbTccPacket(tccMixedChunkTypeData.array(), tccMixedChunkTypeData.arrayOffset(), tccMixedChunkTypeData.limit())
                should("parse the values correctly") {
                    rtcpFbTccPacket.forEach {
                        it should beInstanceOf<ReceivedPacketReport>()
                        it as ReceivedPacketReport
                        expectedTccMixedChunkTypePacketInfo shouldContainKey it.seqNum
                        withClue("seqNum ${it.seqNum} deltaTicks") {
                            it.deltaTicks shouldBe expectedTccMixedChunkTypePacketInfo[it.seqNum]
                            it.deltaDuration shouldBe Duration.ofNanos(it.deltaTicks * 250 * 1000L)
                        }
                    }
                }
            }
        }
        "Creating an RtcpFbTccPacket" {
            val rtcpFbTccPacketBuilder = RtcpFbTccPacketBuilder(
                rtcpHeader = RtcpHeaderBuilder(
                    senderSsrc = 839852602
                ),
                mediaSourceSsrc = 2397376430,
                feedbackPacketSeqNum = 162
            )
            rtcpFbTccPacketBuilder.SetBase(6227, 107784064)
            rtcpFbTccPacketBuilder.AddReceivedPacket(6228, 107784064) shouldBe true
        }
        "Creating and parsing an RtcpFbTccPacket" {
            "with missing packets" {
                val kBaseSeqNo = 1000
                val kBaseTimestampUs = 10000L
                val rtcpFbTccPacketBuilder = RtcpFbTccPacketBuilder(
                    rtcpHeader = RtcpHeaderBuilder(
                        senderSsrc = 839852602
                    ),
                    mediaSourceSsrc = 2397376430,
                    feedbackPacketSeqNum = 163
                )
                rtcpFbTccPacketBuilder.SetBase(kBaseSeqNo, kBaseTimestampUs)
                rtcpFbTccPacketBuilder.AddReceivedPacket(kBaseSeqNo + 0, kBaseTimestampUs)
                rtcpFbTccPacketBuilder.AddReceivedPacket(kBaseSeqNo + 3, kBaseTimestampUs + 2000)

                val coded = rtcpFbTccPacketBuilder.build()

                val packet = RtcpFbTccPacket(coded.buffer, coded.offset, coded.length)
                val it = packet.iterator()
                it.next() should beInstanceOf<ReceivedPacketReport>()
                it.next() should beInstanceOf<UnreceivedPacketReport>()
                it.next() should beInstanceOf<UnreceivedPacketReport>()
                it.next() should beInstanceOf<ReceivedPacketReport>()
            }
        }
    }
}
