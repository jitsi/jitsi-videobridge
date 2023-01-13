/*
 * Copyright @ 2023 - present 8x8, Inc.
 * Copyright @ 2023 Vowel, Inc.
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

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.jitsi.rtp.extensions.bytearray.byteArrayOf
import org.jitsi.rtp.extensions.bytearray.getShort
import org.jitsi.rtp.extensions.bytearray.putShort

class RtpTwoByteHeaderTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    private val rtpHeaderWithTwoBytesLongExtensions = byteArrayOf(
        0x90, 0x2a, 0x5f, 0xd5, 0xe0, 0x04, 0xe9, 0x7e, 0x41, 0x2e, 0xc1, 0xa3, 0x10, 0x00, 0x00, 0x1a,
        // ExtId=3
        0x03, 0x02, 0xDE, 0xAD,
        // ExtId=12
        0x0c, 0x61, 0xc1, 0x00, 0x01, 0xc0, 0x08, 0x14, 0x85, 0x21, 0x4e, 0xaf, 0xff, 0xaa, 0xaa, 0x86,
        0x3c, 0xf0, 0x43, 0x0c, 0x10, 0xc3, 0x02, 0xaf, 0xc0, 0xaa, 0xa0, 0x06, 0x3c, 0x00, 0x43, 0x00,
        0x10, 0xc0, 0x02, 0xa0, 0x00, 0xa8, 0x00, 0x06, 0x00, 0x00, 0x40, 0x00, 0x1d, 0x95, 0x49, 0x26,
        0xe0, 0x82, 0xb0, 0x4a, 0x09, 0x41, 0xb8, 0x20, 0xac, 0x12, 0x82, 0x50, 0x31, 0x57, 0xf9, 0x74,
        0x00, 0x0c, 0xa8, 0x64, 0x33, 0x0e, 0x22, 0x22, 0x22, 0xec, 0xa8, 0x65, 0x53, 0x04, 0x22, 0x42,
        0x30, 0xec, 0xa8, 0x77, 0x53, 0x00, 0x9f, 0x00, 0x59, 0x01, 0x3f, 0x00, 0xb3, 0x02, 0x7f, 0x01,
        0x67, 0x1f, 0x80, 0x00,
    )

    private val rtpHeaderWithTwoBytesShortExtensions = byteArrayOf(
        0x90, 0x2a, 0x5f, 0xd5, 0xe0, 0x04, 0xe9, 0x7e, 0x41, 0x2e, 0xc1, 0xa3, 0x10, 0x00, 0x00, 0x02,
        // ExtId=1
        0x01, 0x02, 0xff, 0xff,
        // ExtId=3
        0x03, 0x02, 0xDE, 0xAD,
    )

    private val rtpPacketWithTwoBytesLongExtensions = RtpPacket(rtpHeaderWithTwoBytesLongExtensions)
    private val rtpPacketWithTwoBytesShortExtensions = RtpPacket(rtpHeaderWithTwoBytesShortExtensions)

    init {
        context("One byte size content RTP packet with 2-bytes header extensions") {
            context("Parsing header extensions") {
                val rtpPacket = rtpPacketWithTwoBytesShortExtensions
                should("be parsed correctly") {
                    rtpPacket.version shouldBe 2
                    rtpPacket.hasPadding shouldBe false
                    rtpPacket.hasExtensions shouldBe true
                    rtpPacket.csrcCount shouldBe 0
                    rtpPacket.isMarked shouldBe false
                    rtpPacket.payloadType shouldBe 42
                    rtpPacket.sequenceNumber shouldBe 24533
                    rtpPacket.timestamp shouldBe 3758418302L

                    val ext = rtpPacket.getHeaderExtension(3)
                    ext shouldNotBe null
                    ext as RtpPacket.HeaderExtension
                    ext.id shouldBe 3
                    ext.dataLengthBytes shouldBe 2
                    ext.isTwoByteHeaderExtension shouldBe true
                    ext.currExtBuffer.getShort(ext.currExtOffset + ext.getHeaderSize()) shouldBe 0xDEAD.toShort()

                    val ext2 = rtpPacket.getHeaderExtension(1)
                    ext2 shouldNotBe null
                    ext2 as RtpPacket.HeaderExtension
                    ext2.id shouldBe 1
                    ext2.dataLengthBytes shouldBe 2
                    ext2.isTwoByteHeaderExtension shouldBe true
                    ext2.currExtBuffer.getShort(ext2.currExtOffset + ext2.getHeaderSize()) shouldBe 0xFFFF.toShort()

                    rtpPacket.payloadLength shouldBe 0
                    rtpPacket.sanityCheck()
                }
            }

            context("Removing a header extension") {
                val rtpPacket = rtpPacketWithTwoBytesShortExtensions.clone()
                rtpPacket.removeHeaderExtension(3)
                rtpPacket.encodeHeaderExtensions()

                should("update the packet correctly") {
                    rtpPacket.sanityCheck()
                    rtpPacket.getHeaderExtension(3) shouldBe null

                    val ext = rtpPacket.getHeaderExtension(1)
                    ext shouldNotBe null
                    ext as RtpPacket.HeaderExtension
                    ext.id shouldBe 1
                    ext.dataLengthBytes shouldBe 2
                    ext.isTwoByteHeaderExtension shouldBe false
                    ext.currExtBuffer.getShort(ext.currExtOffset + ext.getHeaderSize()) shouldBe 0xFFFF.toShort()
                }
            }

            context("Re-adding a header extension") {
                val rtpPacket = rtpPacketWithTwoBytesShortExtensions.clone()
                rtpPacket.removeHeaderExtension(3)
                rtpPacket.encodeHeaderExtensions()
                val newExt = rtpPacket.addHeaderExtension(3, 2)
                newExt.currExtBuffer.putShort(newExt.currExtOffset + newExt.getHeaderSize(), 0xDEAD.toShort())
                rtpPacket.encodeHeaderExtensions()

                should("update the packet correctly") {
                    rtpPacket.sanityCheck()
                    rtpPacket.payloadLength shouldBe 0

                    val ext = rtpPacket.getHeaderExtension(3)
                    ext shouldNotBe null
                    ext as RtpPacket.HeaderExtension
                    ext.id shouldBe 3
                    ext.dataLengthBytes shouldBe 2
                    ext.isTwoByteHeaderExtension shouldBe false
                    ext.currExtBuffer.getShort(ext.currExtOffset + ext.getHeaderSize()) shouldBe 0xDEAD.toShort()

                    val ext2 = rtpPacket.getHeaderExtension(1)
                    ext2 shouldNotBe null
                    ext2 as RtpPacket.HeaderExtension
                    ext2.id shouldBe 1
                    ext2.dataLengthBytes shouldBe 2
                    ext2.isTwoByteHeaderExtension shouldBe false
                    ext2.currExtBuffer.getShort(ext2.currExtOffset + ext2.getHeaderSize()) shouldBe 0xFFFF.toShort()
                }
            }
        }

        context("Two byte size content  RTP packet with 2-bytes header extensions") {
            context("Parsing header extensions") {
                val rtpPacket = rtpPacketWithTwoBytesLongExtensions
                should("be parsed correctly") {
                    rtpPacket.version shouldBe 2
                    rtpPacket.hasPadding shouldBe false
                    rtpPacket.hasExtensions shouldBe true
                    rtpPacket.csrcCount shouldBe 0
                    rtpPacket.isMarked shouldBe false
                    rtpPacket.payloadType shouldBe 42
                    rtpPacket.sequenceNumber shouldBe 24533
                    rtpPacket.timestamp shouldBe 3758418302L

                    val ext = rtpPacket.getHeaderExtension(3)
                    ext shouldNotBe null
                    ext as RtpPacket.HeaderExtension
                    ext.id shouldBe 3
                    ext.dataLengthBytes shouldBe 2
                    ext.isTwoByteHeaderExtension shouldBe true
                    ext.currExtBuffer.getShort(ext.currExtOffset + ext.getHeaderSize()) shouldBe 0xDEAD.toShort()

                    val ext2 = rtpPacket.getHeaderExtension(12)
                    ext2 shouldNotBe null
                    ext2 as RtpPacket.HeaderExtension
                    ext2.id shouldBe 12
                    ext2.dataLengthBytes shouldBe 97
                    ext2.isTwoByteHeaderExtension shouldBe true

                    rtpPacket.payloadLength shouldBe 0
                    rtpPacket.sanityCheck()
                }
            }

            context("Removing a header extension") {
                val rtpPacket = rtpPacketWithTwoBytesLongExtensions.clone()
                rtpPacket.removeHeaderExtension(12)
                rtpPacket.encodeHeaderExtensions()

                should("update the packet correctly") {
                    rtpPacket.sanityCheck()
                    rtpPacket.getHeaderExtension(12) shouldBe null

                    val ext = rtpPacket.getHeaderExtension(3)
                    ext shouldNotBe null
                    ext as RtpPacket.HeaderExtension
                    ext.id shouldBe 3
                    ext.dataLengthBytes shouldBe 2
                    ext.isTwoByteHeaderExtension shouldBe false
                    ext.currExtBuffer.getShort(ext.currExtOffset + ext.getHeaderSize()) shouldBe 0xDEAD.toShort()
                }
            }

            context("Throw exception on creating one-byte header pending extension") {
                shouldThrow<IllegalArgumentException> {
                    val rtpPacket = rtpPacketWithTwoBytesLongExtensions.clone()
                    rtpPacket.removeHeaderExtension(3)
                    rtpPacket.encodeHeaderExtensions()
                }
            }
        }
    }
}
