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
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.jitsi.videobridge.message.ReceiverVideoConstraintsMessage

class AllocationSettingsTest : ShouldSpec() {
    init {
        context("computeVideoConstraints") {
            context("Convert selectedEndpoints to selectedSources") {
                // TODO write a test for sourceNames=true
                val allocationSettings = AllocationSettingsWrapper(false)
                allocationSettings.setBandwidthAllocationSettings(
                    ReceiverVideoConstraintsMessage(
                        onStageEndpoints = listOf("A"),
                        constraints = mapOf("A" to VideoConstraints(720))
                    )
                )

                // Backwards compatibility mode converts endpoints to source names
                allocationSettings.get().selectedEndpoints shouldBe emptyList()
                allocationSettings.get().onStageEndpoints shouldBe emptyList()
                allocationSettings.get().onStageSources shouldBe listOf("A-v0")
                allocationSettings.get().videoConstraints.shouldContainExactly(
                    mapOf(
                        "A-v0" to VideoConstraints(720)
                    )
                )
            }
            context("Tile view behavior") {
                // TODO write a test for sourceNames=true
                val allocationSettings = AllocationSettingsWrapper(false)
                allocationSettings.setBandwidthAllocationSettings(
                    ReceiverVideoConstraintsMessage(
                        selectedEndpoints = listOf("A", "B", "C", "D"),
                        constraints = mapOf(
                            "A" to VideoConstraints(180),
                            "B" to VideoConstraints(360),
                            "C" to VideoConstraints(720),
                        )
                    )
                )

                allocationSettings.get().selectedEndpoints shouldBe emptyList()
                allocationSettings.get().selectedSources shouldBe listOf("A-v0", "B-v0", "C-v0", "D-v0")
                allocationSettings.get().videoConstraints.shouldContainExactly(
                    mapOf(
                        "A" to VideoConstraints(180),
                        "B" to VideoConstraints(180),
                        "C" to VideoConstraints(180),
                        "D" to VideoConstraints(180)
                    )
                )
            }
        }
    }
}
