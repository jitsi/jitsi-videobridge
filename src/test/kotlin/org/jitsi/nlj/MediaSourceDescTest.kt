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

package org.jitsi.nlj

import io.kotest.matchers.shouldBe
import io.kotest.core.spec.style.ShouldSpec
import org.jitsi.nlj.util.Bandwidth
import org.jitsi.nlj.util.BitrateTracker
import org.jitsi.nlj.util.bps
import org.jitsi.utils.secs

class MediaSourceDescTest : ShouldSpec() {
    init {
        val ssrcs = arrayOf(0xdeadbeef, 0xcafebabe, 0x01234567)
        val source = createSource(
            ssrcs,
            1, 3, "Fake owner"
        )

        context("Source properties should be correct") {
            source.owner shouldBe "Fake owner"
            source.rtpEncodings.size shouldBe 3

            source.rtpLayers.size shouldBe 9
            source.hasRtpLayers() shouldBe true
            source.numRtpLayers() shouldBe 9

            source.matches(0xdeadbeef) shouldBe true
        }

        context("Encoding and layer properties should be correct") {
            for (i in source.rtpEncodings.indices) {
                val e = source.rtpEncodings[i]
                e.primarySSRC shouldBe ssrcs[i]
                e.layers.size shouldBe 3
                for (j in e.layers.indices) {
                    val l = e.layers[j]
                    l.eid shouldBe i
                    l.tid shouldBe j
                    l.sid shouldBe -1

                    /* Set up rate statistics testing */
                    /* Set non-zero rates for (0,0), (0,1), (1,0), (1,1), and (2,2) */
                    if ((i < source.rtpEncodings.size - 1 && j < e.layers.size - 1) ||
                        (i == source.rtpEncodings.size - 1 && j == e.layers.size - 1)
                    ) {
                        /* Encode the layer ID into the rate, so it's unambiguous which layers are getting summed. */
                        l.inheritStatistics(FakeBitrateTracker(1L shl (i * source.rtpEncodings.size + j)))
                    }
                }
            }
        }

        context("Layer bitrates should be correct") {
            val t = 0L // Doesn't actually matter for fake rate statistics

            /* Rate from layer 0 */
            source.getBitrate(t, RtpLayerDesc.getIndex(0, 0, 0)) shouldBe 1.bps
            /* Rates from layer 1 and its dependency, 0 */
            source.getBitrate(t, RtpLayerDesc.getIndex(0, 0, 1)) shouldBe (2 + 1).bps
            /* Layer 2's own rate is 0, so rates from its dependencies, 1 and 0 */
            source.getBitrate(t, RtpLayerDesc.getIndex(0, 0, 2)) shouldBe (2 + 1).bps

            /* Rate from layer 3 */
            source.getBitrate(t, RtpLayerDesc.getIndex(1, 0, 0)) shouldBe 8.bps
            /* Rates from layer 4 and its dependency, 3 */
            source.getBitrate(t, RtpLayerDesc.getIndex(1, 0, 1)) shouldBe (16 + 8).bps
            /* Layer 5's own rate is 0, so rates from its dependencies, 4 and 3 */
            source.getBitrate(t, RtpLayerDesc.getIndex(1, 0, 2)) shouldBe (16 + 8).bps

            /* If a layer returns a 0 rate, the function gets the next lower non-zero rate */
            /* Layer 6's own rate is 0, and it has no dependencies, so get the rate from the next-lower
               layer, 5 */
            source.getBitrate(t, RtpLayerDesc.getIndex(2, 0, 0)) shouldBe (16 + 8).bps
            /* Layer 7's own rate is also 0, so similarly get the rate from layer 5 */
            source.getBitrate(t, RtpLayerDesc.getIndex(2, 0, 1)) shouldBe (16 + 8).bps
            /* Layer 8's rate.  Its dependencies (7 and 6) are 0. */
            source.getBitrate(t, RtpLayerDesc.getIndex(2, 0, 2)) shouldBe 256.bps
        }
    }
}

/* The following creation functions are adapted from MediaSourceFactory in jitsi-videobridge. */

/**
 * Calculates the array position of an RTP layer description specified by its
 * spatial index (SVC) and temporal index (SVC).
 *
 * @param spatialIdx the spatial layer index.
 * @param temporalIdx the temporal layer index.
 *
 * @return the subjective quality index of the flow specified in the
 * arguments.
 */
