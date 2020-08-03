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

package org.jitsi.videobridge.health.config

import io.kotest.matchers.shouldBe
import org.jitsi.ConfigTest
import java.time.Duration

class HealthConfigTest : ConfigTest() {
    init {
        context("health interval") {
            context("when legacy config and new config define a value") {
                withLegacyConfig(legacyConfigWithHealthInterval) {
                    withNewConfig(newConfigWithHealthInterval) {
                        should("use the value from legacy config") {
                            HealthConfig().interval shouldBe Duration.ofSeconds(1)
                        }
                    }
                }
            }
            context("when only new config defines a value") {
                withNewConfig(newConfigWithHealthInterval) {
                    should("use the value from the new config") {
                        HealthConfig().interval shouldBe Duration.ofSeconds(5)
                    }
                }
            }
        }
    }
}

private val legacyConfigWithHealthInterval = """
    org.jitsi.videobridge.health.INTERVAL=1000
""".trimIndent()

private val newConfigWithHealthInterval = """
    videobridge.health.interval = 5 seconds
""".trimIndent()
