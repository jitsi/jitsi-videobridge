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

package org.jitsi.videobridge.websocket.config

import io.kotlintest.TestCase
import io.kotlintest.shouldBe
import io.kotlintest.shouldThrow
import org.jitsi.ConfigTest
import org.jitsi.metaconfig.ConfigException

class WebsocketServiceConfigTest : ConfigTest() {
    private lateinit var config: WebsocketServiceConfig

    override fun beforeTest(testCase: TestCase) {
        super.beforeTest(testCase)
        config = WebsocketServiceConfig()
    }

    init {
        "when websockets are disabled" {
            withNewConfig("videobridge.websockets.enabled = false") {
                "accessing domain should throw" {
                    shouldThrow<ConfigException.UnableToRetrieve.ConditionNotMet> {
                        config.domain
                    }
                }
                "accessing useTls should throw" {
                    shouldThrow<ConfigException.UnableToRetrieve.ConditionNotMet> {
                        config.useTls
                    }
                }
            }
        }
        "when websockets are enabled" {
            "accessing domain" {
                withNewConfig(newConfigWebsocketsEnabledDomain) {
                    should("get the right value") {
                        config.domain shouldBe "new_domain"
                    }
                }
            }
            "accessing useTls" {
                "when no value has been set" {
                    withNewConfig(newConfigWebsocketsEnabled) {
                        should("return null") {
                            config.useTls shouldBe null
                        }
                    }
                }
                "when a value has been set" {
                    withNewConfig(newConfigWebsocketsEnableduseTls) {
                        should("get the right value") {
                            config.useTls shouldBe true
                        }
                    }
                }
            }
        }
    }
}
private val newConfigWebsocketsEnabled = """
    videobridge.websockets.enabled = true
""".trimIndent()

private val newConfigWebsocketsEnabledDomain = newConfigWebsocketsEnabled + "\n" + """
    videobridge.websockets.domain = "new_domain"
""".trimIndent()

private val newConfigWebsocketsEnableduseTls = newConfigWebsocketsEnabled + "\n" + """
    videobridge.websockets.tls = true
""".trimIndent()