private fun idx(spatialIdx: Int, temporalIdx: Int, temporalLen: Int) =
    spatialIdx * temporalLen + temporalIdx

/*
 * Creates layers for an encoding.
 *
 * @param spatialLen the number of spatial encodings per simulcast stream.
 * @param temporalLen the number of temporal encodings per simulcast stream.
 * @param height the maximum height of the top spatial layer
 * @return an array that holds the layer descriptions.
 */
private fun createRTPLayerDescs(
    spatialLen: Int,
    temporalLen: Int,
    encodingIdx: Int,
    height: Int
): Array<RtpLayerDesc> {
    val rtpLayers = arrayOfNulls<RtpLayerDesc>(spatialLen * temporalLen)
    for (spatialIdx in 0 until spatialLen) {
        var frameRate = 30.toDouble() / (1 shl temporalLen - 1)
        for (temporalIdx in 0 until temporalLen) {
            val idx: Int = idx(spatialIdx, temporalIdx, temporalLen)
            var dependencies: Array<RtpLayerDesc>
            dependencies = if (spatialIdx > 0 && temporalIdx > 0) {
                // this layer depends on spatialIdx-1 and temporalIdx-1.
                arrayOf(
                    rtpLayers[
                        idx(
                            spatialIdx, temporalIdx - 1,
                            temporalLen
                        )
                    ]!!,
                    rtpLayers[
                        idx(
                            spatialIdx - 1, temporalIdx,
                            temporalLen
                        )
                    ]!!
                )
            } else if (spatialIdx > 0) {
                // this layer depends on spatialIdx-1.
                arrayOf(
                    rtpLayers[
                        idx(
                            spatialIdx - 1, temporalIdx,
                            temporalLen
                        )
                    ]!!
                )
            } else if (temporalIdx > 0) {
                // this layer depends on temporalIdx-1.
                arrayOf(
                    rtpLayers[
                        idx(
                            spatialIdx, temporalIdx - 1,
                            temporalLen
                        )
                    ]!!
                )
            } else {
                // this is a base layer without any dependencies.
                emptyArray()
            }
            val temporalId = if (temporalLen > 1) temporalIdx else -1
            val spatialId = if (spatialLen > 1) spatialIdx else -1
            rtpLayers[idx] = RtpLayerDesc(
                encodingIdx,
                temporalId, spatialId, height, frameRate, dependencies
            )
            frameRate *= 2.0
        }
    }
    return rtpLayers as Array<RtpLayerDesc>
}

/**
 * Creates an RTP encoding.
 * @param primarySsrc the primary SSRC for the encoding.
 * @param spatialLen the number of spatial layers of the encoding.
 * @param temporalLen the number of temporal layers of the encodings.
 * @param secondarySsrcs a list of pairs, where each
 * pair has the secondary ssrc as its key, and the type (rtx, etc.) as its
 * value
 * @param encodingIdx the index of the encoding
 * @return a description of the encoding.
 */
private fun createRtpEncodingDesc(
    primarySsrc: Long,
    spatialLen: Int,
    temporalLen: Int,
    encodingIdx: Int,
    height: Int
): RtpEncodingDesc {
    val layers: Array<RtpLayerDesc> = createRTPLayerDescs(
        spatialLen, temporalLen,
        encodingIdx, height
    )
    val enc = RtpEncodingDesc(primarySsrc, layers)
    return enc
}

private fun createSource(
    primarySsrcs: Array<Long>,
    numSpatialLayersPerStream: Int,
    numTemporalLayersPerStream: Int,
    owner: String
): MediaSourceDesc {
    var height = 720

    val encodings = Array(primarySsrcs.size) {
        encodingIdx ->
        val primarySsrc: Long = primarySsrcs.get(encodingIdx)
        val ret = createRtpEncodingDesc(
            primarySsrc,
            numSpatialLayersPerStream, numTemporalLayersPerStream, encodingIdx, height
        )
        height *= 2
        ret
    }

    return MediaSourceDesc(encodings, owner)
}

/** A fake rate statistics object, for testing */
private class FakeBitrateTracker(
    private val fakeRateBps: Long
) : BitrateTracker(1.secs) {
    override fun getRate(nowMs: Long): Bandwidth {
        return fakeRateBps.bps
    }

    override fun getRateBps(nowMs: Long): Long {
        return fakeRateBps
    }
}
