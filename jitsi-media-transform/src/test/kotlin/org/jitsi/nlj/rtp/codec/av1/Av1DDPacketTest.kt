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

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.beInstanceOf
import org.jitsi.nlj.RtpEncodingDesc
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.rtp.rtp.header_extensions.Av1DependencyException
import org.jitsi.rtp.rtp.header_extensions.Av1TemplateDependencyStructure
import org.jitsi.rtp.util.BitWriter
import org.jitsi.utils.logging2.LoggerImpl
import java.util.function.Supplier
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
        context("An AV1 DD which carries a malformed template dependency structure") {
            /* A structure with one more template than the 6-bit template ID can address. It cannot be parsed with
             * or without a structure in effect, so the packet cannot be used, and the constructor fails. */
            val malformedDd = ByteArray(24)
            BitWriter(malformedDd, 0, malformedDd.size).apply {
                writeBit(true) // startOfFrame
                writeBit(true) // endOfFrame
                writeBits(6, 0) // frameDependencyTemplateId
                writeBits(16, 1) // frameNumber
                writeBit(true) // templateDependencyStructurePresent
                writeBits(4, 0) // activeDecodeTargetsPresent, customDtisFlag, customFdiffsFlag, customChainsFlag
                writeBits(6, 0) // templateIdOffset
                writeBits(5, 0) // dtCntMinusOne
                repeat(Av1TemplateDependencyStructure.MAX_TEMPLATE_COUNT) { writeBits(2, 0) } // One template too many
                writeBits(2, 3)
            }
            val data = scalableKeyframe.data.clone()
            val rtpPacket = RtpPacket(data, 0, data.size).apply {
                removeHeaderExtension(AV1_DD_HEADER_EXTENSION_ID)
                val ext = addHeaderExtension(AV1_DD_HEADER_EXTENSION_ID, malformedDd.size)
                System.arraycopy(malformedDd, 0, ext.buffer, ext.dataOffset, malformedDd.size)
                encodeHeaderExtensions()
            }

            should("fail to construct with one exception, and log nothing") {
                val recordingLogger = WarningRecordingLogger()
                val e = shouldThrow<MalformedAv1DependencyDescriptorException> {
                    Av1DDPacket(rtpPacket, AV1_DD_HEADER_EXTENSION_ID, null, recordingLogger)
                }
                e.message shouldContain "Malformed AV1 Dependency Descriptor"
                e.message shouldContain "more than ${Av1TemplateDependencyStructure.MAX_TEMPLATE_COUNT} templates"
                e.cause should beInstanceOf<Av1DependencyException>()
                recordingLogger.warnings shouldBe emptyList()
            }
        }
        context("An AV1 DD that does not match the known template dependency structure") {
            /* The sender changed to a new structure with two chains, and the packet that carried the new structure
             * was lost. The bridge still has the older structure with three chains. The DD of the next packets has
             * custom fdiffs and two custom chains, and does not fit the older structure. The packet must be
             * constructed without a descriptor (and without frame info), not fail with an exception. */
            val staleStructure = scalableKeyframe.data.let {
                Av1DDPacket(RtpPacket(it, 0, it.size), AV1_DD_HEADER_EXTENSION_ID, null, logger)
            }.descriptor!!.structure

            should("not throw when the template ID is not in the structure") {
                staleStructure.chainCount shouldBe 3
                ((36 + 64 - staleStructure.templateIdOffset) % 64 >= staleStructure.templateCount) shouldBe true

                val data = DatatypeConverter.parseHexBinary(packetWithTwoCustomChains("e4"))
                val p = Av1DDPacket(RtpPacket(data, 0, data.size), AV1_DD_HEADER_EXTENSION_ID, staleStructure, logger)
                p.descriptor shouldBe null
                p.frameInfo shouldBe null
                p.isKeyframe shouldBe false
                p.statelessDescriptor.frameDependencyTemplateId shouldBe 36
                p.statelessDescriptor.frameNumber shouldBe 0x9f3f
            }
            should("not throw when the custom chains do not fit in the DD") {
                ((1 + 64 - staleStructure.templateIdOffset) % 64 < staleStructure.templateCount) shouldBe true

                val data = DatatypeConverter.parseHexBinary(packetWithTwoCustomChains("c1"))
                val p = Av1DDPacket(RtpPacket(data, 0, data.size), AV1_DD_HEADER_EXTENSION_ID, staleStructure, logger)
                p.descriptor shouldBe null
                p.frameInfo shouldBe null
                p.isKeyframe shouldBe false
                p.statelessDescriptor.frameDependencyTemplateId shouldBe 1
                p.statelessDescriptor.frameNumber shouldBe 0x9f3f
            }
        }
    }

    /**
     * A packet from a production log (AV1, ext ID 11). Its 7-byte DD is: SOF=1, EOF=1, template ID (from
     * [firstDdByte]), frame number 0x9f3f, custom fdiffs ([4]) and two custom chains ([4, 0]), then 3 bits of zero
     * padding.
     */
    private fun packetWithTwoCustomChains(firstDdByte: String) = "90a9efa4977b9143c35fa97f" + // RTP header
        "bede0004" + // Header extension header
        "322b0fba" + // Other header extensions
        "510ea4" +
        "b6" + firstDdByte + "9f3f1a602000" + // AV1 DD
        "00" + // Padding
        "1034003100200000e6a5c43403c001de" // AV1 Media payload, truncated.

    companion object {
        const val AV1_DD_HEADER_EXTENSION_ID = 11
    }
}

/** A logger which records the messages logged at WARN. */
private class WarningRecordingLogger : LoggerImpl("Av1DDPacketTest") {
    val warnings = mutableListOf<String>()

    override fun warn(msg: Any?) {
        warnings.add("$msg")
    }

    override fun warn(msg: Supplier<String>) {
        warnings.add(msg.get())
    }

    override fun warn(msg: Any?, t: Throwable?) {
        warnings.add("$msg: $t")
    }
}
