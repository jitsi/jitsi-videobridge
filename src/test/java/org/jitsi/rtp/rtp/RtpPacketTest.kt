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

import io.kotlintest.should
import io.kotlintest.specs.ShouldSpec
import io.kotlintest.shouldBe
import io.kotlintest.shouldNotBe
import org.jitsi.rtp.UnparsedPacket
import org.jitsi.rtp.extensions.bytearray.getShort
import org.jitsi.rtp.extensions.bytearray.putShort
import org.jitsi.rtp.extensions.unsigned.toPositiveInt
import org.jitsi.rtp.util.getByteAsInt
import org.jitsi.test_helpers.matchers.getPayload
import org.jitsi.test_helpers.matchers.haveSameContentAs
import org.jitsi.test_helpers.matchers.haveSameFixedHeader
import org.jitsi.test_helpers.matchers.haveSamePayload

class RtpPacketTest : ShouldSpec() {

    val rtpHeaderWithExtensions = org.jitsi.rtp.extensions.bytearray.byteArrayOf(
        // V=2,P=false,X=true,CC=0,M=false,PT=111,SeqNum=5807
        0x90, 0x6f, 0x16, 0xaf,
        // Timestamp: 1710483662
        0x65, 0xf3, 0xe8, 0xce,
        // SSRC: 839852602
        0x48, 0x0f, 0x22, 0x3a,
        // BEDE, length=1
        0xbe, 0xde, 0x00, 0x01,
        // ExtId=1,Length=0(1 byte),Data=FF,Padding
        0x10, 0xff, 0x00, 0x00
    )

    val rtpHeaderWithExtensionsPaddingBetween = org.jitsi.rtp.extensions.bytearray.byteArrayOf(
        // V=2,P=false,X=true,CC=0,M=false,PT=111,SeqNum=5807
        0x90, 0x6f, 0x16, 0xaf,
        // Timestamp: 1710483662
        0x65, 0xf3, 0xe8, 0xce,
        // SSRC: 839852602
        0x48, 0x0f, 0x22, 0x3a,
        // BEDE, length=2
        0xbe, 0xde, 0x00, 0x02,
        // ExtId=1,Length=1(2 bytes),Data=FF,Padding
        0x11, 0xff, 0xff, 0x00,
        // ExtId=2,Length=0(1 byte),Data=FF,Padding
        0x20, 0xff, 0x00, 0x00
    )

    val rtpHeaderWithNoExtensions = org.jitsi.rtp.extensions.bytearray.byteArrayOf(
        // V=2,P=false,X=false,CC=0,M=false,PT=111,SeqNum=5807
        0x80, 0x6f, 0x16, 0xaf,
        // Timestamp: 1710483662
        0x65, 0xf3, 0xe8, 0xce,
        // SSRC: 839852602
        0x48, 0x0f, 0x22, 0x3a
    )

    val dummyRtpPayload = org.jitsi.rtp.extensions.bytearray.byteArrayOf(
        0x42, 0x42, 0x42, 0x42,
        0x42, 0x42, 0x42, 0x42,
        0x42, 0x42, 0x42, 0x42,
        0x42, 0x42, 0x42, 0x42,
        0x42, 0x42, 0x42, 0x42
    )

    val rtpPacketWithExtensions = RtpPacket(rtpHeaderWithExtensions + dummyRtpPayload)
    val rtpPacketNoExtensions = RtpPacket(rtpHeaderWithNoExtensions + dummyRtpPayload)
    val rtpPacketWithExtensionsWithPaddingBetween = RtpPacket(rtpHeaderWithExtensionsPaddingBetween + dummyRtpPayload)

