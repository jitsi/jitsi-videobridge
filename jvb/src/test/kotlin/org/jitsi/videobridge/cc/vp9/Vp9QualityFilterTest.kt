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

package org.jitsi.videobridge.cc.vp9

import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec
import org.jitsi.nlj.RtpLayerDesc
import org.jitsi.utils.logging2.LoggerImpl
import org.jitsi.utils.logging2.getClassForLogging

internal class Vp9QualityFilterTest : ShouldSpec() {
    init {
        "A non-scalable stream" {
            should("be entirely projected") {
                val filter = Vp9QualityFilter(logger)
                val generator = SingleLayerFrameGenerator()
                val targetIndex = RtpLayerDesc.getIndex(0, 0, 0)

                testGenerator(generator, filter, targetIndex) {
                    f, result ->
                    result.accept shouldBe true
                    result.mark shouldBe true
                    filter.needsKeyframe() shouldBe false
                }
            }
        }

        "A temporally scalable stream" {
            should("be entirely projected when TL2 is requested") {
                val filter = Vp9QualityFilter(logger)
                val generator = TemporallyScaledFrameGenerator()
                val targetIndex = RtpLayerDesc.getIndex(0, 0, 2)

                testGenerator(generator, filter, targetIndex) {
                    f, result ->
                    result.accept shouldBe true
                    result.mark shouldBe true
                    filter.needsKeyframe() shouldBe false
                }
            }
            should("project only the base temporal layer when targeted") {
                val filter = Vp9QualityFilter(logger)
                val generator = TemporallyScaledFrameGenerator()
                val targetIndex = RtpLayerDesc.getIndex(0, 0, 0)

                testGenerator(generator, filter, targetIndex) {
                    f, result ->
                    result.accept shouldBe (f.temporalLayer == 0)
                    if (result.accept) {
                        result.mark shouldBe true
                        filter.needsKeyframe() shouldBe false
                    }
                }
            }
            should("project only the intermediate temporal layer when targeted") {
                val filter = Vp9QualityFilter(logger)
                val generator = TemporallyScaledFrameGenerator()
                val targetIndex = RtpLayerDesc.getIndex(0, 0, 1)

                testGenerator(generator, filter, targetIndex) {
                    f, result ->
                    result.accept shouldBe (f.temporalLayer <= 1)
                    if (result.accept) {
                        result.mark shouldBe true
                        filter.needsKeyframe() shouldBe false
                    }
                }
            }
            should("be able to switch the targeted layers, without a keyframe") {
                val filter = Vp9QualityFilter(logger)
                val generator = TemporallyScaledFrameGenerator()
                val targetIndex1 = RtpLayerDesc.getIndex(0, 0, 0)

                testGenerator(generator, filter, targetIndex1, numFrames = 500) { f, result ->
                    result.accept shouldBe (f.temporalLayer == 0)
                    if (result.accept) {
                        result.mark shouldBe true
                        filter.needsKeyframe() shouldBe false
                    }
                }
                val targetIndex2 = RtpLayerDesc.getIndex(0, 0, 2)

                testGenerator(generator, filter, targetIndex2) { f, result ->
                    result.accept shouldBe true
                    result.mark shouldBe true
                    filter.needsKeyframe() shouldBe false
                }
            }
        }

        "A spatially scalable stream" {
            should("be entirely projected when SL2/TL2 is requested") {
                val filter = Vp9QualityFilter(logger)
                val generator = SVCFrameGenerator()
                val targetIndex = RtpLayerDesc.getIndex(0, 2, 2)

                testGenerator(generator, filter, targetIndex) { f, result ->
                    result.accept shouldBe true
                    result.mark shouldBe (f.spatialLayer == 2)
                    filter.needsKeyframe() shouldBe false
                }
            }
            should("be able to be shaped to SL0/TL2") {
                val filter = Vp9QualityFilter(logger)
                val generator = SVCFrameGenerator()
                val targetIndex = RtpLayerDesc.getIndex(0, 0, 2)

                testGenerator(generator, filter, targetIndex) { f, result ->
                    result.accept shouldBe (f.spatialLayer == 0)
                    if (result.accept) {
                        result.mark shouldBe (f.spatialLayer == 0)
                        filter.needsKeyframe() shouldBe false
                    }
                }
            }
            should("be able to be shaped to SL1/TL2") {
                val filter = Vp9QualityFilter(logger)
                val generator = SVCFrameGenerator()
                val targetIndex = RtpLayerDesc.getIndex(0, 1, 2)

                testGenerator(generator, filter, targetIndex) { f, result ->
                    result.accept shouldBe (f.spatialLayer <= 1)
                    if (result.accept) {
                        result.mark shouldBe (f.spatialLayer == 1)
                        filter.needsKeyframe() shouldBe false
                    }
                }
            }
            should("be able to be shaped to SL2/TL0") {
                val filter = Vp9QualityFilter(logger)
                val generator = SVCFrameGenerator()
                val targetIndex = RtpLayerDesc.getIndex(0, 2, 0)

                testGenerator(generator, filter, targetIndex) { f, result ->
                    result.accept shouldBe (f.temporalLayer == 0)
                    if (result.accept) {
                        result.mark shouldBe (f.spatialLayer == 2)
                        filter.needsKeyframe() shouldBe false
                    }
                }
            }
            should("be able to switch spatial layers") {
                val filter = Vp9QualityFilter(logger)
                val generator = SVCFrameGenerator()

                /* Start by sending spatial layer 0. */
                val targetIndex1 = RtpLayerDesc.getIndex(0, 0, 2)

                testGenerator(generator, filter, targetIndex1, numFrames = 1200) { f, result ->
                    result.accept shouldBe (f.spatialLayer == 0)
                    if (result.accept) {
                        result.mark shouldBe (f.spatialLayer == 0)
                        filter.needsKeyframe() shouldBe false
                    }
                }

                /* Switch to spatial layer 2.  Need a keyframe. */
                val targetIndex2 = RtpLayerDesc.getIndex(0, 2, 2)
                var sawTargetLayer = false
                var sawKeyframe = false
                testGenerator(generator, filter, targetIndex2, numFrames = 1200) { f, result ->
                    if (f.spatialLayer == 2) sawTargetLayer = true
                    if (f.isKeyframe) sawKeyframe = true
                    result.accept shouldBe if (!sawKeyframe) (f.spatialLayer == 0) else true
                    if (result.accept) {
                        result.mark shouldBe if (!sawKeyframe) (f.spatialLayer == 0) else (f.spatialLayer == 2)
                        filter.needsKeyframe() shouldBe (sawTargetLayer && !sawKeyframe)
                    }
                }

                /* Switch to spatial layer 1.  For SVC, dropping down in spatial layers can happen immediately. */
                val targetIndex3 = RtpLayerDesc.getIndex(0, 1, 2)
                testGenerator(generator, filter, targetIndex3) { f, result ->
                    result.accept shouldBe (f.spatialLayer <= 1)
                    if (result.accept) {
                        result.mark shouldBe (f.spatialLayer == 1)
                        filter.needsKeyframe() shouldBe false
                    }
                }
            }
        }

        "A K-SVC spatially scalable stream" {
            should("be able to be shaped to SL2/TL2") {
                val filter = Vp9QualityFilter(logger)
                val generator = KSVCFrameGenerator()
                val targetIndex = RtpLayerDesc.getIndex(0, 2, 2)

                testGenerator(generator, filter, targetIndex) { f, result ->
                    result.accept shouldBe (f.spatialLayer == 2 || !f.isInterPicturePredicted)
                    result.mark shouldBe (f.spatialLayer == 2)
                    filter.needsKeyframe() shouldBe false
                }
            }
            should("be able to be shaped to SL0/TL2") {
                val filter = Vp9QualityFilter(logger)
                val generator = KSVCFrameGenerator()
                val targetIndex = RtpLayerDesc.getIndex(0, 0, 2)

                testGenerator(generator, filter, targetIndex) { f, result ->
                    result.accept shouldBe (f.spatialLayer == 0)
                    if (result.accept) {
                        result.mark shouldBe (f.spatialLayer == 0)
                        filter.needsKeyframe() shouldBe false
                    }
                }
            }
            should("be able to be shaped to SL1/TL2") {
                val filter = Vp9QualityFilter(logger)
                val generator = KSVCFrameGenerator()
                val targetIndex = RtpLayerDesc.getIndex(0, 1, 2)

                testGenerator(generator, filter, targetIndex) { f, result ->
                    result.accept shouldBe (f.spatialLayer == 1 || f.spatialLayer < 1 && !f.isInterPicturePredicted)
                    if (result.accept) {
                        result.mark shouldBe (f.spatialLayer == 1)
                        filter.needsKeyframe() shouldBe false
                    }
                }
            }
            should("be able to be shaped to SL2/TL0") {
                val filter = Vp9QualityFilter(logger)
                val generator = KSVCFrameGenerator()
                val targetIndex = RtpLayerDesc.getIndex(0, 2, 0)

                testGenerator(generator, filter, targetIndex) { f, result ->
                    result.accept shouldBe (f.temporalLayer == 0 &&
                        (f.spatialLayer == 2 || !f.isInterPicturePredicted))
                    if (result.accept) {
                        result.mark shouldBe (f.spatialLayer == 2)
                        filter.needsKeyframe() shouldBe false
                    }
                }
            }
            should("be able to switch spatial layers") {
                val filter = Vp9QualityFilter(logger)
                val generator = KSVCFrameGenerator()

                /* Start by sending spatial layer 0. */
                val targetIndex1 = RtpLayerDesc.getIndex(0, 0, 2)

                testGenerator(generator, filter, targetIndex1, numFrames = 1200) { f, result ->
                    result.accept shouldBe (f.spatialLayer == 0)
                    if (result.accept) {
                        result.mark shouldBe (f.spatialLayer == 0)
                        filter.needsKeyframe() shouldBe false
                    }
                }

                /* Switch to spatial layer 2.  Need a keyframe. */
                val targetIndex2 = RtpLayerDesc.getIndex(0, 2, 2)
                var sawTargetLayer = false
                var sawKeyframe = false
                testGenerator(generator, filter, targetIndex2, numFrames = 1200) { f, result ->
                    if (f.spatialLayer == 2) sawTargetLayer = true
                    if (f.isKeyframe) sawKeyframe = true
                    result.accept shouldBe if (!sawKeyframe) (f.spatialLayer == 0) else
                        (f.spatialLayer == 2 || !f.isInterPicturePredicted)
                    if (result.accept) {
                        result.mark shouldBe if (!sawKeyframe) (f.spatialLayer == 0) else (f.spatialLayer == 2)
                        filter.needsKeyframe() shouldBe (sawTargetLayer && !sawKeyframe)
                    }
                }

                /* Switch to spatial layer 1.  For K-SVC this needs a keyframe. */
                /* Until the switch is complete we send only TL0. */
                sawTargetLayer = false
                sawKeyframe = false
                val targetIndex3 = RtpLayerDesc.getIndex(0, 1, 2)
                testGenerator(generator, filter, targetIndex3) { f, result ->
                    if (f.spatialLayer == 1) sawTargetLayer = true
                    if (f.isKeyframe) sawKeyframe = true
                    result.accept shouldBe if (!sawKeyframe)
                        ((f.spatialLayer == 2 || !f.isInterPicturePredicted) && f.temporalLayer == 0) else
                        (f.spatialLayer == 1 || (!f.isInterPicturePredicted && f.spatialLayer < 1))
                    if (result.accept) {
                        result.mark shouldBe if (!sawKeyframe) (f.spatialLayer == 2) else (f.spatialLayer == 1)
                        filter.needsKeyframe() shouldBe !sawKeyframe
                    }
                }
            }
        }

        "A simulcast source with multiple encodings" {
            should("be able to be shaped to Enc 2/TL2") {
                val filter = Vp9QualityFilter(logger)
                val generator = SimulcastFrameGenerator()
                val targetIndex = RtpLayerDesc.getIndex(2, 0, 2)

                testGenerator(generator, filter, targetIndex) { f, result ->
                    result.accept shouldBe (f.ssrc == 2L || f.isKeyframe)
                    if (result.accept) {
                        result.mark shouldBe true
                        filter.needsKeyframe() shouldBe false
                    }
                }
            }
            should("be able to be shaped to Enc 0/TL2") {
                val filter = Vp9QualityFilter(logger)
                val generator = SimulcastFrameGenerator()
                val targetIndex = RtpLayerDesc.getIndex(0, 0, 2)

                testGenerator(generator, filter, targetIndex) { f, result ->
                    result.accept shouldBe (f.ssrc == 0L)
                    if (result.accept) {
                        result.mark shouldBe true
                        filter.needsKeyframe() shouldBe false
                    }
                }
            }
            should("be able to be shaped to Enc 1/TL2") {
                val filter = Vp9QualityFilter(logger)
                val generator = SimulcastFrameGenerator()
                val targetIndex = RtpLayerDesc.getIndex(1, 0, 2)

                testGenerator(generator, filter, targetIndex) { f, result ->
                    result.accept shouldBe (f.ssrc == 1L || f.isKeyframe && f.ssrc < 1L)
                    if (result.accept) {
                        result.mark shouldBe true
                        filter.needsKeyframe() shouldBe false
                    }
                }
            }
            should("be able to be shaped to Enc 1/TL0") {
                val filter = Vp9QualityFilter(logger)
                val generator = SimulcastFrameGenerator()
                val targetIndex = RtpLayerDesc.getIndex(2, 0, 0)

                testGenerator(generator, filter, targetIndex) { f, result ->
                    result.accept shouldBe (f.temporalLayer == 0 && (f.ssrc == 2L || f.isKeyframe))
                    if (result.accept) {
                        result.mark shouldBe true
                        filter.needsKeyframe() shouldBe false
                    }
                }
            }
            should("be able to switch encodings") {
                val filter = Vp9QualityFilter(logger)
                val generator = SimulcastFrameGenerator()

                /* Start by sending spatial layer 0. */
                val targetIndex1 = RtpLayerDesc.getIndex(0, 0, 2)

                testGenerator(generator, filter, targetIndex1, numFrames = 1200) { f, result ->
                    result.accept shouldBe (f.ssrc == 0L)
                    if (result.accept) {
                        result.mark shouldBe true
                        filter.needsKeyframe() shouldBe false
                    }
                }

                /* Switch to encoding 2.  Need a keyframe. */
                val targetIndex2 = RtpLayerDesc.getIndex(2, 0, 2)
                var sawKeyframe = false
                testGenerator(generator, filter, targetIndex2, numFrames = 1200) { f, result ->
                    if (f.isKeyframe) sawKeyframe = true
                    result.accept shouldBe if (!sawKeyframe) (f.ssrc == 0L) else (f.ssrc == 2L || f.isKeyframe)
                    if (result.accept) {
                        result.mark shouldBe true
                        filter.needsKeyframe() shouldBe !sawKeyframe
                    }
                }

                /* Switch to encoding 1.  Need a keyframe. */
                /* Until the switch is complete we send only TL0. */
                sawKeyframe = false
                val targetIndex3 = RtpLayerDesc.getIndex(1, 0, 2)
                testGenerator(generator, filter, targetIndex3) { f, result ->
                    if (f.isKeyframe) sawKeyframe = true
                    result.accept shouldBe if (!sawKeyframe)
                        (f.temporalLayer == 0 && (f.ssrc == 2L || f.isKeyframe)) else
                        (f.ssrc == 1L || (f.isKeyframe && f.ssrc < 1L))
                    if (result.accept) {
                        result.mark shouldBe true
                        filter.needsKeyframe() shouldBe !sawKeyframe
                    }
                }
            }
        }
    }

