/*
 * Copyright @ 2019 - present 8x8, Inc
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

package org.jitsi.videobridge.cc.av1

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import jakarta.xml.bind.DatatypeConverter
import org.jitsi.nlj.rtp.codec.av1.Av1DDRtpLayerDesc
import org.jitsi.rtp.rtp.header_extensions.Av1DependencyDescriptorReader
import org.jitsi.rtp.rtp.header_extensions.Av1TemplateDependencyStructure
import org.jitsi.utils.logging2.LoggerImpl
import org.jitsi.utils.logging2.getClassForLogging
import java.time.Instant

internal class Av1DDQualityFilterTest : ShouldSpec() {
    init {
        context("A non-scalable stream") {
            should("be entirely projected") {
                val av1FrameMaps = HashMap<Long, Av1DDFrameMap>()

                val filter = Av1DDQualityFilter(av1FrameMaps, logger)
                val generator = SingleLayerFrameGenerator(av1FrameMaps)
                val targetIndex = Av1DDRtpLayerDesc.getIndex(0, 0)

                testGenerator(generator, filter, targetIndex) { _, result ->
                    result.accept shouldBe true
                    result.mark shouldBe true
                    filter.needsKeyframe shouldBe false
                }
            }
        }
        context("A temporally scalable stream") {
            should("be entirely projected when TL2 is requested") {
                val av1FrameMaps = HashMap<Long, Av1DDFrameMap>()

                val filter = Av1DDQualityFilter(av1FrameMaps, logger)
                val generator = TemporallyScaledFrameGenerator(av1FrameMaps)
                val targetIndex = Av1DDRtpLayerDesc.getIndex(0, 2)

                testGenerator(generator, filter, targetIndex) { _, result ->
                    result.accept shouldBe true
                    result.mark shouldBe true
                    filter.needsKeyframe shouldBe false
                }
            }
            should("project only the base temporal layer when targeted") {
                val av1FrameMaps = HashMap<Long, Av1DDFrameMap>()

                val filter = Av1DDQualityFilter(av1FrameMaps, logger)
                val generator = TemporallyScaledFrameGenerator(av1FrameMaps)
                val targetIndex = Av1DDRtpLayerDesc.getIndex(0, 0)

                testGenerator(generator, filter, targetIndex) { f, result ->
                    result.accept shouldBe (f.frameInfo!!.temporalId == 0)
                    if (result.accept) {
                        result.mark shouldBe true
                        filter.needsKeyframe shouldBe false
                    }
                }
            }
            should("project only the intermediate temporal layer when targeted") {
                val av1FrameMaps = HashMap<Long, Av1DDFrameMap>()

                val filter = Av1DDQualityFilter(av1FrameMaps, logger)
                val generator = TemporallyScaledFrameGenerator(av1FrameMaps)
                val targetIndex = Av1DDRtpLayerDesc.getIndex(0, 1)

                testGenerator(generator, filter, targetIndex) { f, result ->
                    result.accept shouldBe (f.frameInfo!!.temporalId <= 1)
                    if (result.accept) {
                        result.mark shouldBe true
                        filter.needsKeyframe shouldBe false
                    }
                }
            }
            should("be able to switch the targeted layers, without a keyframe") {
                val av1FrameMaps = HashMap<Long, Av1DDFrameMap>()

                val filter = Av1DDQualityFilter(av1FrameMaps, logger)
                val generator = TemporallyScaledFrameGenerator(av1FrameMaps)
                val targetIndex1 = Av1DDRtpLayerDesc.getIndex(0, 0)

                testGenerator(generator, filter, targetIndex1, numFrames = 500) { f, result ->
                    result.accept shouldBe (f.frameInfo!!.temporalId == 0)
                    if (result.accept) {
                        result.mark shouldBe true
                        filter.needsKeyframe shouldBe false
                    }
                }
                val targetIndex2 = Av1DDRtpLayerDesc.getIndex(0, 2)

                testGenerator(generator, filter, targetIndex2) { _, result ->
                    result.accept shouldBe true
                    result.mark shouldBe true
                    filter.needsKeyframe shouldBe false
                }
            }
        }
        context("A spatially scalable stream") {
            should("be entirely projected when SL2/TL2 is requested (L3T3)") {
                val av1FrameMaps = HashMap<Long, Av1DDFrameMap>()

                val filter = Av1DDQualityFilter(av1FrameMaps, logger)
                val generator = SVCFrameGenerator(av1FrameMaps)
                val targetIndex = Av1DDRtpLayerDesc.getIndex(0, 3 * 2 + 2)

                testGenerator(generator, filter, targetIndex) { f, result ->
                    result.accept shouldBe true
                    result.mark shouldBe (f.frameInfo!!.spatialId == 2)
                    filter.needsKeyframe shouldBe false
                }
            }
            should("be able to be shaped to SL0/TL2 (L3T3)") {
                val av1FrameMaps = HashMap<Long, Av1DDFrameMap>()

                val filter = Av1DDQualityFilter(av1FrameMaps, logger)
                val generator = SVCFrameGenerator(av1FrameMaps)
                val targetIndex = Av1DDRtpLayerDesc.getIndex(0, 2)

                testGenerator(generator, filter, targetIndex) { f, result ->
                    result.accept shouldBe (f.frameInfo!!.spatialId == 0)
                    if (result.accept) {
                        result.mark shouldBe (f.frameInfo!!.spatialId == 0)
                        filter.needsKeyframe shouldBe false
                    }
                }
            }
            should("be able to be shaped to SL1/TL2 (L3T3)") {
                val av1FrameMaps = HashMap<Long, Av1DDFrameMap>()

                val filter = Av1DDQualityFilter(av1FrameMaps, logger)
                val generator = SVCFrameGenerator(av1FrameMaps)
                val targetIndex = Av1DDRtpLayerDesc.getIndex(0, 3 * 1 + 2)

                testGenerator(generator, filter, targetIndex) { f, result ->
                    result.accept shouldBe (f.frameInfo!!.spatialId <= 1)
                    if (result.accept) {
                        result.mark shouldBe (f.frameInfo!!.spatialId == 1)
                        filter.needsKeyframe shouldBe false
                    }
                }
            }
            should("be able to be shaped to SL2/TL0 (L3T3)") {
                val av1FrameMaps = HashMap<Long, Av1DDFrameMap>()

                val filter = Av1DDQualityFilter(av1FrameMaps, logger)
                val generator = SVCFrameGenerator(av1FrameMaps)
                val targetIndex = Av1DDRtpLayerDesc.getIndex(0, 3 * 2 + 0)

                testGenerator(generator, filter, targetIndex) { f, result ->
                    result.accept shouldBe (f.frameInfo!!.temporalId == 0)
                    if (result.accept) {
                        result.mark shouldBe (f.frameInfo!!.spatialId == 2)
                        filter.needsKeyframe shouldBe false
                    }
                }
            }
            should("be able to switch spatial layers (L3T3)") {
                val av1FrameMaps = HashMap<Long, Av1DDFrameMap>()

                val filter = Av1DDQualityFilter(av1FrameMaps, logger)
                val generator = SVCFrameGenerator(av1FrameMaps)

                /* Start by sending spatial layer 0. */
                val targetIndex1 = Av1DDRtpLayerDesc.getIndex(0, 2)

                testGenerator(generator, filter, targetIndex1, numFrames = 1200) { f, result ->
                    result.accept shouldBe (f.frameInfo!!.spatialId == 0)
                    if (result.accept) {
                        result.mark shouldBe (f.frameInfo!!.spatialId == 0)
                        filter.needsKeyframe shouldBe false
                    }
                }

                /* Switch to spatial layer 2.  Need a keyframe. */
                val targetIndex2 = Av1DDRtpLayerDesc.getIndex(0, 3 * 2 + 2)
                var sawKeyframe = false
                testGenerator(generator, filter, targetIndex2, numFrames = 1200) { f, result ->
                    if (f.isKeyframe) sawKeyframe = true
                    result.accept shouldBe if (!sawKeyframe) (f.frameInfo!!.spatialId == 0) else true
                    if (result.accept) {
                        result.mark shouldBe if (!sawKeyframe) {
                            (f.frameInfo!!.spatialId == 0)
                        } else {
                            (f.frameInfo!!.spatialId == 2)
                        }
                        filter.needsKeyframe shouldBe (!sawKeyframe)
                    }
                }

                /* Switch to spatial layer 1.  For SVC, dropping down in spatial layers can happen immediately. */
                val targetIndex3 = Av1DDRtpLayerDesc.getIndex(0, 3 * 1 + 2)
                testGenerator(generator, filter, targetIndex3) { f, result ->
                    result.accept shouldBe (f.frameInfo!!.spatialId <= 1)
                    if (result.accept) {
                        result.mark shouldBe (f.frameInfo!!.spatialId == 1)
                        filter.needsKeyframe shouldBe false
                    }
                }
            }
        }
        context("A K-SVC spatially scalable stream") {
            should("be able to be shaped to SL2/TL2 (L3T3_KEY)") {
                val av1FrameMaps = HashMap<Long, Av1DDFrameMap>()

                val filter = Av1DDQualityFilter(av1FrameMaps, logger)
                val generator = KSVCFrameGenerator(av1FrameMaps)
                val targetIndex = Av1DDRtpLayerDesc.getIndex(0, 3 * 2 + 2)

                testGenerator(generator, filter, targetIndex) { f, result ->
                    result.accept shouldBe (
                        f.frameInfo!!.spatialId == 2 || !f.frameInfo!!.hasInterPictureDependency()
                        )
                    result.mark shouldBe (f.frameInfo!!.spatialId == 2)
                    filter.needsKeyframe shouldBe false
                }
            }
            should("be able to be shaped to SL0/TL2 (L3T3_KEY)") {
                val av1FrameMaps = HashMap<Long, Av1DDFrameMap>()

                val filter = Av1DDQualityFilter(av1FrameMaps, logger)
                val generator = KSVCFrameGenerator(av1FrameMaps)
                val targetIndex = Av1DDRtpLayerDesc.getIndex(0, 2)

                testGenerator(generator, filter, targetIndex) { f, result ->
                    result.accept shouldBe (f.frameInfo!!.spatialId == 0)
                    if (result.accept) {
                        result.mark shouldBe (f.frameInfo!!.spatialId == 0)
                        filter.needsKeyframe shouldBe false
                    }
                }
            }
            should("be able to be shaped to SL1/TL2 (L3T3_KEY)") {
                val av1FrameMaps = HashMap<Long, Av1DDFrameMap>()

                val filter = Av1DDQualityFilter(av1FrameMaps, logger)
                val generator = KSVCFrameGenerator(av1FrameMaps)
                val targetIndex = Av1DDRtpLayerDesc.getIndex(0, 3 * 1 + 2)

                testGenerator(generator, filter, targetIndex) { f, result ->
                    result.accept shouldBe (
                        f.frameInfo!!.spatialId == 1 || (
                            f.frameInfo!!.spatialId == 0 && !f.frameInfo!!.hasInterPictureDependency()
                            )
                        )
                    if (result.accept) {
                        result.mark shouldBe (f.frameInfo!!.spatialId == 1)
                        filter.needsKeyframe shouldBe false
                    }
                }
            }
            should("be able to be shaped to SL2/TL0 (L3T3_KEY)") {
                val av1FrameMaps = HashMap<Long, Av1DDFrameMap>()

                val filter = Av1DDQualityFilter(av1FrameMaps, logger)
                val generator = KSVCFrameGenerator(av1FrameMaps)
                val targetIndex = Av1DDRtpLayerDesc.getIndex(0, 3 * 2 + 0)

                testGenerator(generator, filter, targetIndex) { f, result ->
                    result.accept shouldBe (
                        f.frameInfo!!.temporalId == 0 && (
                            f.frameInfo!!.spatialId == 2 || !f.frameInfo!!.hasInterPictureDependency()
                            )
                        )
                    if (result.accept) {
                        result.mark shouldBe (f.frameInfo!!.spatialId == 2)
                        filter.needsKeyframe shouldBe false
                    }
                }
            }
            should("be able to switch spatial layers (L3T3_KEY)") {
                val av1FrameMaps = HashMap<Long, Av1DDFrameMap>()

                val filter = Av1DDQualityFilter(av1FrameMaps, logger)
                val generator = KSVCFrameGenerator(av1FrameMaps)

                /* Start by sending spatial layer 0. */
                val targetIndex1 = Av1DDRtpLayerDesc.getIndex(0, 2)

                testGenerator(generator, filter, targetIndex1, numFrames = 1200) { f, result ->
                    result.accept shouldBe (f.frameInfo!!.spatialId == 0)
                    if (result.accept) {
                        result.mark shouldBe (f.frameInfo!!.spatialId == 0)
                        filter.needsKeyframe shouldBe false
                    }
                }

                /* Switch to spatial layer 2.  Need a keyframe. */
                val targetIndex2 = Av1DDRtpLayerDesc.getIndex(0, 3 * 2 + 2)
                var sawKeyframe = false
                testGenerator(generator, filter, targetIndex2, numFrames = 1200) { f, result ->
                    if (f.isKeyframe) sawKeyframe = true
                    result.accept shouldBe if (!sawKeyframe) {
                        (f.frameInfo!!.spatialId == 0)
                    } else {
                        (f.frameInfo!!.spatialId == 2 || !f.frameInfo!!.hasInterPictureDependency())
                    }
                    if (result.accept) {
                        result.mark shouldBe if (!sawKeyframe) {
                            (f.frameInfo!!.spatialId == 0)
                        } else {
                            (f.frameInfo!!.spatialId == 2)
                        }
                        filter.needsKeyframe shouldBe (!sawKeyframe)
                    }
                }

                /* Switch to spatial layer 1.  For K-SVC, dropping down in spatial layers needs a keyframe. */
                val targetIndex3 = Av1DDRtpLayerDesc.getIndex(0, 3 * 1 + 2)
                sawKeyframe = false
                testGenerator(generator, filter, targetIndex3) { f, result ->
                    if (f.isKeyframe) sawKeyframe = true
                    result.accept shouldBe if (!sawKeyframe) {
                        (f.frameInfo!!.spatialId == 2 || !f.frameInfo!!.hasInterPictureDependency())
                    } else {
                        (
                            f.frameInfo!!.spatialId == 1 || (
                                f.frameInfo!!.spatialId == 0 && !f.frameInfo!!.hasInterPictureDependency()
                                )
                            )
                    }
                    if (result.accept) {
                        result.mark shouldBe if (!sawKeyframe) {
                            (f.frameInfo!!.spatialId == 2)
                        } else {
                            (f.frameInfo!!.spatialId == 1)
                        }
                        filter.needsKeyframe shouldBe (!sawKeyframe)
                    }
                }
            }
        }
        context("A K-SVC spatially scalable stream with a temporal shift") {
            should("be able to be shaped to SL1/TL1 (L2S2_KEY_SHIFT)") {
                val av1FrameMaps = HashMap<Long, Av1DDFrameMap>()

                val filter = Av1DDQualityFilter(av1FrameMaps, logger)
                val generator = KSVCShiftFrameGenerator(av1FrameMaps)
                val targetIndex = Av1DDRtpLayerDesc.getIndex(0, 2 * 1 + 1)

                testGenerator(generator, filter, targetIndex) { f, result ->
                    result.accept shouldBe (
                        f.frameInfo!!.spatialId == 1 || !f.frameInfo!!.hasInterPictureDependency()
                        )
                    result.mark shouldBe (f.frameInfo!!.spatialId == 1)
                    filter.needsKeyframe shouldBe false
                }
            }
            should("be able to be shaped to SL0/TL1 (L2S2_KEY_SHIFT)") {
                val av1FrameMaps = HashMap<Long, Av1DDFrameMap>()

                val filter = Av1DDQualityFilter(av1FrameMaps, logger)
                val generator = KSVCShiftFrameGenerator(av1FrameMaps)
                val targetIndex = Av1DDRtpLayerDesc.getIndex(0, 1)

                testGenerator(generator, filter, targetIndex) { f, result ->
                    result.accept shouldBe (f.frameInfo!!.spatialId == 0)
                    if (result.accept) {
                        result.mark shouldBe (f.frameInfo!!.spatialId == 0)
                        filter.needsKeyframe shouldBe false
                    }
                }
            }
            should("be able to be shaped to SL1/TL0 (L2S2_KEY_SHIFT)") {
                val av1FrameMaps = HashMap<Long, Av1DDFrameMap>()

                val filter = Av1DDQualityFilter(av1FrameMaps, logger)
                val generator = KSVCShiftFrameGenerator(av1FrameMaps)
                val targetIndex = Av1DDRtpLayerDesc.getIndex(0, 2 * 1 + 0)

                testGenerator(generator, filter, targetIndex) { f, result ->
                    result.accept shouldBe (
                        f.frameInfo!!.temporalId == 0 && (
                            f.frameInfo!!.spatialId == 1 || !f.frameInfo!!.hasInterPictureDependency()
                            )
                        )
                    if (result.accept) {
                        result.mark shouldBe (f.frameInfo!!.spatialId == 1)
                        filter.needsKeyframe shouldBe false
                    }
                }
            }
            should("be able to switch spatial layers (L2S2_KEY_SHIFT)") {
                val av1FrameMaps = HashMap<Long, Av1DDFrameMap>()

                val filter = Av1DDQualityFilter(av1FrameMaps, logger)
                val generator = KSVCShiftFrameGenerator(av1FrameMaps)

                /* Start by sending spatial layer 0. */
                val targetIndex1 = Av1DDRtpLayerDesc.getIndex(0, 1)

                testGenerator(generator, filter, targetIndex1, numFrames = 1200) { f, result ->
                    result.accept shouldBe (f.frameInfo!!.spatialId == 0)
                    if (result.accept) {
                        result.mark shouldBe (f.frameInfo!!.spatialId == 0)
                        filter.needsKeyframe shouldBe false
                    }
                }

                /* Switch to spatial layer 1.  Need a keyframe. */
                val targetIndex2 = Av1DDRtpLayerDesc.getIndex(0, 2 * 1 + 1)
                var sawKeyframe = false
                testGenerator(generator, filter, targetIndex2, numFrames = 1200) { f, result ->
                    if (f.isKeyframe) sawKeyframe = true
                    result.accept shouldBe if (!sawKeyframe) {
                        (f.frameInfo!!.spatialId == 0)
                    } else {
                        (f.frameInfo!!.spatialId == 1 || !f.frameInfo!!.hasInterPictureDependency())
                    }
                    if (result.accept) {
                        result.mark shouldBe if (!sawKeyframe) {
                            (f.frameInfo!!.spatialId == 0)
                        } else {
                            (f.frameInfo!!.spatialId == 1)
                        }
                        filter.needsKeyframe shouldBe (!sawKeyframe)
                    }
                }

                /* Switch back to spatial layer 0.  For K-SVC, dropping down in spatial layers needs a keyframe. */
                val targetIndex3 = Av1DDRtpLayerDesc.getIndex(0, 1)
                sawKeyframe = false
                testGenerator(generator, filter, targetIndex3) { f, result ->
                    if (f.isKeyframe) sawKeyframe = true
                    result.accept shouldBe if (!sawKeyframe) {
                        (f.frameInfo!!.spatialId == 1 || !f.frameInfo!!.hasInterPictureDependency())
                    } else {
                        (f.frameInfo!!.spatialId == 0)
                    }
                    if (result.accept) {
                        result.mark shouldBe if (!sawKeyframe) {
                            (f.frameInfo!!.spatialId == 1)
                        } else {
                            (f.frameInfo!!.spatialId == 0)
                        }
                        filter.needsKeyframe shouldBe (!sawKeyframe)
                    }
                }
            }
        }
        context("A single-encoding simulcast stream") {
            should("project all of layer 2 when when SL2/TL2 is requested (S3T3)") {
                val av1FrameMaps = HashMap<Long, Av1DDFrameMap>()

                val filter = Av1DDQualityFilter(av1FrameMaps, logger)
                val generator = SingleEncodingSimulcastGenerator(av1FrameMaps)
                val targetIndex = Av1DDRtpLayerDesc.getIndex(0, 3 * 2 + 2)

                testGenerator(generator, filter, targetIndex) { f, result ->
                    result.accept shouldBe (f.frameInfo!!.spatialId == 2)
                    if (result.accept) {
                        result.mark shouldBe true
                        filter.needsKeyframe shouldBe false
                    }
                }
            }
            should("be able to be shaped to SL0/TL2 (S3T3)") {
                val av1FrameMaps = HashMap<Long, Av1DDFrameMap>()

                val filter = Av1DDQualityFilter(av1FrameMaps, logger)
                val generator = SingleEncodingSimulcastGenerator(av1FrameMaps)
                val targetIndex = Av1DDRtpLayerDesc.getIndex(0, 2)

                testGenerator(generator, filter, targetIndex) { f, result ->
                    result.accept shouldBe (f.frameInfo!!.spatialId == 0)
                    if (result.accept) {
                        result.mark shouldBe true
                        filter.needsKeyframe shouldBe false
                    }
                }
            }
            should("be able to be shaped to SL1/TL2 (S3T3)") {
                val av1FrameMaps = HashMap<Long, Av1DDFrameMap>()

                val filter = Av1DDQualityFilter(av1FrameMaps, logger)
                val generator = SingleEncodingSimulcastGenerator(av1FrameMaps)
                val targetIndex = Av1DDRtpLayerDesc.getIndex(0, 3 * 1 + 2)

                testGenerator(generator, filter, targetIndex) { f, result ->
                    result.accept shouldBe (f.frameInfo!!.spatialId == 1)
                    if (result.accept) {
                        result.mark shouldBe true
                        filter.needsKeyframe shouldBe false
                    }
                }
            }
            should("be able to be shaped to SL2/TL0 (S3T3)") {
                val av1FrameMaps = HashMap<Long, Av1DDFrameMap>()

                val filter = Av1DDQualityFilter(av1FrameMaps, logger)
                val generator = SingleEncodingSimulcastGenerator(av1FrameMaps)
                val targetIndex = Av1DDRtpLayerDesc.getIndex(0, 3 * 2 + 0)

                testGenerator(generator, filter, targetIndex) { f, result ->
                    result.accept shouldBe (f.frameInfo!!.spatialId == 2 && f.frameInfo!!.temporalId == 0)
                    if (result.accept) {
                        result.mark shouldBe true
                        filter.needsKeyframe shouldBe false
                    }
                }
            }
            should("be able to switch spatial layers (S3T3)") {
                val av1FrameMaps = HashMap<Long, Av1DDFrameMap>()

                val filter = Av1DDQualityFilter(av1FrameMaps, logger)
                val generator = SingleEncodingSimulcastGenerator(av1FrameMaps)

                /* Start by sending spatial layer 0. */
                val targetIndex1 = Av1DDRtpLayerDesc.getIndex(0, 2)

                testGenerator(generator, filter, targetIndex1, numFrames = 1200) { f, result ->
                    result.accept shouldBe (f.frameInfo!!.spatialId == 0)
                    if (result.accept) {
                        result.mark shouldBe true
                        filter.needsKeyframe shouldBe false
                    }
                }

                /* Switch to spatial layer 2.  Need a keyframe. */
                val targetIndex2 = Av1DDRtpLayerDesc.getIndex(0, 3 * 2 + 2)
                var sawKeyframe = false
                testGenerator(generator, filter, targetIndex2, numFrames = 1200) { f, result ->
                    if (f.isKeyframe) sawKeyframe = true
                    result.accept shouldBe if (!sawKeyframe) {
                        (f.frameInfo!!.spatialId == 0)
                    } else {
                        (f.frameInfo!!.spatialId == 2)
                    }
                    if (result.accept) {
                        result.mark shouldBe true
                        filter.needsKeyframe shouldBe (!sawKeyframe)
                    }
                }

                /* Switch to spatial layer 1.  Need a keyframe. */
                val targetIndex3 = Av1DDRtpLayerDesc.getIndex(0, 3 * 1 + 2)
                sawKeyframe = false
                testGenerator(generator, filter, targetIndex3) { f, result ->
                    if (f.isKeyframe) sawKeyframe = true
                    result.accept shouldBe if (!sawKeyframe) {
                        (f.frameInfo!!.spatialId == 2)
                    } else {
                        (f.frameInfo!!.spatialId == 1)
                    }
                    if (result.accept) {
                        result.mark shouldBe true
                        filter.needsKeyframe shouldBe (!sawKeyframe)
                    }
                }
            }
        }
        context("A multi-encoding simulcast stream") {
            should("project all of encoding 2 when when Enc 2/TL2 is requested") {
                val av1FrameMaps = HashMap<Long, Av1DDFrameMap>()

                val filter = Av1DDQualityFilter(av1FrameMaps, logger)
                val generator = MultiEncodingSimulcastGenerator(av1FrameMaps)
                val targetIndex = Av1DDRtpLayerDesc.getIndex(2, 2)

                testGenerator(generator, filter, targetIndex) { f, result ->
                    result.accept shouldBe (f.ssrc == 2L || f.isKeyframe)
                    if (result.accept) {
                        result.mark shouldBe true
                        filter.needsKeyframe shouldBe false
                    }
                }
            }
            should("be able to be shaped to Enc 0/TL2") {
                val av1FrameMaps = HashMap<Long, Av1DDFrameMap>()

                val filter = Av1DDQualityFilter(av1FrameMaps, logger)
                val generator = MultiEncodingSimulcastGenerator(av1FrameMaps)
                val targetIndex = Av1DDRtpLayerDesc.getIndex(0, 2)

                testGenerator(generator, filter, targetIndex) { f, result ->
                    result.accept shouldBe (f.ssrc == 0L)
                    if (result.accept) {
                        result.mark shouldBe true
                        filter.needsKeyframe shouldBe false
                    }
                }
            }
            should("be able to be shaped to Enc 1/TL2") {
                val av1FrameMaps = HashMap<Long, Av1DDFrameMap>()

                val filter = Av1DDQualityFilter(av1FrameMaps, logger)
                val generator = MultiEncodingSimulcastGenerator(av1FrameMaps)
                val targetIndex = Av1DDRtpLayerDesc.getIndex(1, 2)

                testGenerator(generator, filter, targetIndex) { f, result ->
                    result.accept shouldBe (f.ssrc == 1L || (f.isKeyframe && f.ssrc == 0L))
                    if (result.accept) {
                        result.mark shouldBe true
                        filter.needsKeyframe shouldBe false
                    }
                }
            }
            should("be able to be shaped to Enc 2/TL0") {
                val av1FrameMaps = HashMap<Long, Av1DDFrameMap>()

                val filter = Av1DDQualityFilter(av1FrameMaps, logger)
                val generator = MultiEncodingSimulcastGenerator(av1FrameMaps)
                val targetIndex = Av1DDRtpLayerDesc.getIndex(2, 0)

                testGenerator(generator, filter, targetIndex) { f, result ->
                    result.accept shouldBe ((f.ssrc == 2L || f.isKeyframe) && f.frameInfo!!.temporalId == 0)
                    if (result.accept) {
                        result.mark shouldBe true
                        filter.needsKeyframe shouldBe false
                    }
                }
            }
            should("be able to switch encodings") {
                val av1FrameMaps = HashMap<Long, Av1DDFrameMap>()

                val filter = Av1DDQualityFilter(av1FrameMaps, logger)
                val generator = MultiEncodingSimulcastGenerator(av1FrameMaps)

                /* Start by sending encoding 0. */
                val targetIndex1 = Av1DDRtpLayerDesc.getIndex(0, 2)

                testGenerator(generator, filter, targetIndex1, numFrames = 1200) { f, result ->
                    result.accept shouldBe (f.ssrc == 0L)
                    if (result.accept) {
                        result.mark shouldBe true
                        filter.needsKeyframe shouldBe false
                    }
                }

                /* Switch to encoding 2.  Need a keyframe. */
                val targetIndex2 = Av1DDRtpLayerDesc.getIndex(2, 2)
                var sawKeyframe = false
                testGenerator(generator, filter, targetIndex2, numFrames = 1200) { f, result ->
                    if (f.isKeyframe) sawKeyframe = true
                    result.accept shouldBe if (!sawKeyframe) {
                        (f.ssrc == 0L)
                    } else {
                        (f.ssrc == 2L || f.isKeyframe)
                    }
                    if (result.accept) {
                        result.mark shouldBe true
                        filter.needsKeyframe shouldBe (!sawKeyframe)
                    }
                }

                /* Switch to encoding 1.  Need a keyframe. */
                val targetIndex3 = Av1DDRtpLayerDesc.getIndex(1, 2)
                sawKeyframe = false
                testGenerator(generator, filter, targetIndex3) { f, result ->
                    if (f.isKeyframe) sawKeyframe = true
                    result.accept shouldBe if (!sawKeyframe) {
                        // We don't send discardable frames for the DT while there's a pending encoding downswitch
                        (f.ssrc == 2L && f.frameInfo!!.temporalId != 2)
                    } else {
                        (f.ssrc == 1L || (f.ssrc == 0L && f.isKeyframe))
                    }
                    if (result.accept) {
                        result.mark shouldBe true
                        filter.needsKeyframe shouldBe (!sawKeyframe)
                    }
                }
            }
        }
    }

    private fun testGenerator(
        g: FrameGenerator,
        filter: Av1DDQualityFilter,
        targetIndex: Int,
        numFrames: Int = Int.MAX_VALUE,
        evaluator: (Av1DDFrame, Av1DDQualityFilter.AcceptResult) -> Unit
    ) {
        var lastTs = -1L
        var ms = -1L
        var frames = 0
        while (g.hasNext() && frames < numFrames) {
            val f = g.next()

            ms = if (f.timestamp != lastTs) {
                f.timestamp / 90
            } else {
                ms + 1
            }
            lastTs = f.timestamp

            val result = filter.acceptFrame(
                frame = f,
                externalTargetIndex = targetIndex,
                incomingEncoding = f.ssrc.toInt(),
                receivedTime = Instant.ofEpochMilli(ms)
            )
            f.isAccepted = result.accept
            evaluator(f, result)
            frames++
        }
    }

    companion object {
        val logger = LoggerImpl(getClassForLogging(this::class.java).name)
    }
}

