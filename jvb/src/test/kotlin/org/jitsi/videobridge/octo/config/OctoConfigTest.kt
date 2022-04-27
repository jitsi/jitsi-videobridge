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
import org.jitsi.config.withLegacyConfig
import org.jitsi.config.withNewConfig

internal class OctoConfigTest : ConfigTest() {
    init {
        context("enabled") {
            context("when bind address and bind port are defined in legacy config") {
                withLegacyConfig(legacyConfigWithBindAddressAndBindPort) {
                    withNewConfig(newConfigOctoDisabled) {
                        should("be true") {
                            OctoConfig.config.enabled shouldBe true
                        }
                    }
                }
            }
            context("when bind address is set in legacy config but not bind port") {
                withLegacyConfig(legacyConfigWithBindAddressNoBindPort) {
                    should("be false") {
                        OctoConfig.config.enabled shouldBe false
                    }
                    context("and set as true in new config") {
                        withNewConfig(newConfigOctoEnabled) {
                            should("be false") {
                                OctoConfig.config.enabled shouldBe false
                            }
                        }
                    }
                }
            }
            context("when bind port is set in legacy config but not bind address") {
                withLegacyConfig(legacyConfigWithBindPortNoBindAddress) {
                    withNewConfig(newConfigOctoEnabled) {
                        should("be false") {
                            OctoConfig.config.enabled shouldBe false
                        }
                    }
                }
            }
            context("when enabled is true in new config and bind address/bind port are not defined in old config") {
                withNewConfig(newConfigOctoEnabled) {
                    should("be true") {
                        OctoConfig.config.enabled shouldBe true
                    }
                }
            }
        }
        context("bindAddress") {
            context("when the value isn't set in legacy config") {
                withNewConfig(newConfigBindAddress) {
                    should("be the value from new config") {
                        OctoConfig.config.bindAddress shouldBe "127.0.0.1"
                    }
                }
            }
        }
    }
}

private val legacyConfigWithBindAddressAndBindPort = """
    org.jitsi.videobridge.octo.BIND_ADDRESS=127.0.0.1
    org.jitsi.videobridge.octo.BIND_PORT=8080
""".trimIndent()

private val legacyConfigWithBindAddressNoBindPort = """
    org.jitsi.videobridge.octo.BIND_ADDRESS=127.0.0.1
""".trimIndent()

private val legacyConfigWithBindPortNoBindAddress = """
    org.jitsi.videobridge.octo.BIND_PORT=8080
""".trimIndent()

private val newConfigOctoDisabled = """
    videobridge {
        octo {
            enabled=false
        }
    }
""".trimIndent()

private val newConfigOctoEnabled = """
    videobridge {
        octo {
            enabled=true
        }
    }
""".trimIndent()

private val newConfigBindAddress = """
    videobridge {
        octo {
            bind-address = "127.0.0.1"
        }
    }
""".trimIndent()
