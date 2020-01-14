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
import org.jitsi.videobridge.stats.ColibriStatsTransport
import org.jitsi.videobridge.stats.MucStatsTransport
import org.jitsi.videobridge.stats.PubSubStatsTransport
import org.jitsi.videobridge.stats.StatsTransport
import org.jxmpp.jid.Jid
import org.jxmpp.jid.impl.JidCreate

class StatsManagerBundleActivatorConfig {
    class Config {
        companion object {
            class EnabledProperty : LegacyFallbackConfigProperty<Boolean>(
                Boolean::class,
                "org.jitsi.videobridge.ENABLE_STATISTICS",
                "videobridge.stats.enabled",
                readOnce = true
            )

            private val enabledProp = ResettableSingleton { EnabledProperty() }

            @JvmStatic
            fun enabled() = enabledProp.get().value

            class StatsTransportsProperty : ConditionalProperty<List<StatsTransportConfig>>(
                ::enabled,
                StatsTransports(),
                "Stats transports property is only parsed when stats are enabled"
            )

            private val statsTransportsProp = StatsTransportsProperty()

            @JvmStatic
            fun transports() = statsTransportsProp.value

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
            private class PubSubStatsServiceProperty : SimpleProperty<String>(
                legacyConfigAttributes {
                    name("org.jitsi.videobridge.PUBSUB_SERVICE")
                    readOnce()
                }
            )

            @Suppress("unused")
            private class PubSubStatsNodeProperty : SimpleProperty<String>(
                legacyConfigAttributes {
                    name("org.jitsi.videobridge.PUBSUB_NODE")
                    readOnce()
                }
            )

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

sealed class StatsTransportConfig {
    abstract fun toStatsTransport(): StatsTransport?

    object ColibriStatsTransportConfig : StatsTransportConfig() {
        override fun toStatsTransport(): StatsTransport = ColibriStatsTransport()
    }
    object MucStatsTransportConfig : StatsTransportConfig() {
        override fun toStatsTransport(): StatsTransport = MucStatsTransport()
    }
    object CallStatsIoStatsTransportConfig : StatsTransportConfig() {
        override fun toStatsTransport(): StatsTransport = CallStatsIOTransport()
    }
    class PubSubStatsTransportConfig(
        val service: Jid,
        val node: String
    ) : StatsTransportConfig() {
        override fun toStatsTransport(): StatsTransport = PubSubStatsTransport(service, node)

        companion object {
            operator fun invoke(serviceName: String, nodeName: String): PubSubStatsTransportConfig? {
                return try {
                    val service = JidCreate.from(serviceName)
                    PubSubStatsTransportConfig(service, nodeName)
                } catch (e: Exception) {
                    null
                }
            }
        }
    }
    companion object {
        /**
         * [config] will represent an object within the "videobridge.stats.transports" list
         */
        fun fromNewConfig(config: com.typesafe.config.Config): StatsTransportConfig? {
            return when (config.getString("type")) {
                "colibri" -> ColibriStatsTransportConfig
                "muc" -> MucStatsTransportConfig
                "callstatsio" -> CallStatsIoStatsTransportConfig
                "pubsub" -> {
                    val serviceString = config.getString("service")
                    val nodeString = config.getString("node")
                    PubSubStatsTransportConfig(JidCreate.from(serviceString), nodeString)
                }
                else -> null
            }
        }

        /**
         * [config] will represent the "org.jitsi.videobridge" object
         */
        fun fromLegacyConfig(config: com.typesafe.config.Config): List<StatsTransportConfig> {
            val transportStrings = config.getString("STATISTICS_TRANSPORT").split(",")
            return transportStrings.mapNotNull {
                when (it) {
                    "colibri" -> ColibriStatsTransportConfig
                    "muc" -> MucStatsTransportConfig
                    "callstats.io" -> CallStatsIoStatsTransportConfig
                    "pubsub" -> {
                        // In keeping consistent to swallow all errors, we don't fail here
                        // if we can't find the pubsub service or not properties.
                        try {
                            val serviceString = config.getString("PUBSUB_SERVICE")
                            val nodeString = config.getString("PUBSUB_NODE")
                            PubSubStatsTransportConfig(JidCreate.from(serviceString), nodeString)
                        } catch (t: Throwable) {
                            null
                        }
                    }
                    else -> null
                }
            }
        }
    }
}
