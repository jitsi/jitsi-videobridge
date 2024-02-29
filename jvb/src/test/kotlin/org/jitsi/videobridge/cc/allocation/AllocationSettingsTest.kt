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
        }
    }
}
