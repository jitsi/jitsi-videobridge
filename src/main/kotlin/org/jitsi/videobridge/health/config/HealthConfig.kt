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

import org.jitsi.config.JitsiConfig
import org.jitsi.config.legacyProperty
import org.jitsi.config.newProperty
import org.jitsi.config.simple
import org.jitsi.utils.config.ConfigProperty
import org.jitsi.utils.config.ConfigPropertyImpl
import org.jitsi.utils.config.ConfigResult
import org.jitsi.utils.config.configRunCatching
import org.jitsi.utils.config.dsl.ConfigPropertyBuilder
import org.jitsi.utils.config.dsl.MultiConfigPropertyBuilder
import org.jitsi.utils.config.dsl.multiProperty
import org.jitsi.utils.config.exception.NoAcceptablePropertyInstanceFoundException
import org.jitsi.utils.config.getOrThrow
import java.time.Duration
import kotlin.reflect.KClass


abstract class MultiProperty <T : Any>(protected val valueType: KClass<T>) : ConfigProperty<T> {
    private val innerProperties = mutableListOf<ConfigProperty<T>>()

    fun property(block: ConfigPropertyBuilder<T>.() -> Unit) {
        innerProperties.add(ConfigPropertyBuilder(valueType).apply(block).buildProp())
    }

    private fun findResultOrAggregateExceptions(configProperties: Iterable<ConfigProperty<T>>): ConfigResult<T> {
        val exceptions = mutableListOf<Throwable>()
        for (prop in configProperties) {
            //TODO: configRunCatching here is a bit redundant, as the inner props already have
            // a ConfigResult, they just don't expose it (they only expose the value).  This makes
            // sense for a normal property--but it's a bit wasteful for a multi-property, it was
            // just nice to re-use the code.  We could either have
            // multiconfigproperty not creater inner property types (and just create
            // attrs/readstrats and use those), or, we could expose ConfigResult
            // in ConfigProperty and access that instead of wrapping the access of
            // value in configRunCatching
            when (val result = configRunCatching { prop.value }) {
                is ConfigResult.PropertyNotFound -> exceptions.add(result.exception)
                is ConfigResult.PropertyFound -> return result
            }
        }
        return ConfigResult.notFound(NoAcceptablePropertyInstanceFoundException(exceptions))
    }

    override val value: T
        get() = findResultOrAggregateExceptions(innerProperties).getOrThrow()
}

fun <T : Any> MultiProperty<T>.legacyProperty(block: ConfigPropertyBuilder<T>.() -> Unit) {
    property {
        fromConfig(JitsiConfig.legacyConfig)
        block()
    }
}

fun <T : Any> MultiProperty<T>.newProperty(block: ConfigPropertyBuilder<T>.() -> Unit) {
    property {
        fromConfig(JitsiConfig.newConfig)
        block()
    }
}

open class SimpleProperty<T : Any>(
    valueType: KClass<T>,
    readOnce: Boolean,
    legacyName: String,
    newName: String
) : MultiProperty<T>(valueType) {
    init {
        legacyProperty {
            if (readOnce) readOnce() else readEveryTime()
            name(legacyName)
        }
        newProperty {
            if (readOnce) readOnce() else readEveryTime()
            name(newName)
        }
    }
}


class HealthConfig {
    companion object {
        // Option 1
        private val intervalPropCreator = {
            multiProperty<Long> {
                legacyProperty {
                    name("org.jitsi.videobridge.health.INTERVAL")
                    readOnce()
                }
                newProperty {
                    name("videobridge.health.interval")
                    readOnce()
                    retrievedAs<Duration>() convertedBy { it.toMillis() }
                }
            }
        }
        private val interval = intervalPropCreator()

        class HealthIntervalProperty : MultiProperty<Long>(Long::class) {
            init {
                legacyProperty {
                    name("org.jitsi.videobridge.health.INTERVAL")
                    readOnce()
                }
                newProperty {
                    name("videobridge.health.interval")
                    readOnce()
                    retrievedAs<Duration>() convertedBy { it.toMillis() }
                }
            }
        }
        // private val interval = HealthIntervalProperty()
        private val interval = multiProperty<Long> {
            legacyProperty {
                name("org.jitsi.videobridge.health.INTERVAL")
                readOnce()
            }
            newProperty {
                name("videobridge.health.interval")
                readOnce()
                retrievedAs<Duration>() convertedBy { it.toMillis() }
            }
        }

        @JvmStatic
        fun getInterval(): Long = interval.value

        private val timeout = multiProperty<Long> {
            legacyProperty {
                name("org.jitsi.videobridge.health.TIMEOUT")
                readOnce()
            }
            newProperty {
                name("videobridge.health.timeout")
                readOnce()
                retrievedAs<Duration>() convertedBy { it.toMillis() }
            }
        }

        @JvmStatic
        fun getTimeout(): Long = timeout.value

        class StickyFailuresProperty : SimpleProperty<Boolean>(
            Boolean::class,
            readOnce = true,
            legacyName = "org.jitsi.videobridge.health.STICKY_FAILURES",
            newName = "videobridge.health.sticky-failures"
        )

        private val stickyFailures = simple<Boolean>(
            readOnce = true,
            legacyName = "org.jitsi.videobridge.health.STICKY_FAILURES",
            newName = "videobridge.health.sticky-failures"
        )

        @JvmStatic
        fun stickyFailures() = stickyFailures.value
    }
}