/*
 * Copyright @ 2021 - present 8x8, Inc.
 * Copyright @ 2021 - Vowel, Inc.
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
package org.jitsi.videobridge.cc.allocation

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import org.jitsi.nlj.MediaSourceDesc
import org.jitsi.nlj.VideoType
import org.jitsi.videobridge.cc.config.BitrateControllerConfig

fun testSource(
    endpointId: String,
    sourceName: String,
    videoType: VideoType = VideoType.CAMERA
): MediaSourceDesc {
    return MediaSourceDesc(
        emptyArray(),
        endpointId,
        sourceName,
        videoType
    )
}

@Suppress("NAME_SHADOWING")
class EffectiveConstraintsNewTest : ShouldSpec() {
    override fun isolationMode() = IsolationMode.InstancePerLeaf

    init {
        val s1 = testSource("e1", "s1")
        val s2 = testSource("e1", "s2")
        val s3 = testSource("e1", "s3")
        val s4 = testSource("e1", "s4", videoType = VideoType.DISABLED)
        val s5 = testSource("e1", "s5", videoType = VideoType.DISABLED)
        val s6 = testSource("e1", "s6", videoType = VideoType.DISABLED)

        val defaultConstraints = VideoConstraints(BitrateControllerConfig().thumbnailMaxHeightPx())

        val sources = listOf(s1, s2, s3, s4, s5, s6)
        val zeroEffectiveConstraints = mutableMapOf(
            "s1" to VideoConstraints.NOTHING,
            "s2" to VideoConstraints.NOTHING,
            "s3" to VideoConstraints.NOTHING,
            "s4" to VideoConstraints.NOTHING,
            "s5" to VideoConstraints.NOTHING,
            "s6" to VideoConstraints.NOTHING
        )

        context("With lastN=0") {
            val allocationSettings = AllocationSettings(lastN = 0, defaultConstraints = defaultConstraints)
            getEffectiveConstraints2(sources, allocationSettings) shouldBe zeroEffectiveConstraints
        }
        context("With lastN=1") {
            context("And no other constraints") {
                val allocationSettings = AllocationSettings(lastN = 1, defaultConstraints = defaultConstraints)
                getEffectiveConstraints2(sources, allocationSettings) shouldBe zeroEffectiveConstraints.apply {
                    // The default defaultConstraints are 180
                    put("s1", VideoConstraints(180))
                }
            }
            context("And different defaultConstraints") {
                val allocationSettings = AllocationSettings(lastN = 1, defaultConstraints = VideoConstraints(360))
                getEffectiveConstraints2(sources, allocationSettings) shouldBe zeroEffectiveConstraints.apply {
                    put("s1", VideoConstraints(360))
                }
            }
            context("And all constraints 0") {
                val allocationSettings = AllocationSettings(
                    lastN = 1,
                    defaultConstraints = VideoConstraints.NOTHING,
                )
                getEffectiveConstraints2(sources, allocationSettings) shouldBe zeroEffectiveConstraints
            }
            context("And non-zero constraints for a source with video enabled") {
                val allocationSettings = AllocationSettings(
                    lastN = 1,
                    defaultConstraints = VideoConstraints.NOTHING,
                    videoConstraints = mapOf("s1" to VideoConstraints(720))

                )
                getEffectiveConstraints2(sources, allocationSettings) shouldBe zeroEffectiveConstraints.apply {
                    put("s1", VideoConstraints(720))
                }
            }
            context("And non-zero constraints for a source with video DISABLED") {
                val allocationSettings = AllocationSettings(
                    lastN = 1,
                    defaultConstraints = VideoConstraints.NOTHING,
                    videoConstraints = mapOf("s4" to VideoConstraints(720))
                )
                getEffectiveConstraints2(sources, allocationSettings) shouldBe zeroEffectiveConstraints.apply {
                    put("s4", VideoConstraints(720))
                }
            }
            context("When the top sources have the video DISABLED") {
                // The top sources in speaker order have videoType = DISABLED
                val sources = listOf(s4, s5, s6, s1, s2, s3)

                context("With default settings") {
                    val allocationSettings = AllocationSettings(lastN = 1, defaultConstraints = defaultConstraints)
                    getEffectiveConstraints2(sources, allocationSettings) shouldBe zeroEffectiveConstraints.apply {
                        put("s4", VideoConstraints(180))
                    }
                }
                context("With default constraints 0 and non-zero constraints for a source with video DISABLED") {
                    val allocationSettings = AllocationSettings(
                        lastN = 1,
                        defaultConstraints = VideoConstraints.NOTHING,
                        videoConstraints = mapOf("s5" to VideoConstraints(180))
                    )
                    getEffectiveConstraints2(sources, allocationSettings) shouldBe zeroEffectiveConstraints.apply {
                        put("s5", VideoConstraints(180))
                    }
                }
                context("With default constraints 0 and non-zero constraints for a source with video enabled") {
                    val allocationSettings = AllocationSettings(
                        lastN = 1,
                        defaultConstraints = VideoConstraints.NOTHING,
                        videoConstraints = mapOf("s2" to VideoConstraints(180))
                    )
                    getEffectiveConstraints2(sources, allocationSettings) shouldBe zeroEffectiveConstraints.apply {
                        put("s2", VideoConstraints(180))
                    }
                }
                context("With default constraints 0 and non-zero constraints for sources low on the list") {
                    val allocationSettings = AllocationSettings(
                        lastN = 1,
                        defaultConstraints = VideoConstraints.NOTHING,
                        videoConstraints = mapOf("s2" to VideoConstraints(180), "s3" to VideoConstraints(180))
                    )
                    getEffectiveConstraints2(sources, allocationSettings) shouldBe zeroEffectiveConstraints.apply {
                        put("s2", VideoConstraints(180))
                    }
                }
            }
        }
        context("With lastN=3") {
            context("And default settings") {
                val allocationSettings = AllocationSettings(lastN = 3, defaultConstraints = defaultConstraints)
                getEffectiveConstraints2(sources, allocationSettings) shouldBe zeroEffectiveConstraints.apply {
                    put("s1", VideoConstraints(180))
                    put("s2", VideoConstraints(180))
                    put("s3", VideoConstraints(180))
                }
            }
            context("When the top sources have video DISABLED") {
                // The top sources in speaker order have videoType = DISABLED
                val endpoints = listOf(s4, s5, s6, s1, s2, s3)

                context("And default settings") {
                    val allocationSettings = AllocationSettings(lastN = 3, defaultConstraints = defaultConstraints)
                    getEffectiveConstraints2(endpoints, allocationSettings) shouldBe zeroEffectiveConstraints.apply {
                        put("s4", VideoConstraints(180))
                        put("s5", VideoConstraints(180))
                        put("s6", VideoConstraints(180))
                    }
                }
                context("And non-zero constraints for sources down the list") {
                    val allocationSettings = AllocationSettings(
                        lastN = 3,
                        defaultConstraints = VideoConstraints.NOTHING,
                        videoConstraints = mapOf(
                            "s6" to VideoConstraints(180),
                            "s2" to VideoConstraints(180),
                            "s3" to VideoConstraints(180)
                        )
                    )
                    getEffectiveConstraints2(endpoints, allocationSettings) shouldBe zeroEffectiveConstraints.apply {
                        put("s6", VideoConstraints(180))
                        put("s2", VideoConstraints(180))
                        put("s3", VideoConstraints(180))
                    }
                }
            }
        }
    }
}
