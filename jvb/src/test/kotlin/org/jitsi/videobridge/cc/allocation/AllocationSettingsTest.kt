/*
 * Copyright @ 2020 - present 8x8, Inc.
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

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import org.jitsi.config.withNewConfig
import org.jitsi.videobridge.message.ReceiverVideoConstraintsMessage

class AllocationSettingsTest : ShouldSpec() {
    init {
        context("computeVideoConstraints") {
            context("With client which supports source names") {
                context("no conversion from endpoint to source takes place") {
                    val allocationSettings = AllocationSettingsWrapper()
                    allocationSettings.setBandwidthAllocationSettings(
                        ReceiverVideoConstraintsMessage(
                            onStageSources = listOf("S1", "S2"),
                            selectedSources = listOf("S3", "S4"),
                            constraints = mapOf(
                                "S1" to VideoConstraints(720),
                                "E1" to VideoConstraints(360)
                            )
                        )
                    )

                    allocationSettings.get().onStageSources shouldBe listOf("S1", "S2")
                    allocationSettings.get().selectedSources shouldBe listOf("S3", "S4")

                    allocationSettings.get().onStageEndpoints shouldBe emptyList()
                    allocationSettings.get().selectedEndpoints shouldBe emptyList()

                    allocationSettings.get().videoConstraints shouldBe mapOf(
                        "S1" to VideoConstraints(720),
                        // There's no error when endpoint is used in constraint
                        "E1" to VideoConstraints(360)
                    )
                }
            }
            context("with initial constraints different from default default constraints") {
                withNewConfig(
                    """
                        videobridge.cc.initial-max-height-px = 0
                        videobridge.cc.default-max-height-px = 180
                    """.trimIndent()
                ) {
                    should("consider an empty receiver video constraints a change") {
                        val allocationSettings = AllocationSettingsWrapper()
                        val changed =
                            allocationSettings.setBandwidthAllocationSettings(ReceiverVideoConstraintsMessage())
                        changed shouldBe true
                    }
                    should("consider the default receiver video constraints a change") {
                        val allocationSettings = AllocationSettingsWrapper()
                        val changed =
                            allocationSettings.setBandwidthAllocationSettings(
                                ReceiverVideoConstraintsMessage(defaultConstraints = VideoConstraints(maxHeight = 180))
                            )
                        changed shouldBe true
                    }
                    should("not consider the initial receiver video constraints a change") {
                        val allocationSettings = AllocationSettingsWrapper()
                        val changed =
                            allocationSettings.setBandwidthAllocationSettings(
                                ReceiverVideoConstraintsMessage(defaultConstraints = VideoConstraints(maxHeight = 0))
                            )
                        changed shouldBe false
                    }
                    should("not apply the default default constraints after they've been set explicitly") {
                        val allocationSettings = AllocationSettingsWrapper()
                        allocationSettings.setBandwidthAllocationSettings(
                            ReceiverVideoConstraintsMessage(defaultConstraints = VideoConstraints(maxHeight = 720))
                        )
                        val changed = allocationSettings.setBandwidthAllocationSettings(
                            ReceiverVideoConstraintsMessage()
                        )
                        changed shouldBe false
                        allocationSettings.get().defaultConstraints shouldBe VideoConstraints(maxHeight = 720)
                    }
                }
            }
            context("With an assumed bandwidth limit set") {
                withNewConfig("videobridge.cc.assumed-bandwidth-limit = 10 Mbps") {
                    should("limit an assumed bandwidth to the desired rate") {
                        val allocationSettings = AllocationSettingsWrapper()
                        val changed = allocationSettings.setBandwidthAllocationSettings(
                            ReceiverVideoConstraintsMessage(assumedBandwidthBps = 20_000_000)
                        )
                        changed shouldBe true
                        allocationSettings.get().assumedBandwidthBps shouldBe 10_000_000
                    }
                    should("Not limit a value less than the configured limit") {
                        val allocationSettings = AllocationSettingsWrapper()
                        val changed = allocationSettings.setBandwidthAllocationSettings(
                            ReceiverVideoConstraintsMessage(assumedBandwidthBps = 2_000_000)
                        )
                        changed shouldBe true
                        allocationSettings.get().assumedBandwidthBps shouldBe 2_000_000
                    }
                    should("Not consider the default value to be a change") {
                        val allocationSettings = AllocationSettingsWrapper()
                        // Set this twice because of the initial vs. default max height difference above.
                        val constraints = ReceiverVideoConstraintsMessage(assumedBandwidthBps = -1)
                        allocationSettings.setBandwidthAllocationSettings(constraints)
                        val changed = allocationSettings.setBandwidthAllocationSettings(constraints)
                        changed shouldBe false
                    }
                }
            }
        }
    }
}