    init {
        "An RTP packet with header extensions" {
            val rtpPacket = rtpPacketWithExtensions
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
                rtpPacket.getPayload() should haveSameContentAs(UnparsedPacket(dummyRtpPayload))
            }
        }
        "An RTP packet with header extensions with padding between them" {
            val rtpPacket = rtpPacketWithExtensionsWithPaddingBetween
            should("be parsed correctly") {
                rtpPacket should haveSameFixedHeader(rtpPacketWithExtensions)
                rtpPacket should haveSamePayload(rtpPacketWithExtensions)
                val ext1 = rtpPacket.getHeaderExtension(1)
                ext1 shouldNotBe null

                val ext2 = rtpPacket.getHeaderExtension(2)
                ext2 shouldNotBe null
                ext2 as RtpPacket.HeaderExtension
                ext2.id shouldBe 2
                ext2.dataLengthBytes shouldBe 1
                // The offset is the start of the ext, add 1 to move past the header to get the data
                ext2.currExtBuffer.getByteAsInt(ext2.currExtOffset + 1) shouldBe 0xFF.toPositiveInt()
            }
        }
        "Adding a new RTP header extension" {
            "to an RTP packet with existing extensions" {
                "in a buffer that has no more room" {
                    val rtpPacket = rtpPacketWithExtensions.clone()
                    val newExt = rtpPacket.addHeaderExtension(3, 2)
                    newExt.currExtBuffer.putShort(newExt.currExtOffset + 1, 0xDEAD.toShort())
                    should("update the packet correctly") {
                        rtpPacket should haveSameFixedHeader(rtpPacketWithExtensions)

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

                        rtpPacket should haveSamePayload(rtpPacketWithExtensions)
                    }
                    "in a buffer that has room to the right" {
                        val buf = ByteArray(rtpPacketWithExtensions.length + 100)
                        System.arraycopy(
                            rtpPacketWithExtensions.buffer,
                            0,
                            buf,
                            0,
                            rtpPacketWithExtensions.length
                        )
                        val rtpPacket = RtpPacket(buf, 0, rtpPacketWithExtensions.length)
                        val newExt = rtpPacket.addHeaderExtension(3, 2)
                        newExt.currExtBuffer.putShort(newExt.currExtOffset + 1, 0xDEAD.toShort())
                        should("update the packet correctly") {
                            rtpPacket should haveSameFixedHeader(rtpPacketWithExtensions)

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

                            rtpPacket should haveSamePayload(rtpPacketWithExtensions)
                        }
                    }
                    "in a buffer that has room to the left" {
                        val spaceOnTheLeft = 8 // this is what we often have with Octo.
                        val buf = ByteArray(rtpPacketWithExtensions.length + spaceOnTheLeft + 20)
                        System.arraycopy(
                            rtpPacketWithExtensions.buffer,
                            0,
                            buf,
                            spaceOnTheLeft,
                            rtpPacketWithExtensions.length
                        )
                        val rtpPacket = RtpPacket(buf, spaceOnTheLeft, rtpPacketWithExtensions.length)
                        val newExt = rtpPacket.addHeaderExtension(3, 2)
                        newExt.currExtBuffer.putShort(newExt.currExtOffset + 1, 0xDEAD.toShort())
                        should("update the packet correctly") {
                            rtpPacket should haveSameFixedHeader(rtpPacketWithExtensions)

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

                            rtpPacket should haveSamePayload(rtpPacketWithExtensions)
                        }
                    }
                }
            }
            "to an RTP packet with no existing extensions" {
                "in a buffer that has no more room" {
                    val rtpPacket = rtpPacketNoExtensions.clone()
                    val newExt = rtpPacket.addHeaderExtension(3, 2)
                    newExt.currExtBuffer.putShort(newExt.currExtOffset + 1, 0xDEAD.toShort())
                    should("update the packet correctly") {
                        // The only difference in the fixed headers is the extension bit.
                        rtpPacket should haveSameFixedHeader(rtpPacketWithExtensions)

                        val ext = rtpPacket.getHeaderExtension(3)
                        ext shouldNotBe null
                        ext as RtpPacket.HeaderExtension
                        ext.id shouldBe 3
                        ext.dataLengthBytes shouldBe 2
                        // The offset is the start of the ext, add 1 to move past the header to get the data
                        ext.currExtBuffer.getShort(ext.currExtOffset + 1) shouldBe 0xDEAD.toShort()

                        rtpPacket should haveSamePayload(rtpPacketNoExtensions)
                    }
                }
                "in a buffer that has more room to the right" {
                    val buf = ByteArray(rtpPacketNoExtensions.length + 100)
                    System.arraycopy(rtpPacketNoExtensions.buffer, 0, buf, 0, rtpPacketNoExtensions.length)
                    val rtpPacket = RtpPacket(buf, 0, rtpPacketNoExtensions.length)
                    val newExt = rtpPacket.addHeaderExtension(3, 2)
                    newExt.currExtBuffer.putShort(newExt.currExtOffset + 1, 0xDEAD.toShort())
                    should("update the packet correctly") {
                        // The only difference in the fixed headers is the extension bit.
                        rtpPacket should haveSameFixedHeader(rtpPacketWithExtensions)

                        val ext = rtpPacket.getHeaderExtension(3)
                        ext shouldNotBe null
                        ext as RtpPacket.HeaderExtension
                        ext.id shouldBe 3
                        ext.dataLengthBytes shouldBe 2
                        // The offset is the start of the ext, add 1 to move past the header to get the data
                        ext.currExtBuffer.getShort(ext.currExtOffset + 1) shouldBe 0xDEAD.toShort()

                        rtpPacket should haveSamePayload(rtpPacketNoExtensions)
                    }
                }
                "in a buffer that has more room to the left" {
                    val spaceOnTheLeft = 8 // this is what we often have with Octo.
                    val buf = ByteArray(rtpPacketNoExtensions.length + spaceOnTheLeft + 20)
                    System.arraycopy(
                        rtpPacketNoExtensions.buffer,
                        0,
                        buf,
                        spaceOnTheLeft,
                        rtpPacketNoExtensions.length
                    )
                    val rtpPacket = RtpPacket(buf, spaceOnTheLeft, rtpPacketNoExtensions.length)
                    val newExt = rtpPacket.addHeaderExtension(3, 2)
                    newExt.currExtBuffer.putShort(newExt.currExtOffset + 1, 0xDEAD.toShort())
                    should("update the packet correctly") {
                        // The only difference in the fixed headers is the extension bit.
                        rtpPacket should haveSameFixedHeader(rtpPacketWithExtensions)

                        val ext = rtpPacket.getHeaderExtension(3)
                        ext shouldNotBe null
                        ext as RtpPacket.HeaderExtension
                        ext.id shouldBe 3
                        ext.dataLengthBytes shouldBe 2
                        // The offset is the start of the ext, add 1 to move past the header to get the data
                        ext.currExtBuffer.getShort(ext.currExtOffset + 1) shouldBe 0xDEAD.toShort()

                        rtpPacket should haveSamePayload(rtpPacketNoExtensions)
                    }
                }
            }
        }
    }
}