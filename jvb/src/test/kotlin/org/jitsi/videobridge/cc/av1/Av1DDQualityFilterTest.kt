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
import org.jitsi.rtp.rtp.header_extensions.DTI
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

                testGenerator(generator, filter, targetIndex) {
                        _, result ->
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

                testGenerator(generator, filter, targetIndex) {
                        _, result ->
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

                testGenerator(generator, filter, targetIndex) {
                        f, result ->
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

                testGenerator(generator, filter, targetIndex) {
                        f, result ->
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
            should("be entirely projected when SL2/TL2 is requested") {
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
            should("be able to be shaped to SL0/TL2") {
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
            should("be able to be shaped to SL1/TL2") {
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
            should("be able to be shaped to SL2/TL0") {
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
            should("be able to switch spatial layers") {
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
                        result.mark shouldBe if (!sawKeyframe)
                            (f.frameInfo!!.spatialId == 0)
                        else
                            (f.frameInfo!!.spatialId == 2)
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

            ms = if (f.timestamp != lastTs) { f.timestamp / 90 } else { ms + 1 }
            lastTs = f.timestamp

            val packetIndices = f.frameInfo!!.dti.withIndex()
                .filter { (_, dti) -> dti != DTI.NOT_PRESENT }
                .map { (i, _) -> Av1DDRtpLayerDesc.getIndex(f.ssrc.toInt(), i) }

            val result = filter.acceptFrame(
                frame = f,
                externalTargetIndex = targetIndex,
                incomingEncoding = f.ssrc.toInt(),
                incomingIndices = packetIndices,
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

/** Generate a spatially-scaled series of AV1 frames, with full spatial dependencies and periodic keyframes. */
private class SingleLayerFrameGenerator(val av1FrameMaps: HashMap<Long, Av1DDFrameMap>) : FrameGenerator() {
    private val totalFrames = 10000
    private var frameCount = 0

    private val structure = Av1DependencyDescriptorReader(dd, 0, dd.size).parse(null).structure

    override fun hasNext(): Boolean = frameCount < totalFrames

    override fun next(): Av1DDFrame {
        val f = Av1DDFrame(
            ssrc = 0,
            timestamp = frameCount * 3000L,
            earliestKnownSequenceNumber = frameCount,
            latestKnownSequenceNumber = frameCount,
            seenStartOfFrame = true,
            seenEndOfFrame = true,
            seenMarker = true,
            frameInfo = structure.templateInfo[0],
            frameNumber = frameCount, /* Will be less than 0xffff */
            index = frameCount,
            structure = structure,
            activeDecodeTargets = null,
            isKeyframe = (frameCount == 0)
        )
        av1FrameMaps.getOrPut(f.ssrc) { Av1DDFrameMap(Av1DDQualityFilterTest.logger) }.insertFrame(f)
        frameCount++
        return f
    }

    companion object {
        /* DD with structure for single temporal and single spatial layer. */
        private val dd = DatatypeConverter.parseHexBinary("80000180003a410180ef808680")
    }
}

/** Generate a temporally-scaled series of AV1 frames, with a single keyframe at the start. */
private class TemporallyScaledFrameGenerator(val av1FrameMaps: HashMap<Long, Av1DDFrameMap>) : FrameGenerator() {
    private val totalFrames = 10000
    private var frameCount = 0

    private val structure = Av1DependencyDescriptorReader(dd, 0, dd.size).parse(null).structure

    override fun hasNext(): Boolean = frameCount < totalFrames

    override fun next(): Av1DDFrame {
        val tCycle = frameCount % normalTemplates.size
        val templateId = if (frameCount < keyframeTemplates.size)
            keyframeTemplates[tCycle]
        else
            normalTemplates[tCycle]

        val f = Av1DDFrame(
            ssrc = 0,
            timestamp = frameCount * 3000L,
            earliestKnownSequenceNumber = frameCount,
            latestKnownSequenceNumber = frameCount,
            seenStartOfFrame = true,
            seenEndOfFrame = true,
            seenMarker = true,
            frameInfo = structure.templateInfo[templateId],
            frameNumber = frameCount, /* Will be less than 0xffff */
            index = frameCount,
            structure = structure,
            activeDecodeTargets = null,
            isKeyframe = (frameCount == 0)
        )
        av1FrameMaps.getOrPut(f.ssrc) { Av1DDFrameMap(Av1DDQualityFilterTest.logger) }.insertFrame(f)
        frameCount++
        return f
    }

    companion object {
        val keyframeTemplates = arrayOf(0)
        val normalTemplates = arrayOf(1, 3, 2, 4)

        /* DD with structure for three temporal layers */
        private val dd = DatatypeConverter.parseHexBinary("800001800214eaa860414d141020842701df010d")
    }
}

/** Generate a spatially-scaled series of AV1 frames, with full spatial dependencies and periodic keyframes. */
private class SVCFrameGenerator(val av1FrameMaps: HashMap<Long, Av1DDFrameMap>) : FrameGenerator() {
    private val totalFrames = 10000
    private var frameCount = 0

    private val structure = Av1DependencyDescriptorReader(dd, 0, dd.size).parse(null).structure

    override fun hasNext(): Boolean = frameCount < totalFrames

    override fun next(): Av1DDFrame {
        val tCycle = frameCount % normalTemplates.size
        val keyCycle = frameCount % keyframeInterval

        val templateId = if (keyCycle < keyframeTemplates.size)
            keyframeTemplates[tCycle]
        else
            normalTemplates[tCycle]

        val keyframePicture = keyCycle == 0

        val f = Av1DDFrame(
            ssrc = 0,
            timestamp = frameCount * 3000L,
            earliestKnownSequenceNumber = frameCount,
            latestKnownSequenceNumber = frameCount,
            seenStartOfFrame = true,
            seenEndOfFrame = true,
            seenMarker = true,
            frameInfo = structure.templateInfo[templateId],
            frameNumber = frameCount, /* Will be less than 0xffff */
            index = frameCount,
            structure = structure,
            activeDecodeTargets = null,
            isKeyframe = keyframePicture
        )
        av1FrameMaps.getOrPut(f.ssrc) { Av1DDFrameMap(Av1DDQualityFilterTest.logger) }.insertFrame(f)
        frameCount++
        return f
    }

    companion object {
        /* Periodic keyframes.  Period needs to be a multiple of the tCycle size. */
        const val keyframeInterval = 144
        val keyframeTemplates = arrayOf(1, 6, 11)
        val normalTemplates = arrayOf(0, 5, 10, 3, 8, 13, 2, 7, 12, 4, 9, 14)

        /* DD with structure for L3T3 */
        private val dd = DatatypeConverter.parseHexBinary(
            "d0013481e81485214eafffaaaa863cf0430c10c302afc0aaa0063c00430010c002a000a800060000" +
                "40001d954926e082b04a0941b820ac1282503157f974000ca864330e222222eca8655304224230ec" +
                "a87753013f00b3027f016704ff02cf"
        )
    }
}
