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
            evaluator(f, result)
            frames++
        }
    }

    companion object {
        private val logger = LoggerImpl(getClassForLogging(this::class.java).name)
    }
}

private abstract class FrameGenerator : Iterator<Av1DDFrame>

/** Generate a non-scalable series of VP9 frames, with a single keyframe at the start. */
private class SingleLayerFrameGenerator(val av1FrameMaps: Map<Long, Av1DDFrameMap>) : FrameGenerator() {
    private val totalFrames = 1000
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
        av1FrameMaps[f.ssrc]?.insertFrame(f)
        frameCount++
        return f
    }

    companion object {
        /* DD with structure for single temporal and single spatial layer. */
        private val dd = DatatypeConverter.parseHexBinary("80000180003a410180ef808680")
    }
}
