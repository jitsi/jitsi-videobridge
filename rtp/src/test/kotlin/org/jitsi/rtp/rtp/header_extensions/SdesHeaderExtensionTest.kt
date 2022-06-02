/*
 * Copyright @ 2021 - present 8x8, Inc.
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
package org.jitsi.rtp.rtp.header_extensions

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.jitsi.rtp.extensions.bytearray.byteArrayOf
import org.jitsi.rtp.rtp.RtpPacket

class SdesHeaderExtensionTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    private val rtpHeaderWithEmptyExtension = byteArrayOf(
        // V=2,P=false,X=true,CC=0,M=false,PT=111,SeqNum=5807
        0x90, 0x6f, 0x16, 0xaf,
        // Timestamp: 1710483662
        0x65, 0xf3, 0xe8, 0xce,
        // SSRC: 1208951354
        0x48, 0x0f, 0x22, 0x3a,
    )

    private val dummyRtpPayload = byteArrayOf(
        0x42, 0x42, 0x42, 0x42,
        0x42, 0x42, 0x42, 0x42,
        0x42, 0x42, 0x42, 0x42,
        0x42, 0x42, 0x42, 0x42,
        0x42, 0x42, 0x42, 0x42
    )

    private val rtpHeaderSimpleSdesExtension = byteArrayOf(
        // BEDE, length=1
        0xbe, 0xde, 0x00, 0x01,
        // ExtId=1,Length=0(1 byte),Data='1',Padding
        0x10, 0x31, 0x00, 0x00
    )

    private val rtpHeaderEmojiSdesExtension = byteArrayOf(
        // BEDE, length=2
        0xbe, 0xde, 0x00, 0x02,
        // ExtId=5,Length=3(4 byte),Data='Poop emoji',Padding
        0x53, 0xf0, 0x9f, 0x92,
        0xa9, 0x00, 0x00, 0x00
    )

    private val rtpHeaderMaxSdesExtension = byteArrayOf(
        // BEDE, length=5
        0xbe, 0xde, 0x00, 0x05,
        // ExtId=15,Length=15(16 byte),Data='abcdefghijklmnop',Padding
        0xff, 0x61, 0x62, 0x63,
        0x64, 0x65, 0x66, 0x67,
        0x68, 0x69, 0x6a, 0x6b,
        0x6c, 0x6d, 0x6e, 0x6f,
        0x70, 0x00, 0x00, 0x00
    )

    private val simpleSdesHeaderExtension = RtpPacket(
        rtpHeaderWithEmptyExtension +
            rtpHeaderSimpleSdesExtension + dummyRtpPayload
    )
    private val maxLengthSdesHeaderExtension = RtpPacket(
        rtpHeaderWithEmptyExtension +
            rtpHeaderMaxSdesExtension + dummyRtpPayload
    )

    private val emojiSdesHeaderExtension = RtpPacket(
        rtpHeaderWithEmptyExtension +
            rtpHeaderEmojiSdesExtension + dummyRtpPayload
    )

    init {
        context("RTP with simple SDES extension value 1") {
            should("simple SDES should be parsed correctly") {
                val rtpPacket = simpleSdesHeaderExtension
                rtpPacket shouldNotBe null
                val sdesExt = rtpPacket.getHeaderExtension(1)
                sdesExt shouldNotBe null
                sdesExt as RtpPacket.HeaderExtension
                sdesExt.id shouldBe 1
                sdesExt.dataLengthBytes shouldBe 1
                val payload = SdesHeaderExtension.getTextValue(sdesExt)
                payload shouldBe "1"
            }
            should("allow changing the simple SDES value") {
                val rtpPacket = simpleSdesHeaderExtension
                rtpPacket shouldNotBe null
                val sdesExt = rtpPacket.getHeaderExtension(1)
                sdesExt shouldNotBe null
                sdesExt as RtpPacket.HeaderExtension
                SdesHeaderExtension.setTextValue(sdesExt, "2")
                val payload = SdesHeaderExtension.getTextValue(sdesExt)
                payload shouldBe "2"
            }
        }
        context("RTP with max len SDES extension value abcdefghijklmop") {
            should("max length SDES should be parsed correctly") {
                val rtpPacket = maxLengthSdesHeaderExtension
                rtpPacket shouldNotBe null
                val sdesExt = rtpPacket.getHeaderExtension(15)
                sdesExt shouldNotBe null
                sdesExt as RtpPacket.HeaderExtension
                sdesExt.id shouldBe 15
                sdesExt.dataLengthBytes shouldBe 16
                val payload = SdesHeaderExtension.getTextValue(sdesExt)
                payload shouldBe "abcdefghijklmnop"
            }
            should("allow changing the max length SDES value") {
                val rtpPacket = maxLengthSdesHeaderExtension
                rtpPacket shouldNotBe null
                val sdesExt = rtpPacket.getHeaderExtension(15)
                sdesExt shouldNotBe null
                sdesExt as RtpPacket.HeaderExtension
                SdesHeaderExtension.setTextValue(sdesExt, "ZYX23acj00yxzABC")
                val payload = SdesHeaderExtension.getTextValue(sdesExt)
                payload shouldBe "ZYX23acj00yxzABC"
            }
        }
        context("RTP with emjoi SDES extension") {
            should("emoji SDES should not be parsed") {
                val rtpPacket = emojiSdesHeaderExtension
                rtpPacket shouldNotBe null
                val sdesExt = rtpPacket.getHeaderExtension(5)
                sdesExt shouldNotBe null
                sdesExt as RtpPacket.HeaderExtension
                sdesExt.id shouldBe 5
                sdesExt.dataLengthBytes shouldBe 4
                val payload = SdesHeaderExtension.getTextValue(sdesExt)
                /* The payload here is UTF-8, but we only parse ASCII.
                *  Parsing doesn't fail, but the string contains garbage. */
                payload shouldNotBe null
            }
        }
    }
}