private abstract class FrameGenerator : Iterator<Av1DDFrame>

private open class DDBasedGenerator(
    val av1FrameMaps: HashMap<Long, Av1DDFrameMap>,
    val keyframeInterval: Int,
    val keyframeTemplates: Array<Int>,
    val normalTemplates: Array<Int>,
    ddHex: String
) : FrameGenerator() {
    private var frameCount = 0
    private val structure: Av1TemplateDependencyStructure

    init {
        val dd = DatatypeConverter.parseHexBinary(ddHex)
        structure = Av1DependencyDescriptorReader(dd, 0, dd.size).parse(null).structure
    }

    override fun hasNext(): Boolean = frameCount < TOTAL_FRAMES

    protected open fun isKeyframe(keyCycle: Int) = keyCycle == 0

    override fun next(): Av1DDFrame {
        val tCycle = frameCount % normalTemplates.size
        val keyCycle = frameCount % keyframeInterval

        val templateId = if (keyCycle < keyframeTemplates.size) {
            keyframeTemplates[tCycle]
        } else {
            normalTemplates[tCycle]
        }

        val f = Av1DDFrame(
            ssrc = 0,
            timestamp = frameCount * 3000L,
            earliestKnownSequenceNumber = frameCount,
            latestKnownSequenceNumber = frameCount,
            seenStartOfFrame = true,
            seenEndOfFrame = true,
            seenMarker = true,
            frameInfo = structure.templateInfo[templateId],
            // Will be less than 0xffff
            frameNumber = frameCount,
            index = frameCount.toLong(),
            templateId = templateId,
            structure = structure,
            activeDecodeTargets = null,
            isKeyframe = isKeyframe(keyCycle),
            rawDependencyDescriptor = null
        )
        av1FrameMaps.getOrPut(f.ssrc) { Av1DDFrameMap(Av1DDQualityFilterTest.logger) }.insertFrame(f)
        frameCount++
        return f
    }

    companion object {
        private const val TOTAL_FRAMES = 10000
    }
}