    private fun testGenerator(
        g: FrameGenerator,
        filter: Vp9QualityFilter,
        targetIndex: Int,
        numFrames: Int = Int.MAX_VALUE,
        evaluator: (Vp9Frame, Vp9QualityFilter.AcceptResult) -> Unit
    ) {
        var lastTs = -1L
        var ms = -1L
        var frames = 0
        while (g.hasNext() && frames < numFrames) {
            val f = g.next()

            ms = if (f.timestamp != lastTs) { f.timestamp / 90 } else { ms + 1 }
            lastTs = f.timestamp

            val packetIndex = RtpLayerDesc.getIndex(f.ssrc.toInt(), f.spatialLayer, f.temporalLayer)

            val result = filter.acceptFrame(
                frame = f,
                externalTargetIndex = targetIndex,
                incomingIndex = packetIndex,
                receivedMs = ms)
            evaluator(f, result)
            frames++
        }
    }

    companion object {
        private val logger = LoggerImpl(getClassForLogging(this::class.java).name)
    }
}

private abstract class FrameGenerator : Iterator<Vp9Frame>

/** Generate a non-scalable series of VP9 frames, with a single keyframe at the start. */
private class SingleLayerFrameGenerator : FrameGenerator() {
    private val totalPictures = 1000
    private var pictureCount = 0

