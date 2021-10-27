/*
 * Copyright @ 2021 - present 8x8, Inc.
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
import org.jitsi.nlj.VideoType

@Suppress("NAME_SHADOWING")
class EffectiveConstraintsTest : ShouldSpec() {
    override fun isolationMode() = IsolationMode.InstancePerLeaf

    init {
        val e1 = TestEndpoint("e1")
        val e2 = TestEndpoint("e2")
        val e3 = TestEndpoint("e3")
        val e4 = TestEndpoint("e4", videoType = VideoType.NONE)
        val e5 = TestEndpoint("e5", videoType = VideoType.NONE)
        val e6 = TestEndpoint("e6", videoType = VideoType.NONE)

        val endpoints = listOf(e1, e2, e3, e4, e5, e6)
        val zeroEffectiveConstraints = mutableMapOf(
            "e1" to VideoConstraints.NOTHING,
            "e2" to VideoConstraints.NOTHING,
            "e3" to VideoConstraints.NOTHING,
            "e4" to VideoConstraints.NOTHING,
            "e5" to VideoConstraints.NOTHING,
            "e6" to VideoConstraints.NOTHING
        )

        context("With lastN=0") {
            val allocationSettings = AllocationSettings(lastN = 0)
            getEffectiveConstraints(endpoints, allocationSettings) shouldBe zeroEffectiveConstraints
        }
        context("With lastN=1") {
            context("And no other constraints") {
                val allocationSettings = AllocationSettings(lastN = 1)
                getEffectiveConstraints(endpoints, allocationSettings) shouldBe zeroEffectiveConstraints.apply {
                    // The default defaultConstraints are 180
                    put("e1", VideoConstraints(180))
                }
            }
            context("And different defaultConstraints") {
                val allocationSettings = AllocationSettings(lastN = 1, defaultConstraints = VideoConstraints(360))
                getEffectiveConstraints(endpoints, allocationSettings) shouldBe zeroEffectiveConstraints.apply {
                    put("e1", VideoConstraints(360))
                }
            }
            context("And all constraints 0") {
                val allocationSettings = AllocationSettings(
                    lastN = 1,
                    defaultConstraints = VideoConstraints.NOTHING,
                )
                getEffectiveConstraints(endpoints, allocationSettings) shouldBe zeroEffectiveConstraints
            }
            context("And non-zero constraints for an endpoint with video") {
                val allocationSettings = AllocationSettings(
                    lastN = 1,
                    defaultConstraints = VideoConstraints.NOTHING,
                    videoConstraints = mapOf("e1" to VideoConstraints(720))

                )
                getEffectiveConstraints(endpoints, allocationSettings) shouldBe zeroEffectiveConstraints.apply {
                    put("e1", VideoConstraints(720))
                }
            }
            context("And non-zero constraints for and endpoint without video") {
                val allocationSettings = AllocationSettings(
                    lastN = 1,
                    defaultConstraints = VideoConstraints.NOTHING,
                    videoConstraints = mapOf("e4" to VideoConstraints(720))
                )
                getEffectiveConstraints(endpoints, allocationSettings) shouldBe zeroEffectiveConstraints.apply {
                    put("e4", VideoConstraints(720))
                }
            }
            context("When the top endpoints do not have video") {
                // The top endpoints in speaker order have no camera
                val endpoints = listOf(e4, e5, e6, e1, e2, e3)

                context("With default settings") {
                    val allocationSettings = AllocationSettings(lastN = 1)
                    getEffectiveConstraints(endpoints, allocationSettings) shouldBe zeroEffectiveConstraints.apply {
                        put("e4", VideoConstraints(180))
                    }
                }
                context("With default constraints 0 and non-zero constraints for an endpoint without video") {
                    val allocationSettings = AllocationSettings(
                        lastN = 1,
                        defaultConstraints = VideoConstraints.NOTHING,
                        videoConstraints = mapOf("e5" to VideoConstraints(180))
                    )
                    getEffectiveConstraints(endpoints, allocationSettings) shouldBe zeroEffectiveConstraints.apply {
                        put("e5", VideoConstraints(180))
                    }
                }
                context("With default constraints 0 and non-zero constraints for an endpoint with video") {
                    val allocationSettings = AllocationSettings(
                        lastN = 1,
                        defaultConstraints = VideoConstraints.NOTHING,
                        videoConstraints = mapOf("e2" to VideoConstraints(180))
                    )
                    getEffectiveConstraints(endpoints, allocationSettings) shouldBe zeroEffectiveConstraints.apply {
                        put("e2", VideoConstraints(180))
                    }
                }
                context("With default constraints 0 and non-zero constraints for endpoints low on the list") {
                    val allocationSettings = AllocationSettings(
                        lastN = 1,
                        defaultConstraints = VideoConstraints.NOTHING,
                        videoConstraints = mapOf("e2" to VideoConstraints(180), "e3" to VideoConstraints(180))
                    )
                    getEffectiveConstraints(endpoints, allocationSettings) shouldBe zeroEffectiveConstraints.apply {
                        put("e2", VideoConstraints(180))
                    }
                }
            }
        }
        context("With lastN=3") {
            context("And default settings") {
                val allocationSettings = AllocationSettings(lastN = 3)
                getEffectiveConstraints(endpoints, allocationSettings) shouldBe zeroEffectiveConstraints.apply {
                    put("e1", VideoConstraints(180))
                    put("e2", VideoConstraints(180))
                    put("e3", VideoConstraints(180))
                }
            }
            context("When the top endpoints do not have video") {
                // The top endpoints in speaker order have no camera
                val endpoints = listOf(e4, e5, e6, e1, e2, e3)

                context("And default settings") {
                    val allocationSettings = AllocationSettings(lastN = 3)
                    getEffectiveConstraints(endpoints, allocationSettings) shouldBe zeroEffectiveConstraints.apply {
                        put("e4", VideoConstraints(180))
                        put("e5", VideoConstraints(180))
                        put("e6", VideoConstraints(180))
                    }
                }
                context("And non-zero constraints for endpoints down the list") {
                    val allocationSettings = AllocationSettings(
                        lastN = 3,
                        defaultConstraints = VideoConstraints.NOTHING,
                        videoConstraints = mapOf(
                            "e6" to VideoConstraints(180),
                            "e2" to VideoConstraints(180),
                            "e3" to VideoConstraints(180)
                        )
                    )
                    getEffectiveConstraints(endpoints, allocationSettings) shouldBe zeroEffectiveConstraints.apply {
                        put("e6", VideoConstraints(180))
                        put("e2", VideoConstraints(180))
                        put("e3", VideoConstraints(180))
                    }
                }
            }
        }
    }
}
