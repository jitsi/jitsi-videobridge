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
import org.jitsi.config.NewJitsiConfig
import org.jitsi.metaconfig.ConfigException
import org.jitsi.metaconfig.config
import org.jitsi.videobridge.stats.CallStatsIOTransport
import org.jitsi.videobridge.stats.MucStatsTransport
import org.jitsi.videobridge.stats.StatsTransport
import java.time.Duration

class NewStatsManagerBundleActivatorConfig {
    /**
     * Whether or not the stats are enabled
     */
    val enabled: Boolean by config {
        retrieve("org.jitsi.videobridge.ENABLE_STATISTICS".from(NewJitsiConfig.legacyConfig))
        retrieve("videobridge.stats.enabled".from(NewJitsiConfig.newConfig))
    }

    /**
     * The interval at which the stats are pushed
     */
    val interval: Duration by config {
        onlyIf("Stats are enabled", ::enabled) {
            retrieve("org.jitsi.videobridge.STATISTICS_INTERVAL"
                .from(NewJitsiConfig.legacyConfig)
                .asType<Long>()
                .andConvertBy(Duration::ofMillis)
            )
            retrieve("videobridge.stats.interval".from(NewJitsiConfig.newConfig))
        }
    }

    /**
     * The enabled stat transports
     *
     * Note that if 'org.jitsi.videobridge.STATISTICS_TRANSPORT' is present at all
     * in the legacy config, we won't search the new config (we don't support merging
     * stats transport configs from old and new config together).
    */
    val transportConfigs: List<NewStatsTransportConfig> by config {
        retrieve("org.jitsi.videobridge."
            .from(NewJitsiConfig.legacyConfig)
            .asType<Map<String, String>>()
            .andConvertBy { it.toStatsTransportConfig() }
        )
        retrieve("videobridge.stats"
            .from(NewJitsiConfig.newConfig)
            .asType<ConfigObject>()
            .andConvertBy { cfg ->
                val transports = cfg["transports"]
                    ?: throw ConfigException.UnableToRetrieve.NotFound("Could not find transports within stats")
                transports as ConfigList
                transports.map { it as ConfigObject }
                    .map { it.toConfig() }
                    .mapNotNull { it.toStatsTransportConfig() }
            }
        )
    }

    /**
     * From a map of properties pulled from the legacy config file, create a list of [NewStatsTransportConfig]
     */
    private fun Map<String, String>.toStatsTransportConfig(): List<NewStatsTransportConfig> {
        val transportTypes =
            this["org.jitsi.videobridge.STATISTICS_TRANSPORT"]?.split(",") ?: return listOf()
        return transportTypes.mapNotNull { transportType ->
            val interval = this["org.jitsi.videobridge.STATISTICS_INTERVAL.$transportType"]?.let {
                Duration.ofMillis(it.toLong())
            } ?: this@NewStatsManagerBundleActivatorConfig.interval
            when (transportType) {
                "muc" -> NewStatsTransportConfig.MucStatsTransportConfig(interval)
                "callstats.io" -> NewStatsTransportConfig.CallStatsIoStatsTransportConfig(interval)
                else -> null
            }
        }
    }

    private fun Config.toStatsTransportConfig(): NewStatsTransportConfig? {
        val interval = if (hasPath("interval")) {
            getDuration("interval")
        } else {
            this@NewStatsManagerBundleActivatorConfig.interval
        }
        return when (getString("type")) {
            "muc" -> NewStatsTransportConfig.MucStatsTransportConfig(interval)
            "callstatsio" -> NewStatsTransportConfig.CallStatsIoStatsTransportConfig(interval)
            else -> null
        }
    }
}

/**
 * Helper classes which model the config parameters for each stats transport
 */
sealed class NewStatsTransportConfig(
    val interval: Duration
) {
    abstract fun toStatsTransport(): StatsTransport?

    class MucStatsTransportConfig(interval: Duration) : NewStatsTransportConfig(interval) {
        override fun toStatsTransport(): StatsTransport = MucStatsTransport()
    }

    class CallStatsIoStatsTransportConfig(interval: Duration) : NewStatsTransportConfig(interval) {
        override fun toStatsTransport(): StatsTransport = CallStatsIOTransport()
    }
}
