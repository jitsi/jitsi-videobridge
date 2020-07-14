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
                val generator = SingleLayerPacketGenerator()
                val targetIndex = RtpLayerDesc.getIndex(0, 0, 0)

                testGenerator(generator, filter, targetIndex) {
                    f, result ->
                    result.accept shouldBe true
                    result.mark shouldBe true
                    filter.needsKeyframe() shouldBe false
                }
            }
        }
    }

    private fun testGenerator(
        g: PacketGenerator,
        filter: Vp9QualityFilter,
        targetIndex: Int,
        numFrames: Int = Int.MAX_VALUE,
        evaluator: (Vp9Frame, Vp9QualityFilter.AcceptResult) -> Unit
    ) {
        var lastTs = -1L
        var ms = -1L
        while (g.hasNext() && numFrames > 0) {
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
        }
    }

    companion object {
        private val logger = LoggerImpl(getClassForLogging(this::class.java).name)
    }
}

private abstract class PacketGenerator : Iterator<Vp9Frame>

/** Generate a non-scalable series of VP9 frames, with a single keyframe at the start. */
private class SingleLayerPacketGenerator : PacketGenerator() {
    private val totalPictures = 1000
    private var frameCount = 0

    override fun hasNext(): Boolean = frameCount < totalPictures

    override fun next(): Vp9Frame {
        val f = Vp9Frame(
            ssrc = 0,
            timestamp = frameCount * 3000L,
            earliestKnownSequenceNumber = frameCount,
            latestKnownSequenceNumber = frameCount,
            seenStartOfFrame = true,
            seenEndOfFrame = true,
            seenMarker = true,
            temporalLayer = 0,
            spatialLayer = 0,
            isUpperLevelReference = false,
            isSwitchingUpPoint = false,
            usesInterLayerDependency = false,
            isInterPicturePredicted = (frameCount > 0),
            pictureId = frameCount,
            tl0PICIDX = frameCount,
            isKeyframe = (frameCount == 0)
        )
        frameCount++
        return f
    }
}