    override fun hasNext(): Boolean = pictureCount < totalPictures

    override fun next(): Vp9Frame {
        val f = Vp9Frame(
            ssrc = 0,
            timestamp = pictureCount * 3000L,
            earliestKnownSequenceNumber = pictureCount,
            latestKnownSequenceNumber = pictureCount,
            seenStartOfFrame = true,
            seenEndOfFrame = true,
            seenMarker = true,
            temporalLayer = 0,
            spatialLayer = 0,
            isUpperLevelReference = false,
            isSwitchingUpPoint = false,
            usesInterLayerDependency = false,
            isInterPicturePredicted = (pictureCount > 0),
            pictureId = pictureCount,
            tl0PICIDX = pictureCount,
            isKeyframe = (pictureCount == 0),
            numSpatialLayers = if (pictureCount == 0) 1 else -1
        )
        pictureCount++
        return f
    }
}

/** Generate a temporally-scaled series of VP9 frames, with a single keyframe at the start. */
private class TemporallyScaledFrameGenerator : FrameGenerator() {
    private val totalPictures = 1000
    private var pictureCount = 0
    private var tl0Count = -1

    override fun hasNext(): Boolean = pictureCount < totalPictures

    override fun next(): Vp9Frame {
        val tCycle = pictureCount % 4
        val tLayer = when {
            tCycle == 0 -> 0
            tCycle == 2 -> 1
            else -> 2
        }

        if (tLayer == 0) tl0Count++

        val f = Vp9Frame(
            ssrc = 0,
            timestamp = pictureCount * 3000L,
            earliestKnownSequenceNumber = pictureCount,
            latestKnownSequenceNumber = pictureCount,
            seenStartOfFrame = true,
            seenEndOfFrame = true,
            seenMarker = true,
            temporalLayer = tLayer,
            spatialLayer = 0,
            isUpperLevelReference = false,
            isSwitchingUpPoint = tLayer > 0,
            usesInterLayerDependency = false,
            isInterPicturePredicted = (pictureCount > 0),
            pictureId = pictureCount,
            tl0PICIDX = tl0Count,
            isKeyframe = (pictureCount == 0),
            numSpatialLayers = if (pictureCount == 0) 1 else -1
        )
        pictureCount++
        return f
    }
}

