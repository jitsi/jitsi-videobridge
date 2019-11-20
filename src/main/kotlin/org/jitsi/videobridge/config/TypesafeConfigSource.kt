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
import com.typesafe.config.ConfigFactory
import org.jitsi.utils.config.ConfigSource
import org.jitsi.utils.config.dsl.ConfigPropertyBuilder
import org.jitsi.utils.config.dsl.MultiConfigPropertyBuilder
import org.jitsi.utils.config.exception.ConfigurationValueTypeUnsupportedException
import org.jitsi.utils.logging2.LoggerImpl
import java.nio.file.InvalidPathException
import java.nio.file.Paths
import java.time.Duration
import kotlin.reflect.KClass

open class TypesafeConfigSource(
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

class NewConfig : TypesafeConfigSource("new config", ConfigFactory.load())
class LegacyConfig : TypesafeConfigSource("legacy config", loadLegacyConfig()) {
    companion object {
        private val logger = LoggerImpl(LegacyConfig::class.java.name)

        fun loadLegacyConfig(): Config {
            val oldConfigHomeDirLocation = System.getProperty("net.java.sip.communicator.SC_HOME_DIR_LOCATION")
            val oldConfigHomeDirName = System.getProperty("net.java.sip.communicator.SC_HOME_DIR_NAME")
            return try {
                val config = ConfigFactory.parseFile(
                    Paths.get(oldConfigHomeDirLocation, oldConfigHomeDirName, "sip-communicator.properties")
                            .toFile())
                logger.info("Found a legacy config file: \n" + config.root().render())
                config
            } catch (e: InvalidPathException) {
                logger.info("No legacy config file found")
                ConfigFactory.parseString("")
            } catch (e: NullPointerException) {
                logger.info("No legacy config file found")
                ConfigFactory.parseString("")
            }
        }
    }
}
