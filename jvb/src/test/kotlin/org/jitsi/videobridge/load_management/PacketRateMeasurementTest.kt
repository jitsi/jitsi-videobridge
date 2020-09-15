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

package org.jitsi.videobridge.load_management

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe

class PacketRateMeasurementTest : ShouldSpec({
    context("division") {
        context("between two PacketRateMeasurements") {
            should("yield the correct result") {
                PacketRateMeasurement(10) / PacketRateMeasurement(2) shouldBe 5.0
            }
        }
        context("between a PacketRateMeasurement and another measurement type") {
            should("throw an exception") {
                shouldThrow<UnsupportedOperationException> {
                    PacketRateMeasurement(10) / MockLoadMeasurement(1.1)
                }
            }
        }
    }
})