/** Generate a spatially-scaled series of VP9 frames, with full spatial dependencies and periodic keyframes. */
private class SVCFrameGenerator : FrameGenerator() {
    private val totalPictures = 1000
    private var pictureCount = 0
    private var frameCount = 0
    private var tl0Count = -1
    private var sLayer = 0

    override fun hasNext(): Boolean = pictureCount < totalPictures

    override fun next(): Vp9Frame {
        val tCycle = pictureCount % 4
        val tLayer = when {
            tCycle == 0 -> 0
            tCycle == 2 -> 1
            else -> 2
        }

        if (sLayer == 0 && tLayer == 0) tl0Count++

        /* Periodic keyframes.  Period needs to be a multiple of the tCycle size. */
        val keyframePicture = (pictureCount % 48) == 0

        val f = Vp9Frame(
            ssrc = 0,
            timestamp = pictureCount * 3000L,
            earliestKnownSequenceNumber = frameCount,
            latestKnownSequenceNumber = frameCount,
            seenStartOfFrame = true,
            seenEndOfFrame = true,
            seenMarker = sLayer == 2,
            temporalLayer = tLayer,
            spatialLayer = sLayer,
            isUpperLevelReference = sLayer < 2,
            isSwitchingUpPoint = tLayer > 0,
            usesInterLayerDependency = sLayer > 0,
            isInterPicturePredicted = !keyframePicture,
            pictureId = pictureCount,
            tl0PICIDX = tl0Count,
            isKeyframe = keyframePicture && sLayer == 0,
            numSpatialLayers = if (keyframePicture && sLayer == 0) 3 else -1
        )
        frameCount++
        sLayer++
        if (sLayer == 3) {
            sLayer = 0
            pictureCount++
        }
        return f
    }
}

