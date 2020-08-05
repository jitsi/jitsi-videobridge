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

package org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb

import io.kotest.core.spec.IsolationMode
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.core.spec.style.ShouldSpec
import org.jitsi.rtp.rtcp.RtcpHeaderBuilder
import org.jitsi.rtp.util.byteBufferOf

internal class RtcpFbNackPacketTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

//    private val samplePacketId = 0
//    private val sampleBlp = GenericNackBlp(listOf(1, 3, 5, 7, 9, 11, 13, 15))
//    private val sampleMediaSsrc = 12345L
//    private val sampleHeader =
//        RtcpHeader(length = 3, packetType = TransportLayerFbPacket.PT,
//            reportCount = RtcpFbNackPacket.FMT)
//    val sampleRtcpFbNackPacketBuf = ByteBuffer.allocate(16).apply {
//        put(sampleHeader.getBuffer())
//        putInt(sampleMediaSsrc.toInt())
//        putShort(samplePacketId.toShort())
//        put(sampleBlp.getBuffer())
//    }.flip() as ByteBuffer

    val multipleNackBlocksBuf = byteBufferOf(
        0x81, 0xCD, 0x00, 0x04,
        0xA2, 0x78, 0xEB, 0x18,
        0x84, 0x50, 0x49, 0x6C,
        // packet id = 44832, other nacked packets = 44838, 44848
        0xAF, 0x20, 0x80, 0x20,
        // packet id = 44850, other nacked packets = 44854, 44856
        0xAF, 0x32, 0x00, 0x28
    )

    init {
        context("Creating an RtcpFbNackPacket") {
//            context("from a buffer") {
//                val nackPacket = RtcpFbNackPacket.
//                should("parse the values correctly") {
//                    nackPacket.mediaSourceSsrc shouldBe sampleMediaSsrc
//                    nackPacket.missingSeqNums.shouldContainInOrder(1, 3, 5, 7, 9, 11, 13, 15)
//                }
//                should("leave the buffer's position after the parsed data") {
//                    sampleRtcpFbNackPacketBuf.position() shouldBe sampleRtcpFbNackPacketBuf.limit()
//                }
//            }
            context("from a buffer with multiple NACK blocks") {
                val nackPacket = RtcpFbNackPacket(
                    multipleNackBlocksBuf.array(),
                    multipleNackBlocksBuf.arrayOffset(),
                    multipleNackBlocksBuf.limit()
                )
                should("parse the values correctly") {
                    nackPacket.missingSeqNums shouldContainExactly sortedSetOf(44832, 44838, 44848, 44850, 44854, 44856)
                }
            }
            context("from values") {
                val nackPacket = RtcpFbNackPacketBuilder(
                    rtcpHeader = RtcpHeaderBuilder(
                        senderSsrc = 4567L
                    ),
                    mediaSourceSsrc = 12345L,
                    missingSeqNums = (0..15 step 2).toSortedSet()
                ).build()
                should("set the values correctly") {
                    nackPacket.senderSsrc shouldBe 4567L
                    nackPacket.mediaSourceSsrc shouldBe 12345L
                    nackPacket.missingSeqNums.shouldContainExactly((0..15 step 2).toSortedSet())
                }
//                context("and then serializing it") {
//                    context("by requesting a buffer") {
//                        val buf = nackPacket.getBuffer()
//                        should("serialize the data correctly") {
//                            buf should haveSameContentAs(sampleRtcpFbNackPacketBuf)
//                        }
//                    }
//                    context("to an existing buffer") {
//                        val existingBuf = ByteBuffer.allocate(RtcpFbNackPacket.SIZE_BYTES + 8)
//                        existingBuf.position(8)
//                        nackPacket.serializeTo(existingBuf)
//                        should("write the data to the correct place") {
//                            existingBuf.subBuffer(8) should haveSameContentAs(sampleRtcpFbNackPacketBuf)
//                        }
//                        should("leave the buffer's position after the written data") {
//                            existingBuf.position() shouldBe existingBuf.limit()
//                        }
//                    }
//                }
            }
//            context("from values too big to fit into a single NACK block") {
//                val rtcpFbNackPacket =
//                    RtcpFbNackPacket.fromValues(
//                        header = RtcpHeader(senderSsrc = 2725833496),
//                        mediaSourceSsrc = 2219854188,
//                        missingSeqNums = sortedSetOf(44832, 44838, 44848, 44850, 44854, 44856))
//                context("and then serializing it") {
//                    val buf = rtcpFbNackPacket.getBuffer()
//                    should("serialize into multiple nack blocks") {
//                        buf should haveSameContentAs(multipleNackBlocksBuf)
//                    }
//                }
//            }
        }
    }
}
