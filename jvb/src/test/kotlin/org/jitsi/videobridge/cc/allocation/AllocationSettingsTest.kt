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
                val (strategy, constraints) = computeVideoConstraints(720, listOf("A"))

                strategy shouldBe AllocationStrategy.StageView
                constraints.shouldContainExactly(
                    mapOf(
                        "A" to VideoConstraints(720)
                    )
                )
            }
            context("Tile view behavior") {
                val (strategy, constraints) = computeVideoConstraints(180, listOf("A", "B", "C", "D"))

                strategy shouldBe AllocationStrategy.TileView
                constraints.shouldContainExactly(
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