/** Generate a non-scalable AV1 stream, with a single keyframe at the start. */
private class SingleLayerFrameGenerator(av1FrameMaps: HashMap<Long, Av1DDFrameMap>) : DDBasedGenerator(
    av1FrameMaps,
    10000,
    arrayOf(0),
    arrayOf(1),
    "80000180003a410180ef808680"
)

/** Generate a temporally-scaled series of AV1 frames, with a single keyframe at the start. */
private class TemporallyScaledFrameGenerator(av1FrameMaps: HashMap<Long, Av1DDFrameMap>) : DDBasedGenerator(
    av1FrameMaps,
    10000,
    arrayOf(0),
    arrayOf(1, 3, 2, 4),
    "800001800214eaa860414d141020842701df010d"
)

/** Generate a spatially-scaled series of AV1 frames (L3T3), with full spatial dependencies and periodic keyframes. */
private class SVCFrameGenerator(av1FrameMaps: HashMap<Long, Av1DDFrameMap>) : DDBasedGenerator(
    av1FrameMaps,
    144,
    arrayOf(1, 6, 11),
    arrayOf(0, 5, 10, 3, 8, 13, 2, 7, 12, 4, 9, 14),
    "d0013481e81485214eafffaaaa863cf0430c10c302afc0aaa0063c00430010c002a000a800060000" +
        "40001d954926e082b04a0941b820ac1282503157f974000ca864330e222222eca8655304224230ec" +
        "a87753013f00b3027f016704ff02cf"
)