/** Generate a spatially-scaled series of VP9 frames, with K-SVC spatial dependencies and periodic keyframes. */
private class KSVCFrameGenerator : FrameGenerator() {
    private val totalPictures = 1000
    private var pictureCount = 0
    private var frameCount = 0
    private var tl0Count = -1
    private var sLayer = 0

    override fun hasNext(): Boolean = pictureCount < totalPictures

    override fun next(): Vp9Frame {
        val tCycle = pictureCount % 4
        val tLayer = when {
            tCycle == 0 -> 0
            tCycle == 2 -> 1
            else -> 2
        }

        if (sLayer == 0 && tLayer == 0) tl0Count++

        /* Periodic keyframes.  Period needs to be a multiple of the tCycle size. */
        val keyframePicture = (pictureCount % 48) == 0

        val f = Vp9Frame(
            ssrc = 0,
            timestamp = pictureCount * 3000L,
            earliestKnownSequenceNumber = frameCount,
            latestKnownSequenceNumber = frameCount,
            seenStartOfFrame = true,
            seenEndOfFrame = true,
            seenMarker = sLayer == 2,
            temporalLayer = tLayer,
            spatialLayer = sLayer,
            isUpperLevelReference = keyframePicture && sLayer < 2,
            isSwitchingUpPoint = tLayer > 0,
            usesInterLayerDependency = keyframePicture && sLayer > 0,
            isInterPicturePredicted = !keyframePicture,
            pictureId = pictureCount,
            tl0PICIDX = tl0Count,
            isKeyframe = keyframePicture && sLayer == 0,
            numSpatialLayers = if (keyframePicture && sLayer == 0) 3 else -1
        )
        frameCount++
        sLayer++
        if (sLayer == 3) {
            sLayer = 0
            pictureCount++
        }
        return f
    }
}

