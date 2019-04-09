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

package org.jitsi.rtp.rtp

import io.kotlintest.specs.ShouldSpec
import io.kotlintest.shouldBe
import io.kotlintest.shouldNotBe
import org.jitsi.rtp.extensions.bytearray.getShort
import org.jitsi.rtp.extensions.bytearray.putShort
import org.jitsi.rtp.extensions.unsigned.toPositiveInt
import org.jitsi.rtp.util.getByteAsInt

class RtpPacketTest : ShouldSpec() {

    val rtpHeaderWithExtensions = org.jitsi.rtp.extensions.bytearray.byteArrayOf(
        // V=2,P=false,X=true,CC=0,M=false,PT=111,SeqNum=5807
        0x90, 0x6f, 0x16, 0xaf,
        // Timestamp: 1710483662
        0x65, 0xf3, 0xe8, 0xce,
        // SSRC: 839852602
        0x32, 0x0f, 0x22, 0x3a,
        // BEDE, length=1
        0xbe, 0xde, 0x00, 0x01,
        // ExtId=1,Length=0(1 byte),Data=FF,Padding
        0x10, 0xff, 0x00, 0x00
    )

    val rtpHeaderWithNoExtensions = org.jitsi.rtp.extensions.bytearray.byteArrayOf(
        // V=2,P=false,X=false,CC=0,M=false,PT=111,SeqNum=5807
        0x80, 0x6f, 0x16, 0xaf,
        // Timestamp: 1710483662
        0x65, 0xf3, 0xe8, 0xce,
        // SSRC: 839852602
        0x32, 0x0f, 0x22, 0x3a
    )

    val dummyRtpPayload = org.jitsi.rtp.extensions.bytearray.byteArrayOf(
        0x42, 0x42, 0x42, 0x42,
        0x42, 0x42, 0x42, 0x42,
        0x42, 0x42, 0x42, 0x42,
        0x42, 0x42, 0x42, 0x42,
        0x42, 0x42, 0x42, 0x42
    )

    val rtpPacketDataExistingHeaderExtensions = rtpHeaderWithExtensions + dummyRtpPayload

    val rtpPacketDataNoHeaderExtensions = rtpHeaderWithNoExtensions + dummyRtpPayload

