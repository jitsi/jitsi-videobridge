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
package org.jitsi.videobridge.load_management

import io.kotest.matchers.shouldBe
import org.jitsi.ConfigTest
import org.jitsi.config.withNewConfig

class ConferenceSizeLastNLimitsTest : ConfigTest() {
    init {
        context("With limits set") {
            withNewConfig(
                """
            videobridge.load-management.conference-last-n-limits {
                // Intentionally out of order
                40 = 10,
                20 = 20,
                30 = 15,
                50 = 5
            }
                """.trimIndent()
            ) {
                ConferenceSizeLastNLimits().apply {
                    getLastNLimit(19) shouldBe -1
                    getLastNLimit(20) shouldBe 20
                    getLastNLimit(21) shouldBe 20

                    getLastNLimit(29) shouldBe 20
                    getLastNLimit(30) shouldBe 15
                    getLastNLimit(31) shouldBe 15

                    getLastNLimit(40) shouldBe 10

                    getLastNLimit(50) shouldBe 5
                    getLastNLimit(1000) shouldBe 5
                }
            }
        }
        context("With no limits set") {
            withNewConfig(
                """
            videobridge.load-management.conference-last-n-limits { }
                """.trimIndent()
            ) {
                ConferenceSizeLastNLimits().apply {
                    getLastNLimit(111) shouldBe -1
                }
            }
        }
    }
}
