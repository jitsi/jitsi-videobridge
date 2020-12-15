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

class AllocationSettingsTest : ShouldSpec() {
    init {
        context("computeVideoConstraints") {
            context("Stage view behavior") {
                val allocationSettings = AllocationSettingsWrapper()
                allocationSettings.setMaxFrameHeight(720)
                allocationSettings.setSelectedEndpoints(listOf("A"))

                allocationSettings.get().strategy shouldBe AllocationStrategy.StageView
                allocationSettings.get().videoConstraints.shouldContainExactly(
                    mapOf(
                        "A" to VideoConstraints(720)
                    )
                )
                allocationSettings.get().selectedEndpoints shouldBe listOf("A")
            }
            context("Tile view behavior") {
                val allocationSettings = AllocationSettingsWrapper()
                allocationSettings.setMaxFrameHeight(180)
                allocationSettings.setSelectedEndpoints(listOf("A", "B", "C", "D"))

                allocationSettings.get().strategy shouldBe AllocationStrategy.TileView
                allocationSettings.get().videoConstraints.shouldContainExactly(
                    mapOf(
                        "A" to VideoConstraints(180),
                        "B" to VideoConstraints(180),
                        "C" to VideoConstraints(180),
                        "D" to VideoConstraints(180)
                    )
                )
                // The legacy API (currently used by jitsi-meet) uses "selected count > 0" to infer TileView, and the
                // desired behavior in TileView is to not have selected endpoints.
                allocationSettings.get().selectedEndpoints shouldBe emptyList()
            }
        }
    }
}