    init {
        "An RTP packet with header extensions" {
            val rtpPacket = RtpPacket(rtpPacketDataExistingHeaderExtensions)
            should("be parsed correctly") {
                rtpPacket.version shouldBe 2
                rtpPacket.hasPadding shouldBe false
                rtpPacket.hasExtensions shouldBe true
                rtpPacket.csrcCount shouldBe 0
                rtpPacket.isMarked shouldBe false
                rtpPacket.payloadType shouldBe 111
                rtpPacket.sequenceNumber shouldBe 5807
                rtpPacket.timestamp shouldBe 1710483662L
                val ext = rtpPacket.getHeaderExtension(1)
                ext shouldNotBe null
                ext as RtpPacket.HeaderExtension
                ext.id shouldBe 1
                ext.dataLengthBytes shouldBe 1
                // The offset is the start of the ext, add 1 to move past the header to get the data
                ext.currExtBuffer.getByteAsInt(ext.currExtOffset + 1) shouldBe 0xFF.toPositiveInt()

                rtpPacket.payloadLength shouldBe dummyRtpPayload.size
            }
        }
        "Adding a new RTP header extension" {
            "to an RTP packet with existing extensions" {
                "in a buffer that has no more room" {
                    val rtpPacket = RtpPacket(rtpPacketDataExistingHeaderExtensions)
                    val newExt = rtpPacket.addHeaderExtension(3, 2)
                    newExt.currExtBuffer.putShort(newExt.currExtOffset + 1, 0xDEAD.toShort())
                    should("update the packet correctly") {
                        // All header values should still be correct
                        rtpPacket.version shouldBe 2
                        rtpPacket.hasPadding shouldBe false
                        rtpPacket.hasExtensions shouldBe true
                        rtpPacket.csrcCount shouldBe 0
                        rtpPacket.isMarked shouldBe false
                        rtpPacket.payloadType shouldBe 111
                        rtpPacket.sequenceNumber shouldBe 5807
                        rtpPacket.timestamp shouldBe 1710483662L

                        val ext = rtpPacket.getHeaderExtension(1)
                        ext shouldNotBe null
                        ext as RtpPacket.HeaderExtension
                        ext.id shouldBe 1
                        ext.dataLengthBytes shouldBe 1
                        // The offset is the start of the ext, add 1 to move past the header to get the data
                        ext.currExtBuffer.getByteAsInt(ext.currExtOffset + 1) shouldBe 0xFF.toPositiveInt()

                        val ext2 = rtpPacket.getHeaderExtension(3)
                        ext2 shouldNotBe null
                        ext2 as RtpPacket.HeaderExtension
                        ext2.id shouldBe 3
                        ext2.dataLengthBytes shouldBe 2
                        // The offset is the start of the ext, add 1 to move past the header to get the data
                        ext2.currExtBuffer.getShort(ext.currExtOffset + 1) shouldBe 0xDEAD.toShort()
                    }
                }
            }
            "to an RTP packet with no existing extensions" {
                "in a buffer that has no more room" {
                    val rtpPacket = RtpPacket(rtpPacketDataNoHeaderExtensions)
                    val newExt = rtpPacket.addHeaderExtension(3, 2)
                    newExt.currExtBuffer.putShort(newExt.currExtOffset + 1, 0xDEAD.toShort())
                    should("update the packet correctly") {
                        rtpPacket.version shouldBe 2
                        rtpPacket.hasPadding shouldBe false
                        rtpPacket.hasExtensions shouldBe true
                        rtpPacket.csrcCount shouldBe 0
                        rtpPacket.isMarked shouldBe false
                        rtpPacket.payloadType shouldBe 111
                        rtpPacket.sequenceNumber shouldBe 5807
                        rtpPacket.timestamp shouldBe 1710483662L

                        val ext = rtpPacket.getHeaderExtension(3)
                        ext shouldNotBe null
                        ext as RtpPacket.HeaderExtension
                        ext.id shouldBe 3
                        ext.dataLengthBytes shouldBe 2
                        // The offset is the start of the ext, add 1 to move past the header to get the data
                        ext.currExtBuffer.getShort(ext.currExtOffset + 1) shouldBe 0xDEAD.toShort()
                    }
                }
                "in a buffer that has more room" {
                    val buf = ByteArray(rtpPacketDataNoHeaderExtensions.size + 100)
                    System.arraycopy(rtpPacketDataNoHeaderExtensions, 0, buf, 0, rtpPacketDataNoHeaderExtensions.size)
                    val rtpPacket = RtpPacket(buf)
                    val newExt = rtpPacket.addHeaderExtension(3, 2)
                    newExt.currExtBuffer.putShort(newExt.currExtOffset + 1, 0xDEAD.toShort())
                    should("update the packet correctly") {
                        rtpPacket.version shouldBe 2
                        rtpPacket.hasPadding shouldBe false
                        rtpPacket.hasExtensions shouldBe true
                        rtpPacket.csrcCount shouldBe 0
                        rtpPacket.isMarked shouldBe false
                        rtpPacket.payloadType shouldBe 111
                        rtpPacket.sequenceNumber shouldBe 5807
                        rtpPacket.timestamp shouldBe 1710483662L

                        val ext = rtpPacket.getHeaderExtension(3)
                        ext shouldNotBe null
                        ext as RtpPacket.HeaderExtension
                        ext.id shouldBe 3
                        ext.dataLengthBytes shouldBe 2
                        // The offset is the start of the ext, add 1 to move past the header to get the data
                        ext.currExtBuffer.getShort(ext.currExtOffset + 1) shouldBe 0xDEAD.toShort()
                    }
                }
            }
        }
    }
}