/** Generate a spatially-scaled series of AV1 frames (L3T3), with keyframe spatial dependencies and periodic
 *  keyframes. */
private class KSVCFrameGenerator(av1FrameMaps: HashMap<Long, Av1DDFrameMap>) : DDBasedGenerator(
    av1FrameMaps,
    144,
    arrayOf(0, 5, 10),
    arrayOf(1, 6, 11, 3, 8, 13, 2, 7, 12, 4, 9, 14),
    "8f008581e81485214eaaaaa8000600004000100002aa80a8000600004000100002a000a80006000040" +
        "0016d549241b5524906d54923157e001974ca864330e222396eca8655304224390eca87753013f00b3027f016704ff02cf"
)

/** Generate a spatially-scaled series of AV1 frames (L2T2), with keyframe spatial dependencies and periodic
 *  keyframes, with temporal structures shifted. */
/* Note that as of Chrome 111, L3T3_KEY_SHIFT is not supported yet, so we're testing L2T2_KEY_SHIFT instead. */
private class KSVCShiftFrameGenerator(av1FrameMaps: HashMap<Long, Av1DDFrameMap>) : DDBasedGenerator(
    av1FrameMaps,
    144,
    arrayOf(0, 4, 1),
    arrayOf(2, 6, 3, 5),
    "8700ed80e3061eaa82804028280514d14134518010a091889a09409fc059c13fc0b3c0"
)

