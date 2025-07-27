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

package org.jitsi.videobridge

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe

class JvbAudioLastNTest : ShouldSpec({
    context("calculateAudioLastN") {
        should("return -1 when all inputs are -1") {
            calculateAudioLastN(-1, -1, -1) shouldBe -1
        }

        should("return the minimum value when all inputs are positive") {
            calculateAudioLastN(5, 3, 7) shouldBe 3
        }

        should("return the minimum value when some inputs are -1") {
            calculateAudioLastN(-1, 3, 7) shouldBe 3
        }

        should("return -1 when all inputs are -1") {
            calculateAudioLastN(-1) shouldBe -1
        }

        should("return the value when only one input") {
            calculateAudioLastN(5) shouldBe 5
        }
    }

    context("JvbAudioLastN") {
        should("initialize with default value") {
            val jvbAudioLastN = JvbAudioLastN()
            jvbAudioLastN.get() shouldBe -1 // Default value from config
        }

        should("allow setting and getting custom value") {
            val jvbAudioLastN = JvbAudioLastN()
            jvbAudioLastN.jvbAudioLastN = 5
            jvbAudioLastN.get() shouldBe 5
        }
    }
}) 