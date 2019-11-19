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

import com.typesafe.config.Config
import org.jitsi.utils.configk.ConfigSource
import org.jitsi.utils.configk.dsl.ConfigPropertyBuilder
import org.jitsi.utils.configk.dsl.MultiConfigPropertyBuilder
import org.jitsi.utils.configk.exception.ConfigurationValueTypeUnsupportedException
import java.time.Duration
import kotlin.reflect.KClass

class TypesafeConfigSource(
    override val name: String,
    private val config: Config
) : ConfigSource {
    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> getterFor(valueType: KClass<T>): (String) -> T {
        return when(valueType) {
            Boolean::class -> { path -> getBoolean(path) as T }
            Int::class -> { path -> getInt(path) as T }
            Long::class -> { path -> getLong(path) as T }
            Duration::class -> { path -> getDuration(path) as T }
            else -> throw ConfigurationValueTypeUnsupportedException.new(valueType)
        }
    }

    //TODO: translate typesafeconfig exceptions(?)

    private fun getInt(path: String): Int = config.getInt(path)
    private fun getLong(path: String): Long = config.getLong(path)
    private fun getDuration(path: String): Duration = config.getDuration(path)
    private fun getBoolean(path: String): Boolean = config.getBoolean(path)
}

fun <T : Any> MultiConfigPropertyBuilder<T>.legacyProperty(block: ConfigPropertyBuilder<T>.() -> Unit) {
    property {
        fromConfig(JvbConfigk.legacyConfig)
        block()
    }
}

fun <T : Any> MultiConfigPropertyBuilder<T>.newProperty(block: ConfigPropertyBuilder<T>.() -> Unit) {
    property {
        fromConfig(JvbConfigk.newConfig)
        block()
    }
}
