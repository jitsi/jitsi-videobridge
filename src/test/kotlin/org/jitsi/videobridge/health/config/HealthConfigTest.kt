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

import io.kotlintest.shouldBe
import org.jitsi.utils.config.ConfigProperty
import org.jitsi.videobridge.ConfigTest
import org.jitsi.videobridge.testutils.EMPTY_CONFIG
import org.jitsi.videobridge.testutils.MockConfigSource
import java.time.Duration
import kotlin.reflect.KProperty
import kotlin.reflect.full.companionObject
import kotlin.reflect.full.declaredMembers
import kotlin.reflect.jvm.isAccessible

class HealthConfigTest : ConfigTest() {

    private val legacyConfigWithValue = MockConfigSource("legacy",
        mapOf("org.jitsi.videobridge.health.INTERVAL" to 1000L)
    )

    private val newConfig = MockConfigSource("new",
        mapOf("videobridge.health.interval" to Duration.ofSeconds(5)))



    // Option 1
    @Suppress("UNCHECKED_CAST")
    private fun <T : Any>createProp(creatorName: String): ConfigProperty<T> {
        val creator = HealthConfig::class.companionObject?.declaredMembers?.find { it.name == creatorName } ?: TODO()
        creator.isAccessible = true
        val propCreator = (creator.call(HealthConfig::class) as () -> ConfigProperty<T>)
        return propCreator()
    }

    private fun createIntervalProp(): ConfigProperty<Long> =
        createProp<Long>("intervalPropCreator")

    init {
        "health interval" {
            "when legacy config contains a value" {
                withLegacyConfig(legacyConfigWithValue)
                withNewConfig(newConfig)
                val prop = createIntervalProp()
                should("find the value in legacy config") {
                    prop.value shouldBe 1000L
                }
            }
            "when legacy config does not contain a value" {
                withLegacyConfig(EMPTY_CONFIG)
                withNewConfig(newConfig)
                val prop = createIntervalProp()
                should("find the value in new config") {
                    prop.value shouldBe 5000L
                }
            }
        }
    }
}

class HealthConfigTest2 : ConfigTest() {

    private val legacyConfigWithValue = MockConfigSource("legacy",
        mapOf("org.jitsi.videobridge.health.INTERVAL" to 1000L)
    )

    private val newConfig = MockConfigSource("new",
        mapOf("videobridge.health.interval" to Duration.ofSeconds(5)))

    private fun createIntervalProp(): ConfigProperty<Long> =
        HealthConfig.Companion.HealthIntervalProperty()

    init {
        "health interval" {
            "when legacy config contains a value" {
                withLegacyConfig(legacyConfigWithValue)
                withNewConfig(newConfig)
                val prop = createIntervalProp()
                should("find the value in legacy config") {
                    prop.value shouldBe 1000L
                }
            }
            "when legacy config does not contain a value" {
                withLegacyConfig(EMPTY_CONFIG)
                withNewConfig(newConfig)
                val prop = createIntervalProp()
                should("find the value in new config") {
                    prop.value shouldBe 5000L
                }
            }
        }
    }
}
