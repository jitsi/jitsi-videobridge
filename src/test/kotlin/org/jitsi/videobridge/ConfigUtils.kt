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
import java.time.Duration
import kotlin.reflect.KClass

val EMPTY_CONFIG: ConfigSource = MockConfigSource("empty config", mapOf())

class MapConfigSource private constructor(
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
            String::class -> getterHelper(::getString)
            else -> throw ConfigurationValueTypeUnsupportedException.new(valueType)
        }
    }

    override fun reload() { /* No op */ }
    override fun toStringMasked(): String = props.toString()

    private fun getInt(path: String): Int? = props[path] as? Int
    private fun getLong(path: String): Long? = props[path] as? Long
    private fun getDuration(path: String): Duration? = props[path] as? Duration
    private fun getString(path: String): String? = props[path] as? String

    @Suppress("UNCHECKED_CAST")
    private fun <U, T : Any> getterHelper(getter: (String) -> U): (String) -> T {
        return { path ->
            numGetsCalled++
            getter(path) as? T ?:
            throw ConfigPropertyNotFoundException("Could not find value for property at '$path'")
        }
    }
}


/**
 * A [ConfigSource] which delegates to an inner [ConfigSource] that can
 * be swapped out.
 */
class ConfigSourceWrapper(
    var innerConfig: ConfigSource? = null
) : ConfigSource {
    override val name: String
        get() = innerConfig!!.name

    override fun <T : Any> getterFor(valueType: KClass<T>): (String) -> T =
        innerConfig!!.getterFor(valueType)

    override fun reload() = innerConfig!!.reload()

    override fun toStringMasked(): String = innerConfig!!.toStringMasked()
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

    override fun reload() { /* No op */ }
    override fun toStringMasked(): String = props.toString()

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