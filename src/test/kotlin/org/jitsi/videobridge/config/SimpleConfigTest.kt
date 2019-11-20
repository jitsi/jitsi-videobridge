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

package org.jitsi.videobridge.config

import io.kotlintest.IsolationMode
import io.kotlintest.matchers.withClue
import io.kotlintest.specs.ShouldSpec
import io.kotlintest.shouldBe
import org.jitsi.utils.config.ConfigSource
import org.jitsi.videobridge.testutils.EMPTY_CONFIG
import org.jitsi.videobridge.testutils.MockConfigSource

class SimpleConfigTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    private val legacyConfigNoValue =
        MockConfigSource("legacy config", mapOf("some.other.prop.name" to 42))
    private val legacyConfigWithValue =
        MockConfigSource("legacy config", mapOf(legacyName to legacyValue))
    private val newConfig =
        MockConfigSource("new config", mapOf(newName to newValue))

    init {
        "when old config is present" {
            "but doesn't provide a value" {
                val legacyConfig = legacyConfigNoValue
                "and the new config does" {
                    val newConfig = newConfig
                    testProps(legacyConfig, newConfig, newName, newConfig)
                }
            }
            "and provides a value" {
                val legacyConfig = legacyConfigWithValue
                "and so does the new one" {
                    val newConfig = newConfig
                    testProps(legacyConfig, newConfig, legacyName, legacyConfig)
                }
            }
        }
        "when there's no old config" {
            val legacyConfig = EMPTY_CONFIG
            "and new config provides a value" {
                val newConfig = newConfig
                testProps(legacyConfig, newConfig, newName, newConfig)
            }
        }
    }

    private fun testProps(
        legacyConfig: ConfigSource,
        newConfig: ConfigSource,
        expectedKey: String,
        expectedSourceOfValue: MockConfigSource) {

        val readOnceProp = TestReadOnceProperty(legacyConfig, newConfig)
        val readEveryTimeProp = TestReadEveryTimeProperty(legacyConfig, newConfig)
        val originalExpectedValue = expectedSourceOfValue.getterFor(Int::class).invoke(expectedKey)

        for (prop in listOf(readOnceProp, readEveryTimeProp)) {
            withClue("${prop.javaClass.simpleName} should read the correct value") {
                prop.value shouldBe originalExpectedValue
            }
        }
        // 4242 is assumed to be some value different than whatever the value was before
        expectedSourceOfValue[expectedKey] = 4242
        withClue("ReadOnceProp should always see the original value") {
            readOnceProp.value shouldBe originalExpectedValue
        }
        withClue("ReadEveryTimeProp should always see the new value") {
            readEveryTimeProp.value shouldBe 4242
        }
    }

    companion object {
        private const val legacyName = "some.legacy.prop.name"
        private const val legacyValue = 5
        private const val newName = "some.new.prop.name"
        private const val newValue = 10
    }

    private class TestReadOnceProperty(
        legacyConfig: ConfigSource,
        newConfig: ConfigSource
    ) : SimpleConfig<Int>(
        valueType = Int::class,
        legacyName = legacyName,
        newName = newName,
        readOnce = true,
        legacyConfig = legacyConfig,
        newConfig = newConfig
    )

    private class TestReadEveryTimeProperty(
        legacyConfig: ConfigSource,
        newConfig: ConfigSource
    ) : SimpleConfig<Int>(
        valueType = Int::class,
        legacyName = legacyName,
        newName = newName,
        readOnce = false,
        legacyConfig = legacyConfig,
        newConfig = newConfig
    )
}