/** Generate a simulcast series of VP9 frame, with periodic keyframes. */
private class SimulcastFrameGenerator : FrameGenerator() {
    private val totalPictures = 1000
    private var pictureCount = 0
    private var frameCount = 0
    private var tl0Count = -1
    private var enc = 0

    override fun hasNext(): Boolean = pictureCount < totalPictures

    override fun next(): Vp9Frame {
        val tCycle = pictureCount % 4
        val tLayer = when {
            tCycle == 0 -> 0
            tCycle == 2 -> 1
            else -> 2
        }

        if (enc == 0 && tLayer == 0) tl0Count++

        /* Periodic keyframes.  Period needs to be a multiple of the tCycle size. */
        val keyframePicture = (pictureCount % 48) == 0

        val f = Vp9Frame(
            ssrc = enc.toLong(), /* Use the encoding ID as the SSRC to make testing easier. */
            timestamp = pictureCount * 3000L,
            earliestKnownSequenceNumber = pictureCount + (enc * 10000),
            latestKnownSequenceNumber = pictureCount + (enc * 10000),
            seenStartOfFrame = true,
            seenEndOfFrame = true,
            seenMarker = true,
            temporalLayer = tLayer,
            spatialLayer = 0,
            isUpperLevelReference = false,
            isSwitchingUpPoint = tLayer > 0,
            usesInterLayerDependency = false,
            isInterPicturePredicted = !keyframePicture,
            pictureId = pictureCount,
            tl0PICIDX = tl0Count,
            isKeyframe = keyframePicture,
            numSpatialLayers = if (keyframePicture) 1 else -1
        )
        frameCount++
        enc++
        if (enc == 3) {
            enc = 0
            pictureCount++
        }
        return f
    }
}
