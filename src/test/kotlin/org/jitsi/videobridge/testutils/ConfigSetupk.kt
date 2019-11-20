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

package org.jitsi.videobridge.testutils


import org.jitsi.utils.config.ConfigSource
import org.jitsi.utils.config.exception.ConfigPropertyNotFoundException
import org.jitsi.utils.config.exception.ConfigurationValueTypeUnsupportedException
import org.jitsi.videobridge.config.ConfigSupplierSettingsk
import org.jitsi.videobridge.config.JvbConfigk
import java.time.Duration
import java.util.LinkedList
import kotlin.reflect.KClass

/**
 * Helper class to make installing new and legacy configurations
 * easier.
 */
//TODO: do we still need this?
class ConfigSetupk {
    private var commandLineArgs: MutableList<String> = LinkedList()
    private var legacyConfigSupplier = { EMPTY_CONFIG }
    private var newConfigSupplier = { EMPTY_CONFIG }

    fun withLegacyConfig(config: ConfigSource): ConfigSetupk {
        legacyConfigSupplier = { config }

        return this
    }

    fun withNewConfig(config: ConfigSource): ConfigSetupk {
        newConfigSupplier = { config }

        return this
    }

    fun withCommandLineArg(argName: String, value: String): ConfigSetupk {
        commandLineArgs.add("$argName=$value")

        return this
    }

    fun finishSetup() {
        // Make sure config doesn't see any command line args from a previous setup
        ConfigSupplierSettingsk.commandLineArgsSupplier = { commandLineArgs.toTypedArray() }
        ConfigSupplierSettingsk.legacyConfigSupplier = legacyConfigSupplier
        ConfigSupplierSettingsk.configSupplier = newConfigSupplier

        JvbConfigk.reloadConfig()
    }

    companion object {
    }
}

class MockConfigSource private constructor(
    override val name: String,
    private val props: MutableMap<String, Any>,
    // Just to distinguish this constructor from the public one
    dummy: Int
) : ConfigSource, MutableMap<String, Any> by props {

    /**
     * The caller should pass a non-mutable map of properties, because all
     * modifications to the props should be done via access the MockConfigSource
     * instance, not the given map directly.
     */
    constructor(name: String, props: Map<String, Any>) : this(name, props.toMutableMap(), 42)
    var numGetsCalled = 0

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> getterFor(valueType: KClass<T>): (String) -> T {
        return when(valueType) {
            Int::class -> getterHelper(::getInt)
            Long::class -> getterHelper(::getLong)
            Duration::class -> getterHelper(::getDuration)
            else -> throw ConfigurationValueTypeUnsupportedException.new(valueType)
        }
    }

    private fun getInt(path: String): Int? = props[path] as? Int
    private fun getLong(path: String): Long? = props[path] as? Long
    private fun getDuration(path: String): Duration? = props[path] as? Duration

    @Suppress("UNCHECKED_CAST")
    private fun <U, T : Any> getterHelper(getter: (String) -> U): (String) -> T {
        return { path ->
            numGetsCalled++
            getter(path) as? T ?:
            throw ConfigPropertyNotFoundException("Could not find value for property at '$path'")
        }
    }
}
