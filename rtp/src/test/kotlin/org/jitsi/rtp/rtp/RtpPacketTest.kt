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

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.jitsi.rtp.Packet
import org.jitsi.rtp.UnparsedPacket
import org.jitsi.rtp.extensions.bytearray.byteArrayOf
import org.jitsi.rtp.extensions.bytearray.getShort
import org.jitsi.rtp.extensions.bytearray.putShort
import org.jitsi.rtp.extensions.unsigned.toPositiveInt
import org.jitsi.rtp.util.BufferPool
import org.jitsi.rtp.util.getByteAsInt
import org.jitsi.test_helpers.matchers.getPayload
import org.jitsi.test_helpers.matchers.haveSameContentAs
import org.jitsi.test_helpers.matchers.haveSameFixedHeader
import org.jitsi.test_helpers.matchers.haveSamePayload
import java.util.Collections

class RtpPacketTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    private val rtpHeaderWithExtensions = byteArrayOf(
        // V=2,P=false,X=true,CC=0,M=false,PT=111,SeqNum=5807
        0x90, 0x6f, 0x16, 0xaf,
        // Timestamp: 1710483662
        0x65, 0xf3, 0xe8, 0xce,
        // SSRC: 1208951354
        0x48, 0x0f, 0x22, 0x3a,
        // BEDE, length=1
        0xbe, 0xde, 0x00, 0x01,
        // ExtId=1,Length=0(1 byte),Data=FF,Padding
        0x10, 0xff, 0x00, 0x00
    )

    private val rtpHeaderWithExtensionsPaddingBetween = byteArrayOf(
        // V=2,P=false,X=true,CC=0,M=false,PT=111,SeqNum=5807
        0x90, 0x6f, 0x16, 0xaf,
        // Timestamp: 1710483662
        0x65, 0xf3, 0xe8, 0xce,
        // SSRC: 1208951354
        0x48, 0x0f, 0x22, 0x3a,
        // BEDE, length=2
        0xbe, 0xde, 0x00, 0x02,
        // ExtId=1,Length=1(2 bytes),Data=FF,Padding
        0x11, 0xff, 0xff, 0x00,
        // ExtId=2,Length=0(1 byte),Data=FF,Padding
        0x20, 0xff, 0x00, 0x00
    )

    private val rtpHeaderWithNoExtensions = byteArrayOf(
        // V=2,P=false,X=false,CC=0,M=false,PT=111,SeqNum=5807
        0x80, 0x6f, 0x16, 0xaf,
        // Timestamp: 1710483662
        0x65, 0xf3, 0xe8, 0xce,
        // SSRC: 1208951354
        0x48, 0x0f, 0x22, 0x3a
    )

    private val dummyRtpPayload = byteArrayOf(
        0x42, 0x42, 0x42, 0x42,
        0x42, 0x42, 0x42, 0x42,
        0x42, 0x42, 0x42, 0x42,
        0x42, 0x42, 0x42, 0x42,
        0x42, 0x42, 0x42, 0x42
    )

    private val rtpPacketWithExtensions = RtpPacket(rtpHeaderWithExtensions + dummyRtpPayload)
    private val rtpPacketNoExtensions = RtpPacket(rtpHeaderWithNoExtensions + dummyRtpPayload)
    private val rtpPacketWithExtensionsWithPaddingBetween =
        RtpPacket(rtpHeaderWithExtensionsPaddingBetween + dummyRtpPayload)

    init {
        context("An RTP packet with header extensions") {
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
                rtpPacket.sanityCheck()
            }
            should("allow changing the ID of a header extension") {
                val ext = rtpPacket.getHeaderExtension(1)
                ext shouldNotBe null
                ext as RtpPacket.HeaderExtension
                ext.id = 12
                rtpPacket.getHeaderExtension(1) shouldBe null
                rtpPacket.getHeaderExtension(12) shouldNotBe null
            }
        }
        context("An RTP packet with header extensions with padding between them") {
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
        context("Adding a new RTP header extension") {
            context("to an RTP packet with existing extensions") {
                context("in a buffer that has no more room") {
                    val rtpPacket = rtpPacketWithExtensions.clone()
                    val newExt = rtpPacket.addHeaderExtension(3, 2)
                    newExt.currExtBuffer.putShort(newExt.currExtOffset + 1, 0xDEAD.toShort())
                    rtpPacket.encodeHeaderExtensions()

                    should("update the packet correctly") {
                        rtpPacket.sanityCheck()
                        rtpPacket should haveSameFixedHeader(rtpPacketWithExtensions)

                        rtpPacket.headerLength shouldBe
                            RtpHeader.FIXED_HEADER_SIZE_BYTES + RtpHeader.EXT_HEADER_SIZE_BYTES + 8

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
                context("in a buffer that has room to the right") {
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
                    rtpPacket.encodeHeaderExtensions()

                    should("update the packet correctly") {
                        rtpPacket.sanityCheck()
                        rtpPacket should haveSameFixedHeader(rtpPacketWithExtensions)

                        rtpPacket.headerLength shouldBe
                            RtpHeader.FIXED_HEADER_SIZE_BYTES + RtpHeader.EXT_HEADER_SIZE_BYTES + 8

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
                context("in a buffer that has room to the left") {
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
                    rtpPacket.encodeHeaderExtensions()

                    should("update the packet correctly") {
                        rtpPacket.sanityCheck()
                        rtpPacket should haveSameFixedHeader(rtpPacketWithExtensions)

                        rtpPacket.headerLength shouldBe
                            RtpHeader.FIXED_HEADER_SIZE_BYTES + RtpHeader.EXT_HEADER_SIZE_BYTES + 8

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
            context("to an RTP packet with no existing extensions") {
                context("in a buffer that has no more room") {
                    val rtpPacket = rtpPacketNoExtensions.clone()
                    val newExt = rtpPacket.addHeaderExtension(3, 2)
                    newExt.currExtBuffer.putShort(newExt.currExtOffset + 1, 0xDEAD.toShort())
                    rtpPacket.encodeHeaderExtensions()

                    should("update the packet correctly") {
                        rtpPacket.sanityCheck()
                        // The only difference in the fixed headers is the extension bit.
                        rtpPacket should haveSameFixedHeader(rtpPacketWithExtensions)

                        rtpPacket.headerLength shouldBe
                            RtpHeader.FIXED_HEADER_SIZE_BYTES + RtpHeader.EXT_HEADER_SIZE_BYTES + 4

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
                context("in a buffer that has more room to the right") {
                    val buf = ByteArray(rtpPacketNoExtensions.length + 100)
                    System.arraycopy(rtpPacketNoExtensions.buffer, 0, buf, 0, rtpPacketNoExtensions.length)
                    val rtpPacket = RtpPacket(buf, 0, rtpPacketNoExtensions.length)
                    val newExt = rtpPacket.addHeaderExtension(3, 2)
                    newExt.currExtBuffer.putShort(newExt.currExtOffset + 1, 0xDEAD.toShort())
                    rtpPacket.encodeHeaderExtensions()

                    should("update the packet correctly") {
                        rtpPacket.sanityCheck()
                        // The only difference in the fixed headers is the extension bit.
                        rtpPacket should haveSameFixedHeader(rtpPacketWithExtensions)

                        rtpPacket.headerLength shouldBe
                            RtpHeader.FIXED_HEADER_SIZE_BYTES + RtpHeader.EXT_HEADER_SIZE_BYTES + 4

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
                context("in a buffer that has more room to the left") {
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
                    rtpPacket.encodeHeaderExtensions()

                    should("update the packet correctly") {
                        rtpPacket.sanityCheck()
                        // The only difference in the fixed headers is the extension bit.
                        rtpPacket should haveSameFixedHeader(rtpPacketWithExtensions)

                        rtpPacket.headerLength shouldBe
                            RtpHeader.FIXED_HEADER_SIZE_BYTES + RtpHeader.EXT_HEADER_SIZE_BYTES + 4

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
                context("in tight padding-only packets") {
                    for (paddingLength in 0..255) {
                        val length = paddingLength + RtpHeader.FIXED_HEADER_SIZE_BYTES
                        val rtpPacket = PaddingOnlyPacket.create(length)

                        val newExt = rtpPacket.addHeaderExtension(3, 2)
                        newExt.currExtBuffer.putShort(newExt.currExtOffset + 1, 0xDEAD.toShort())
                        rtpPacket.encodeHeaderExtensions()

                        rtpPacket.sanityCheck()

                        rtpPacket.headerLength shouldBe
                            RtpHeader.FIXED_HEADER_SIZE_BYTES + RtpHeader.EXT_HEADER_SIZE_BYTES + 4

                        val ext = rtpPacket.getHeaderExtension(3)
                        ext shouldNotBe null
                        ext as RtpPacket.HeaderExtension
                        ext.id shouldBe 3
                        ext.dataLengthBytes shouldBe 2
                        // The offset is the start of the ext, add 1 to move past the header to get the data
                        ext.currExtBuffer.getShort(ext.currExtOffset + 1) shouldBe 0xDEAD.toShort()

                        // Payload length includes padding
                        rtpPacket.payloadLength shouldBe length - RtpHeader.FIXED_HEADER_SIZE_BYTES
                        rtpPacket.paddingSize shouldBe length - RtpHeader.FIXED_HEADER_SIZE_BYTES

                        // This will have done a resize, so it should have left space at the end for the SRTP tag.
                        rtpPacket.offset + rtpPacket.length +
                            Packet.BYTES_TO_LEAVE_AT_END_OF_PACKET shouldBeLessThanOrEqualTo
                            rtpPacket.buffer.size
                    }
                }
            }
        }
        context("Removing a header extension") {
            context("when a header extension remains") {
                val rtpPacket = rtpPacketWithExtensionsWithPaddingBetween.clone()
                rtpPacket.removeHeaderExtension(2)
                rtpPacket.encodeHeaderExtensions()

                should("update the packet correctly") {
                    rtpPacket.sanityCheck()
                    rtpPacket should haveSameFixedHeader(rtpPacketWithExtensionsWithPaddingBetween)

                    rtpPacket.headerLength shouldBe
                        RtpHeader.FIXED_HEADER_SIZE_BYTES + RtpHeader.EXT_HEADER_SIZE_BYTES + 4

                    val ext = rtpPacket.getHeaderExtension(1)
                    ext shouldNotBe null
                    ext as RtpPacket.HeaderExtension
                    ext.id shouldBe 1
                    ext.dataLengthBytes shouldBe 2
                    // The offset is the start of the ext, add 1 to move past the header to get the data
                    ext.currExtBuffer.getShort(ext.currExtOffset + 1) shouldBe 0xFFFF.toShort()
                    rtpPacket should haveSamePayload(rtpPacketNoExtensions)
                }
            }

            context("when no header extension remains") {
                val rtpPacket = rtpPacketWithExtensions.clone()
                rtpPacket.removeHeaderExtension(1)
                rtpPacket.encodeHeaderExtensions()

                should("update the packet correctly") {
                    rtpPacket.sanityCheck()
                    rtpPacket should haveSameFixedHeader(rtpPacketNoExtensions)

                    rtpPacket.headerLength shouldBe RtpHeader.FIXED_HEADER_SIZE_BYTES

                    val ext = rtpPacket.getHeaderExtension(1)
                    ext shouldBe null
                    rtpPacket.hasExtensions shouldBe false
                    rtpPacket should haveSamePayload(rtpPacketNoExtensions)
                }
            }
        }
        context("Removing all but a given header extension") {
            context("when a header extension remains") {
                val rtpPacket = rtpPacketWithExtensionsWithPaddingBetween.clone()
                rtpPacket.removeHeaderExtensionsExcept(Collections.singleton(1))
                rtpPacket.encodeHeaderExtensions()

                should("update the packet correctly") {
                    rtpPacket.sanityCheck()
                    rtpPacket should haveSameFixedHeader(rtpPacketWithExtensionsWithPaddingBetween)

                    rtpPacket.headerLength shouldBe
                        RtpHeader.FIXED_HEADER_SIZE_BYTES + RtpHeader.EXT_HEADER_SIZE_BYTES + 4

                    val ext = rtpPacket.getHeaderExtension(1)
                    ext shouldNotBe null
                    ext as RtpPacket.HeaderExtension
                    ext.id shouldBe 1
                    ext.dataLengthBytes shouldBe 2
                    // The offset is the start of the ext, add 1 to move past the header to get the data
                    ext.currExtBuffer.getShort(ext.currExtOffset + 1) shouldBe 0xFFFF.toShort()

                    val ext2 = rtpPacket.getHeaderExtension(2)
                    ext2 shouldBe null

                    rtpPacket should haveSamePayload(rtpPacketNoExtensions)
                }
            }

            context("when no header extension remains due to no match") {
                val rtpPacket = rtpPacketWithExtensionsWithPaddingBetween.clone()
                rtpPacket.removeHeaderExtensionsExcept(Collections.singleton(3))
                rtpPacket.encodeHeaderExtensions()

                should("update the packet correctly") {
                    rtpPacket.sanityCheck()
                    rtpPacket should haveSameFixedHeader(rtpPacketNoExtensions)

                    rtpPacket.headerLength shouldBe RtpHeader.FIXED_HEADER_SIZE_BYTES

                    val ext = rtpPacket.getHeaderExtension(1)
                    ext shouldBe null
                    rtpPacket.hasExtensions shouldBe false
                    rtpPacket should haveSamePayload(rtpPacketNoExtensions)
                }
            }

            context("when no header extension remains due to empty exception set") {
                val rtpPacket = rtpPacketWithExtensionsWithPaddingBetween.clone()
                rtpPacket.removeHeaderExtensionsExcept(Collections.emptySet())
                rtpPacket.encodeHeaderExtensions()

                should("update the packet correctly") {
                    rtpPacket.sanityCheck()
                    rtpPacket should haveSameFixedHeader(rtpPacketNoExtensions)

                    rtpPacket.headerLength shouldBe RtpHeader.FIXED_HEADER_SIZE_BYTES

                    val ext = rtpPacket.getHeaderExtension(1)
                    ext shouldBe null
                    rtpPacket.hasExtensions shouldBe false
                    rtpPacket should haveSamePayload(rtpPacketNoExtensions)
                }
            }
        }

        context("Removing and then adding header extensions") {
            context("when an original header extension remains") {
                val rtpPacket = rtpPacketWithExtensionsWithPaddingBetween.clone()
                rtpPacket.removeHeaderExtensionsExcept(Collections.singleton(1))
                val newExt = rtpPacket.addHeaderExtension(3, 2)
                newExt.currExtBuffer.putShort(newExt.currExtOffset + 1, 0xDEAD.toShort())
                rtpPacket.encodeHeaderExtensions()

                should("update the packet correctly") {
                    rtpPacket.sanityCheck()
                    rtpPacket should haveSameFixedHeader(rtpPacketWithExtensionsWithPaddingBetween)

                    rtpPacket.headerLength shouldBe
                        RtpHeader.FIXED_HEADER_SIZE_BYTES + RtpHeader.EXT_HEADER_SIZE_BYTES + 8

                    val ext = rtpPacket.getHeaderExtension(1)
                    ext shouldNotBe null
                    ext as RtpPacket.HeaderExtension
                    ext.id shouldBe 1
                    ext.dataLengthBytes shouldBe 2
                    // The offset is the start of the ext, add 1 to move past the header to get the data
                    ext.currExtBuffer.getShort(ext.currExtOffset + 1) shouldBe 0xFFFF.toShort()

                    val ext2 = rtpPacket.getHeaderExtension(2)
                    ext2 shouldBe null

                    val ext3 = rtpPacket.getHeaderExtension(3)
                    ext3 shouldNotBe null
                    ext3 as RtpPacket.HeaderExtension
                    ext3.id shouldBe 3
                    ext3.dataLengthBytes shouldBe 2
                    // The offset is the start of the ext, add 1 to move past the header to get the data
                    ext3.currExtBuffer.getShort(ext3.currExtOffset + 1) shouldBe 0xDEAD.toShort()

                    rtpPacket should haveSamePayload(rtpPacketNoExtensions)
                }
            }

            context("when all original header extensions are removed") {
                val rtpPacket = rtpPacketWithExtensionsWithPaddingBetween.clone()
                rtpPacket.removeHeaderExtensionsExcept(Collections.emptySet())
                val newExt = rtpPacket.addHeaderExtension(3, 2)
                newExt.currExtBuffer.putShort(newExt.currExtOffset + 1, 0xDEAD.toShort())
                rtpPacket.encodeHeaderExtensions()

                should("update the packet correctly") {
                    rtpPacket.sanityCheck()
                    rtpPacket should haveSameFixedHeader(rtpPacketWithExtensionsWithPaddingBetween)

                    rtpPacket.headerLength shouldBe
                        RtpHeader.FIXED_HEADER_SIZE_BYTES + RtpHeader.EXT_HEADER_SIZE_BYTES + 4

                    val ext = rtpPacket.getHeaderExtension(1)
                    ext shouldBe null

                    val ext2 = rtpPacket.getHeaderExtension(2)
                    ext2 shouldBe null

                    val ext3 = rtpPacket.getHeaderExtension(3)
                    ext3 shouldNotBe null
                    ext3 as RtpPacket.HeaderExtension
                    ext3.id shouldBe 3
                    ext3.dataLengthBytes shouldBe 2
                    // The offset is the start of the ext, add 1 to move past the header to get the data
                    ext3.currExtBuffer.getShort(ext3.currExtOffset + 1) shouldBe 0xDEAD.toShort()

                    rtpPacket should haveSamePayload(rtpPacketNoExtensions)
                }
            }
        }
    }
}

// This imitates PaddingVideoPacket in jmt
class PaddingOnlyPacket private constructor(
    buffer: ByteArray,
    offset: Int,
    length: Int
) : RtpPacket(buffer, offset, length) {

    override fun clone(): PaddingOnlyPacket =
        throw NotImplementedError("clone() not supported for padding packets.")

    companion object {
        /**
         * Creating a PaddingVideoPacket by directly grabbing a buffer in its
         * ctor is problematic because we cannot clear the buffer we retrieve
         * before calling the parent class' constructor.  Because the buffer
         * may contain invalid data, any attempts to parse it by parent class(es)
         * could fail, so we use a helper here instead
         */
        fun create(length: Int): PaddingOnlyPacket {
            val buf = BufferPool.getArray(length)
            // It's possible we the buffer we pulled from the pool already has
            // data in it, and we won't be overwriting it with anything so clear
            // out the data
            buf.fill(0, 0, length)

            return PaddingOnlyPacket(buf, 0, length).apply {
                // Recalculate the header length now that we've zero'd everything out
                // and set the fields
                version = RtpHeader.VERSION
                headerLength = RtpHeader.getTotalLength(buffer, offset)
                paddingSize = payloadLength
            }
        }
    }
}

fun RtpPacket.sanityCheck() {
    offset shouldBeGreaterThanOrEqualTo 0
    length shouldBeGreaterThanOrEqualTo 0
    offset + length shouldBeLessThanOrEqualTo buffer.size
    headerLength shouldBeLessThanOrEqualTo length
    headerLength shouldBe RtpHeader.getTotalLength(buffer, offset)
    payloadLength shouldBeLessThan length
    headerLength + payloadLength shouldBe length
    paddingSize shouldBeLessThanOrEqualTo payloadLength
}
