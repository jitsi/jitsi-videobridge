/*
 * Copyright @ 2026 - present 8x8, Inc.
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
package org.jitsi.nlj.rtp.codec.av1

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.jitsi.nlj.MediaSourceDesc
import org.jitsi.nlj.RtpEncodingDesc
import org.jitsi.nlj.resources.logging.StdoutLogger
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.utils.logging.DiagnosticContext
import javax.xml.bind.DatatypeConverter

class Av1DDParserTest : ShouldSpec() {
    override fun isolationMode() = IsolationMode.InstancePerLeaf

    private val ssrc = 0x2ab2873cL
    private val logger = StdoutLogger()

    private val parser = Av1DDParser(
        MediaSourceDesc(arrayOf(RtpEncodingDesc(ssrc, 0)), "owner", "name"),
        logger,
        DiagnosticContext()
    )

    /** The first packet of a scalable (L3T3) keyframe. Its DD carries a template dependency structure. */
    private fun keyframe(seq: Int) = packet(
        // RTP header
        "906519aed780a8f32ab2873c" +
            // Header extension header (2-byte)
            "1000001a" +
            // Other header extensions
            "030302228e" +
            "050219f9" +
            // AV1 DD
            "0b5a8003ca80081485214eaaaaa8000600004000100002aa80a80006" +
            "00004000100002a000a800060000400016d549241b5524906d549231" +
            "57e001974ca864330e222396eca8655304224390eca87753013f00b3" +
            "027f016704ff02cf" +
            // Padding
            "000000" +
            // AV1 Media payload, truncated.
            "aabbdc101a58014000b4028001680500",
        seq
    )

    /** A packet of the same stream whose DD has no template dependency structure. */
    private fun following(seq: Int) = packet(
        // RTP header
        "90e519c2d780b1bd2ab2873c" +
            // Header extension header
            "bede0004" +
            // Other header extensions
            "3202f824" +
            "511a19" +
            // AV1 DD
            "b4c303cd401c" +
            // Padding
            "000000" +
            // AV1 Media payload, truncated.
            "edbbdd501a87000000027e016704ff02",
        seq
    )

    private fun packet(hex: String, seq: Int): RtpPacket {
        val data = DatatypeConverter.parseHexBinary(hex)
        return RtpPacket(data, 0, data.size).apply { sequenceNumber = seq }
    }

    init {
        context("A retransmitted packet that arrives late") {
            /* Production case: the sender sends many keyframes, and each one has a new structure. The bridge sends
             * NACKs, and the retransmissions arrive more than TEMPLATE_HISTORY_SIZE packets after the newest
             * packet. The parser has dropped the structure of the retransmitted packet from its history, so it
             * cannot parse the DD of the packet. The frame of the packet, which the bridge already accepted, knows
             * the structure, and the packet can be parsed again with it. */
            should("be parsed again with the structure of its keyframe") {
                val keyframe = parser.createFrom(keyframe(1000), AV1_DD_HEADER_EXTENSION_ID)
                val structure = keyframe.descriptor!!.structure
                val onTime = parser.createFrom(following(1001), AV1_DD_HEADER_EXTENSION_ID)
                onTime.descriptor shouldNotBe null
                // The next keyframe, with a new structure.
                parser.createFrom(keyframe(1100), AV1_DD_HEADER_EXTENSION_ID).descriptor shouldNotBe null
                for (seq in 1101..1700) {
                    parser.createFrom(following(seq), AV1_DD_HEADER_EXTENSION_ID)
                }

                // A retransmission of a packet of the first keyframe frame.
                val late = parser.createFrom(following(1010), AV1_DD_HEADER_EXTENSION_ID)
                late.descriptor shouldBe null
                late.frameInfo shouldBe null

                late.updateParse(structure, logger) shouldBe true
                late.descriptor shouldNotBe null
                late.statelessDescriptor shouldBeSameInstanceAs late.descriptor
                late.frameInfo shouldBe onTime.frameInfo
                late.descriptor!!.activeDecodeTargetsBitmask shouldBe onTime.descriptor!!.activeDecodeTargetsBitmask

                /* Structures are immutable, so the descriptor shares the one passed in (e.g. by a frame). */
                late.descriptor!!.structure shouldBeSameInstanceAs structure
            }
            should("be parsed when it is not later than the history size") {
                parser.createFrom(keyframe(1000), AV1_DD_HEADER_EXTENSION_ID)
                parser.createFrom(keyframe(1100), AV1_DD_HEADER_EXTENSION_ID)
                for (seq in 1101..1400) {
                    parser.createFrom(following(seq), AV1_DD_HEADER_EXTENSION_ID)
                }
                parser.createFrom(following(1010), AV1_DD_HEADER_EXTENSION_ID).descriptor shouldNotBe null
            }
        }
    }

    companion object {
        const val AV1_DD_HEADER_EXTENSION_ID = 11
    }
}
