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

import com.typesafe.config.Config
import com.typesafe.config.ConfigList
import com.typesafe.config.ConfigObject
import org.jitsi.config.JitsiConfig
import org.jitsi.metaconfig.ConfigException
import org.jitsi.metaconfig.config
import org.jitsi.videobridge.stats.CallStatsIOTransport
import org.jitsi.videobridge.stats.MucStatsTransport
import org.jitsi.videobridge.stats.StatsTransport
import org.jitsi.videobridge.xmpp.ClientConnection
import java.time.Duration

class StatsManagerBundleActivatorConfig {
    /**
     * Whether or not the stats are enabled
     */
    val enabled: Boolean by config {
        "org.jitsi.videobridge.ENABLE_STATISTICS".from(JitsiConfig.legacyConfig)
        "videobridge.stats.enabled".from(JitsiConfig.newConfig)
    }

    /**
     * The interval at which the stats are pushed
     */
    val interval: Duration by config {
        onlyIf("Stats are enabled", ::enabled) {
            "org.jitsi.videobridge.STATISTICS_INTERVAL"
                .from(JitsiConfig.legacyConfig)
                .convertFrom<Long>(Duration::ofMillis)
            "videobridge.stats.interval".from(JitsiConfig.newConfig)
        }
    }

    /**
     * The enabled stat transports
     *
     * Note that if 'org.jitsi.videobridge.STATISTICS_TRANSPORT' is present at all
     * in the legacy config, we won't search the new config (we don't support merging
     * stats transport configs from old and new config together).
    */
    val transportConfigs: List<StatsTransportConfig> by config {
        onlyIf("Stats transports are enabled", ::enabled) {
            "org.jitsi.videobridge."
                .from(JitsiConfig.legacyConfig)
                .convertFrom<Map<String, String>> {
                    if ("org.jitsi.videobridge.STATISTICS_TRANSPORT" in it) {
                        it.toStatsTransportConfig()
                    } else {
                        throw ConfigException.UnableToRetrieve.NotFound("not found in legacy config")
                    }
                }
            "videobridge.stats"
                .from(JitsiConfig.newConfig)
                .convertFrom<ConfigObject> { cfg ->
                    val transports = cfg["transports"]
                        ?: throw ConfigException.UnableToRetrieve.NotFound("Could not find transports within stats")
                    transports as ConfigList
                    transports.map { it as ConfigObject }
                            .map { it.toConfig() }
                            .mapNotNull { it.toStatsTransportConfig() }
                }
        }
    }

    /**
     * From a map of properties pulled from the legacy config file, create a list of [StatsTransportConfig]
     */
    private fun Map<String, String>.toStatsTransportConfig(): List<StatsTransportConfig> {
        val transportTypes =
            this["org.jitsi.videobridge.STATISTICS_TRANSPORT"]?.split(",") ?: return listOf()
        return transportTypes.mapNotNull { transportType ->
            val interval = this["org.jitsi.videobridge.STATISTICS_INTERVAL.$transportType"]?.let {
                Duration.ofMillis(it.toLong())
            } ?: this@StatsManagerBundleActivatorConfig.interval
            when (transportType) {
                "muc" -> StatsTransportConfig.MucStatsTransportConfig(interval)
                "callstats.io" -> StatsTransportConfig.CallStatsIoStatsTransportConfig(interval)
                else -> null
            }
        }
    }

    private fun Config.toStatsTransportConfig(): StatsTransportConfig? {
        val interval = if (hasPath("interval")) {
            getDuration("interval")
        } else {
            this@StatsManagerBundleActivatorConfig.interval
        }
        return when (getString("type")) {
            "muc" -> StatsTransportConfig.MucStatsTransportConfig(interval)
            "callstatsio" -> StatsTransportConfig.CallStatsIoStatsTransportConfig(interval)
            else -> null
        }
    }
}

/**
 * Helper classes which model the config parameters for each stats transport
 */
sealed class StatsTransportConfig(
    val interval: Duration
) {
    class MucStatsTransportConfig(interval: Duration) : StatsTransportConfig(interval) {
        fun toStatsTransport(clientConnection: ClientConnection): StatsTransport = MucStatsTransport(clientConnection)
    }

    class CallStatsIoStatsTransportConfig(interval: Duration) : StatsTransportConfig(interval) {
        fun toStatsTransport(): StatsTransport = CallStatsIOTransport()
    }
}
