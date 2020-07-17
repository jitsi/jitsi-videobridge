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

import io.kotlintest.IsolationMode
import io.kotlintest.Spec
import io.kotlintest.TestCase
import io.kotlintest.shouldBe
import io.kotlintest.shouldThrow
import io.kotlintest.specs.ShouldSpec
import org.jitsi.config.NewJitsiConfig
import org.jitsi.metaconfig.ConfigException
import org.jitsi.metaconfig.MapConfigSource

class WebsocketServiceConfigTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    private val newConfig = MapConfigSource("new")
    private val legacyConfig = MapConfigSource("legacy")
    private lateinit var config: WebsocketServiceConfig

    override fun beforeSpec(spec: Spec) {
        super.beforeSpec(spec)
        NewJitsiConfig.legacyConfig = legacyConfig
        NewJitsiConfig.newConfig = newConfig
    }

    override fun beforeTest(testCase: TestCase) {
        super.beforeTest(testCase)
        config = WebsocketServiceConfig()
    }

    override fun afterSpec(spec: Spec) {
        super.afterSpec(spec)
        NewJitsiConfig.legacyConfig = NewJitsiConfig.SipCommunicatorPropsConfigSource
        NewJitsiConfig.newConfig = NewJitsiConfig.TypesafeConfig
    }

    init {
        "when websockets are disabled" {
            newConfig["videobridge.websockets.enabled"] = false
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
        "when websockets are enabled" {
            newConfig["videobridge.websockets.enabled"] = true
            "accessing domain" {
                newConfig["videobridge.websockets.domain"] = "new_domain"
                should("get the right value") {
                    config.domain shouldBe "new_domain"
                }
            }
            "accessing useTls" {
                "when no value has been set" {
                    should("return null") {
                        config.useTls shouldBe null
                    }
                }
                "when a value has been set" {
                    newConfig["videobridge.websockets.tls"] = true
                    should("get the right value xx") {
                        config.useTls shouldBe true
                    }
                }
            }
        }
    }
}
