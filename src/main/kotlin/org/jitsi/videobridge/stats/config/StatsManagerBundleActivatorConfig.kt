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

package org.jitsi.videobridge.stats.config

import com.typesafe.config.ConfigList
import com.typesafe.config.ConfigObject
import org.jitsi.config.LegacyFallbackConfigProperty
import org.jitsi.config.legacyConfigAttributes
import org.jitsi.config.newConfigAttributes
import org.jitsi.utils.config.FallbackProperty
import org.jitsi.utils.config.SimpleProperty
import org.jitsi.utils.config.exception.ConfigPropertyNotFoundException
import org.jitsi.videobridge.config.ConditionalProperty
import org.jitsi.videobridge.config.ResettableSingleton
import org.jitsi.videobridge.stats.CallStatsIOTransport
import org.jitsi.videobridge.stats.MucStatsTransport
import org.jitsi.videobridge.stats.StatsTransport
import java.time.Duration

class StatsManagerBundleActivatorConfig {
    class Config {
        companion object {
            /**
             * Whether or not the stats are enabled
             */
            class EnabledProperty : LegacyFallbackConfigProperty<Boolean>(
                Boolean::class,
                "org.jitsi.videobridge.ENABLE_STATISTICS",
                "videobridge.stats.enabled",
                readOnce = true
            )

            private val enabledProp = ResettableSingleton { EnabledProperty() }

            @JvmStatic
            fun enabled() = enabledProp.get().value

            /**
             * The interval at which the stats are pushed
             */
            class StatsIntervalProperty : ConditionalProperty<Duration>(
                ::enabled,
                StatsInterval(),
                "Stats interval property is only parsed when stats are enabled"
            )

            private val statsIntervalProp = ResettableSingleton { StatsIntervalProperty() }

            @JvmStatic
            fun statsInterval(): Duration = statsIntervalProp.get().value

            private class StatsInterval : FallbackProperty<Duration>(
                legacyConfigAttributes {
                    name("org.jitsi.videobridge.STATISTICS_INTERVAL")
                    readOnce()
                    retrievedAs<Long>() convertedBy { Duration.ofMillis(it) }
                },
                newConfigAttributes {
                    name("videobridge.stats.interval")
                    readOnce()
                }
            )

            /**
             * The enabled stat transports
             */
            class StatsTransportsProperty : ConditionalProperty<List<StatsTransportConfig>>(
                ::enabled,
                StatsTransports(),
                "Stats transports property is only parsed when stats are enabled"
            )

            private val statsTransportsProp = StatsTransportsProperty()

            @JvmStatic
            fun transportConfigs() = statsTransportsProp.value

            /**
             * Note that if 'org.jitsi.videobridge.STATISTICS_TRANSPORT' is present at all
             * in the legacy config, we won't search the new config (we don't support merging
             * stats transport configs from old and new config together).
             */
            // TODO: currently we silently swallow all errors.  Can we propagate up in a useful way?  If
            //  we throw then I think things will be 'catastrophic' (prevent accessing this config at all), so
            //  not sure we want that.  Other option would be to create a logger here?
            private class StatsTransports : FallbackProperty<List<StatsTransportConfig>>(
                // NOTE: Do NOT mark *these* legacy attributes as deprecated.  When we
                //  want to mark the old stats transport config as deprecated, use the
                //  classes defined below.
                legacyConfigAttributes {
                    name("org.jitsi.videobridge")
                    readOnce()
                    retrievedAs<ConfigObject>() convertedBy { cfg ->
                        // Note: if the 'STATISTICS_TRANSPORT' property isn't found [fromLegacyConfig]
                        // will throw an that will bubble up to here so we fall through to the
                        // new config
                        StatsTransportConfig.fromLegacyConfig(cfg.toConfig())
                    }
                },
                // Note: the implementation of this can be simplified when we get support for
                // List types (we'll be able to parse 'videobridge.stats.transports' directly
                // as a ConfigObjectList)
                newConfigAttributes {
                    name("videobridge.stats")
                    readOnce()
                    retrievedAs<ConfigObject>() convertedBy { cfg ->
                        val transports = cfg["transports"] ?: throw ConfigPropertyNotFoundException("Could not find transports within stats")
                        transports as ConfigList
                        transports.map { it as ConfigObject }
                            .map { it.toConfig() }
                            .mapNotNull { StatsTransportConfig.fromNewConfig(it) }
                    }
                }
            )

            /**
             * Note: These three classes exist only for the purposes of validation
             * and future deprecation.  IN the legacy config file, the properties
             * for stats transports are spread across these 3 values, making it
             * difficult to encompass in a single property.  We could read the property
             * at the level of 'org.jitsi.videobridge", but that has the following
             * issues:
             *
             * 1) It won't register correctly in validation that these specific properties
             * are read
             * 2) When we want to mark them as deprecated, we'd be marking the entire chunk
             * as deprecated instead of the specific properties
             *
             * Because of that, we define these here to handle the above use cases, but
             * implement the property that's used differently (see above)
             */
            @Suppress("unused")
            private class LegacyStatsTransportsProperty : SimpleProperty<String>(
                legacyConfigAttributes {
                    name("org.jitsi.videobridge.STATISTICS_TRANSPORT")
                    readOnce()
                }
            )
        }
    }
}

/**
 * Helper classes which model the config parameters for each stats transport
 */
sealed class StatsTransportConfig(
    val interval: Duration
) {
    abstract fun toStatsTransport(): StatsTransport?

    class MucStatsTransportConfig(interval: Duration) : StatsTransportConfig(interval) {
        override fun toStatsTransport(): StatsTransport = MucStatsTransport()
    }
    class CallStatsIoStatsTransportConfig(interval: Duration) : StatsTransportConfig(interval) {
        override fun toStatsTransport(): StatsTransport = CallStatsIOTransport()
    }
    companion object {
        /**
         * [config] will represent an object within the "videobridge.stats.transports" list
         */
        fun fromNewConfig(config: com.typesafe.config.Config): StatsTransportConfig? {
            val interval = if (config.hasPath("interval")) {
                config.getDuration("interval")
            } else {
                StatsManagerBundleActivatorConfig.Config.statsInterval()
            }
            return when (config.getString("type")) {
                "muc" -> MucStatsTransportConfig(interval)
                "callstatsio" -> CallStatsIoStatsTransportConfig(interval)
                else -> null
            }
        }

        /**
         * [config] will represent the "org.jitsi.videobridge" object
         */
        fun fromLegacyConfig(config: com.typesafe.config.Config): List<StatsTransportConfig> {
            val transportStrings = config.getString("STATISTICS_TRANSPORT").split(",")
            return transportStrings.mapNotNull {
                val interval = if (config.hasPath("STATISTICS_INTERVAL.$it")) {
                    Duration.ofMillis(config.getLong("STATISTICS_INTERVAL.$it"))
                } else {
                    StatsManagerBundleActivatorConfig.Config.statsInterval()
                }
                when (it) {
                    "muc" -> MucStatsTransportConfig(interval)
                    "callstats.io" -> CallStatsIoStatsTransportConfig(interval)
                    else -> null
                }
            }
        }
    }
}
