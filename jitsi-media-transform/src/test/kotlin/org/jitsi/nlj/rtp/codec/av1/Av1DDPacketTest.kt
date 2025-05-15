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
package org.jitsi.nlj.rtp.codec.av1

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.beInstanceOf
import org.jitsi.nlj.RtpEncodingDesc
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.utils.logging2.LoggerImpl
import javax.xml.bind.DatatypeConverter

class Av1DDPacketTest : ShouldSpec() {
    val logger = LoggerImpl(javaClass.name)

    private data class SampleAv1DDPacket(
        val description: String,
        val data: ByteArray,
        val structureSource: SampleAv1DDPacket?,
        // ...
        val scalabilityStructure: RtpEncodingDesc? = null

    ) {
        constructor(
            description: String,
            hexData: String,
            structureSource: SampleAv1DDPacket?,
            // ...
            scalabilityStructure: RtpEncodingDesc? = null
        ) : this(
            description = description,
            data = DatatypeConverter.parseHexBinary(hexData),
            structureSource = structureSource,
            // ...
            scalabilityStructure = scalabilityStructure
        )
    }

    /* Packets captured from AV1 test calls */

    private val nonScalableKeyframe =
        SampleAv1DDPacket(
            "non-scalable keyframe",
            // RTP header
            "902939b91ff6a695114ed316" +
                // Header extension header
                "bede0006" +
                // Other header extensions
                "3248c482" +
                "510002" +
                // AV1 DD
                "bc80000180003a410180ef808680" +
                // Padding
                "000000" +
                // AV1 Media payload, truncated.
                "680b0800000004477e1a00d004301061",
            null,
            RtpEncodingDesc(
                0x114ed316L,
                arrayOf(
                    Av1DDRtpLayerDesc(0, 0, 0, 0, 270, 30.0)
                )
            )
        )

    private val scalableKeyframe =
        SampleAv1DDPacket(
            "scalable keyframe",
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
            null,
            RtpEncodingDesc(
                0x2ab2873cL,
                arrayOf(
                    Av1DDRtpLayerDesc(0, 0, 0, 0, 180, 7.5),
                    Av1DDRtpLayerDesc(0, 1, 1, 0, 180, 15.0),
                    Av1DDRtpLayerDesc(0, 2, 2, 0, 180, 30.0),
                    Av1DDRtpLayerDesc(0, 3, 1, 0, 360, 7.5),
                    Av1DDRtpLayerDesc(0, 4, 1, 1, 360, 15.0),
                    Av1DDRtpLayerDesc(0, 5, 1, 2, 360, 30.0),
                    Av1DDRtpLayerDesc(0, 6, 2, 0, 720, 7.5),
                    Av1DDRtpLayerDesc(0, 7, 2, 1, 720, 15.0),
                    Av1DDRtpLayerDesc(0, 8, 2, 2, 720, 30.0)
                )
            )
        )

    private val testPackets = arrayOf(
        nonScalableKeyframe,
        SampleAv1DDPacket(
            "non-scalable following packet",
            // RTP header
            "90a939ba1ff6a695114ed316" +
                // Header extension header
                "bede0003" +
                // Other header extensions
                "3248d02a" +
                "510003" +
                // AV1 DD
                "b2400001" +
                // Padding
                "00" +
                // AV1 Media payload, truncated.
                "9057f7c3f51ba803b0c397e938589750",
            nonScalableKeyframe
        ),
        scalableKeyframe,
        SampleAv1DDPacket(
            "scalable following packet changing available DTs",
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
            scalableKeyframe,
            RtpEncodingDesc(
                0x2ab2873cL,
                arrayOf(
                    Av1DDRtpLayerDesc(0, 0, 0, 0, 180, 7.5),
                    Av1DDRtpLayerDesc(0, 1, 0, 1, 180, 15.0),
                    Av1DDRtpLayerDesc(0, 2, 0, 2, 180, 30.0),
                )
            )
        ),
        SampleAv1DDPacket(
            "temporally-scalable portrait-mode keyframe",
            // RTP header
            "906487db2fb5ae06481d4dca" +
                // Two-byte header extension header
                "1000000f" +
                // Other header extensions
                "030387b1a4" +
                "0502827e" +
                // AV1 DD
                "0b178023c58002044eaaaf2860414d34538a094040b3c13fc0" +
                // VLA
                "0c17a1009601f403dc0b00b3013f060167027f0602cf04ff06" +
                // padding
                "00" +
                // Media payload, truncated
                "109096029d012ad0020005396b00",
            null,
            RtpEncodingDesc(
                0x481d4dcaL,
                arrayOf(
                    // Make sure the correct "height" value is picked from portrait mode (720x1280)
                    // TODO: I think the frame rate calculation gets this DD wrong, but I'm not quite sure
                    //  what structure the encoder is using
                    Av1DDRtpLayerDesc(0, 0, 0, 0, 720, 10.0),
                    Av1DDRtpLayerDesc(0, 1, 1, 0, 720, 20.0),
                    Av1DDRtpLayerDesc(0, 2, 2, 0, 720, 30.0),
                )
            )

        )
    )

    init {
        context("AV1 packets") {
            should("be parsed correctly") {
                for (t in testPackets) {
                    withClue(t.description) {
                        val structure = if (t.structureSource != null) {
                            val sourceR =
                                RtpPacket(t.structureSource.data, 0, t.structureSource.data.size)
                            val sourceP = Av1DDPacket(sourceR, AV1_DD_HEADER_EXTENSION_ID, null, logger)
                            sourceP.descriptor?.structure
                        } else {
                            null
                        }

                        val r = RtpPacket(t.data, 0, t.data.size)
                        val p = Av1DDPacket(r, AV1_DD_HEADER_EXTENSION_ID, structure, logger)

                        if (t.scalabilityStructure != null) {
                            val tss = t.scalabilityStructure
                            val ss = p.getScalabilityStructure()
                            ss shouldNotBe null
                            ss!!.primarySSRC shouldBe tss.primarySSRC
                            ss.layers.size shouldBe tss.layers.size
                            for ((index, layer) in ss.layers.withIndex()) {
                                val tLayer = tss.layers[index]
                                layer.layerId shouldBe tLayer.layerId
                                layer.index shouldBe tLayer.index
                                layer should beInstanceOf<Av1DDRtpLayerDesc>()
                                layer as Av1DDRtpLayerDesc
                                tLayer as Av1DDRtpLayerDesc
                                layer.dt shouldBe tLayer.dt
                                layer.height shouldBe tLayer.height
                                layer.frameRate shouldBe tLayer.frameRate
                            }
                        } else {
                            p.getScalabilityStructure() shouldBe null
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val AV1_DD_HEADER_EXTENSION_ID = 11
    }
}
