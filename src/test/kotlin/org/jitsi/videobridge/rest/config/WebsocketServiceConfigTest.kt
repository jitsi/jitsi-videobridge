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

package org.jitsi.videobridge.rest.config

import io.kotlintest.shouldBe
import io.kotlintest.shouldThrow
import org.jitsi.config.BooleanMockConfigValueGenerator
import org.jitsi.config.MockConfigValue
import org.jitsi.config.MockConfigValueGenerator
import org.jitsi.config.runBasicTests
import org.jitsi.videobridge.JitsiConfigTest
import org.jitsi.videobridge.config.ConditionalPropertyConditionNotMetException
import org.jitsi.videobridge.testutils.MockConfigSource
import org.jitsi.videobridge.testutils.resetSingleton

class WebsocketServiceConfigTest : JitsiConfigTest() {
    init {
        // By default install config sources which can be modified later
        val legacyConfig = MockConfigSource("legacy", mapOf())
        val newConfig = MockConfigSource("new", mapOf())
        withLegacyConfig(legacyConfig)
        withNewConfig(newConfig)
        "enabled" {
            runBasicTests(
                legacyConfigName = "org.jitsi.videobridge.rest.COLIBRI_WS_DISABLE",
                legacyValueGenerator = InvertedBooleanMockConfigValueGenerator(),
                newConfigName = "videobridge.websockets.enabled",
                newConfigValueGenerator = BooleanMockConfigValueGenerator(),
                propCreator = { WebsocketServiceConfig.Config.Companion.EnabledProperty() }
            )
        }
        "domain" {
            val propCreator = { WebsocketServiceConfig.Config.Companion.DomainProperty() }
            "when websockets are disabled" {
                disableWebsockets(legacyConfig)
                val prop = propCreator()
                shouldThrow<ConditionalPropertyConditionNotMetException> {
                    prop.value
                }
            }
            "when websockets are enabled" {
                enableWebsockets(legacyConfig)
                "and websocket domain is set in old config" {
                    legacyConfig["org.jitsi.videobridge.rest.COLIBRI_WS_DOMAIN"] = "domain"
                    val prop = propCreator()
                    prop.value shouldBe "domain"
                }
                "and websocket domain is set in new config" {
                    newConfig["videobridge.websockets.domain"] = "new_domain"
                    val prop = propCreator()
                    prop.value shouldBe "new_domain"
                }
            }
        }
        "tls" {
            val propCreator = { WebsocketServiceConfig.Config.Companion.TlsProperty() }
            "when websockets are disabled" {
                disableWebsockets(legacyConfig)
                // Even if config is set
                legacyConfig["org.jitsi.videobridge.rest.COLIBRI_WS_TLS"] = true
                val prop = propCreator()
                shouldThrow<ConditionalPropertyConditionNotMetException> {
                    prop.value
                }
            }
            "when websockets are enabled" {
                enableWebsockets(legacyConfig)
                "and websocket domain is set in old config" {
                    legacyConfig["org.jitsi.videobridge.rest.COLIBRI_WS_TLS"] = true
                    val prop = propCreator()
                    prop.value shouldBe true
                }
                "and websocket domain is set in new config" {
                    newConfig["videobridge.websockets.tls"] = false
                    val prop = propCreator()
                    prop.value shouldBe false
                }
            }
        }
        "server id" {
            val propCreator = { WebsocketServiceConfig.Config.Companion.ServerIdProperty() }
            "when websockets are disabled" {
                disableWebsockets(legacyConfig)
                // Even if config is set
                legacyConfig["org.jitsi.videobridge.rest.COLIBRI_WS_SERVER_ID"] = "server_id"
                val prop = propCreator()
                shouldThrow<ConditionalPropertyConditionNotMetException> {
                    prop.value
                }
            }
            "when websockets are enabled" {
                enableWebsockets(legacyConfig)
                "and websocket domain is set in old config" {
                    legacyConfig["org.jitsi.videobridge.rest.COLIBRI_WS_SERVER_ID"] = "server_id"
                    val prop = propCreator()
                    prop.value shouldBe "server_id"
                }
                "and websocket domain is set in new config" {
                    newConfig["videobridge.websockets.server-id"] = "new_server_id"
                    val prop = propCreator()
                    prop.value shouldBe "new_server_id"
                }
            }
        }
    }

    private fun enableWebsockets(legacyConfig: MockConfigSource) {
        legacyConfig["org.jitsi.videobridge.rest.COLIBRI_WS_DISABLE"] = false
        resetEnabledProperty()
    }

    private fun disableWebsockets(legacyConfig: MockConfigSource) {
        legacyConfig["org.jitsi.videobridge.rest.COLIBRI_WS_DISABLE"] = true
        resetEnabledProperty()
    }

    private fun resetEnabledProperty() {
        resetSingleton(
            "enabledProp",
            WebsocketServiceConfig.Config.Companion
        )
    }

    private class InvertedBooleanMockConfigValueGenerator : MockConfigValueGenerator<Boolean, Boolean> {
        private var currValue: Boolean = true

        override fun gen(): MockConfigValue<Boolean, Boolean> {
            return MockConfigValue(currValue, !currValue).also {
                currValue = !currValue
            }
        }
    }
}