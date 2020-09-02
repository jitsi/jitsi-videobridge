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

import io.kotest.core.test.TestCase
import io.kotest.matchers.shouldBe
import org.jitsi.ConfigTest

class WebsocketServiceConfigTest : ConfigTest() {
    private lateinit var config: WebsocketServiceConfig

    override fun beforeTest(testCase: TestCase) {
        super.beforeTest(testCase)
        config = WebsocketServiceConfig()
    }

    init {
        context("when websockets are enabled") {
            context("accessing domain") {
                withNewConfig(newConfigWebsocketsEnabledDomain) {
                    should("get the right value") {
                        config.domain shouldBe "new_domain"
                    }
                }
            }
            context("accessing useTls") {
                context("when no value has been set") {
                    withNewConfig(newConfigWebsocketsEnabled) {
                        should("return null") {
                            config.useTls shouldBe null
                        }
                    }
                }
                context("when a value has been set") {
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