/** Generate a single-stream temporally-scaled simulcast (S3T3) series of AV1 frames, with periodic keyframes. */
private class SingleEncodingSimulcastGenerator(av1FrameMaps: HashMap<Long, Av1DDFrameMap>) : DDBasedGenerator(
    av1FrameMaps,
    144,
    arrayOf(1, 6, 11),
    arrayOf(0, 5, 10, 3, 8, 13, 2, 7, 12, 4, 9, 14),
    "c1000180081485214ea000a8000600004000100002a000a8000600004000100002a000a8000600004" +
        "0001d954926caa493655248c55fe5d00032a190cc38e58803b2a1954c10e10843b2a1dd4c01dc010803bc0218077c0434"
) {
    // All frames of the initial picture get the DD structure attached
    override fun isKeyframe(keyCycle: Int) = keyCycle < keyframeTemplates.size
}

private class MultiEncodingSimulcastGenerator(val av1FrameMaps: HashMap<Long, Av1DDFrameMap>) : FrameGenerator() {
    private var frameCount = 0

    override fun hasNext(): Boolean = frameCount < TOTAL_FRAMES

    override fun next(): Av1DDFrame {
        val pictureCount = frameCount / NUM_ENCODINGS
        val encoding = frameCount % NUM_ENCODINGS
        val tCycle = pictureCount % normalTemplates.size
        val keyCycle = pictureCount % KEYFRAME_INTERVAL

        val templateId = if (keyCycle < keyframeTemplates.size) {
            keyframeTemplates[tCycle]
        } else {
            normalTemplates[tCycle]
        }

        val keyframePicture = keyCycle == 0

        val f = Av1DDFrame(
            ssrc = encoding.toLong(),
            timestamp = pictureCount * 3000L,
            earliestKnownSequenceNumber = pictureCount,
            latestKnownSequenceNumber = pictureCount,
            seenStartOfFrame = true,
            seenEndOfFrame = true,
            seenMarker = true,
            frameInfo = structure.templateInfo[templateId],
            // Will be less than 0xffff
            frameNumber = pictureCount,
            index = pictureCount.toLong(),
            templateId = templateId,
            structure = structure,
            activeDecodeTargets = null,
            isKeyframe = keyframePicture,
            rawDependencyDescriptor = null
        )
        av1FrameMaps.getOrPut(f.ssrc) { Av1DDFrameMap(Av1DDQualityFilterTest.logger) }.insertFrame(f)
        frameCount++
        return f
    }

    companion object {
        private const val TOTAL_FRAMES = 10000
        private const val KEYFRAME_INTERVAL = 144
        private const val NUM_ENCODINGS = 3
        private val keyframeTemplates = arrayOf(0)
        private val normalTemplates = arrayOf(1, 3, 2, 4)

        private val structure: Av1TemplateDependencyStructure

        init {
            val dd = DatatypeConverter.parseHexBinary("800001800214eaa860414d141020842701df010d")
            structure = Av1DependencyDescriptorReader(dd, 0, dd.size).parse(null).structure
        }
    }
}
