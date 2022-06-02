/*
 * Copyright @ 2020 - Present, 8x8 Inc
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
package org.jitsi.videobridge.octo.config

import io.kotest.matchers.shouldBe
import org.jitsi.ConfigTest
import org.jitsi.config.withNewConfig

internal class OctoConfigTest : ConfigTest() {
    init {
        context("enabled") {
            context("when enabled is true in new config and bind address/bind port are not defined in old config") {
                withNewConfig(newConfigOctoEnabled) {
                    should("be true") {
                        OctoConfig.config.enabled shouldBe true
                    }
                }
                withNewConfig(newConfigOctoDisabled) {
                    should("be true") {
                        OctoConfig.config.enabled shouldBe false
                    }
                }
            }
        }
        context("relay-id") {
            withNewConfig("videobridge.octo.relay-id=abc") {
                OctoConfig.config.relayId shouldBe "abc"
            }
        }
    }
}

private const val newConfigOctoDisabled = "videobridge.octo.enabled=false"
private const val newConfigOctoEnabled = "videobridge.octo.enabled=true"
