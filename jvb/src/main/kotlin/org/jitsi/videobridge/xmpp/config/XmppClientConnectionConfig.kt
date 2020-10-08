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

package org.jitsi.videobridge.xmpp.config

import com.typesafe.config.ConfigObject
import com.typesafe.config.ConfigValue
import org.jitsi.config.JitsiConfig
import org.jitsi.metaconfig.ConfigException
import org.jitsi.metaconfig.config
import org.jitsi.videobridge.stats.StatsCollector
import org.jitsi.videobridge.stats.config.StatsTransportConfig
import org.jitsi.xmpp.mucclient.MucClientConfiguration
import java.time.Duration

class XmppClientConnectionConfig {
    val clientConfigs: List<MucClientConfiguration> by config {
        "org.jitsi.videobridge.xmpp.user."
            .from(JitsiConfig.legacyConfig)
            .convertFrom<Map<String, String>> { propsMap ->
                MucClientConfiguration.loadFromMap(propsMap, "org.jitsi.videobridge.xmpp.user.", true)
                    .toList()
                    .apply { forEach { it.applyDefaultIqHandlerMode() } }
                    .takeIf { it.isNotEmpty() } ?: throw ConfigException.UnableToRetrieve.NotFound("no configs found")
            }
        "videobridge.apis.xmpp-client.configs".from(JitsiConfig.newConfig)
            .convertFrom<ConfigObject> { cfg ->
                cfg.entries
                    .map { it.toMucClientConfiguration() }
                    .filter { it.isComplete }
            }
    }

    private val presenceIntervalProperty: Duration by config {
        "videobridge.apis.xmpp-client.presence-interval".from(JitsiConfig.newConfig)
    }

    /**
     * The interval at which presence updates (with updates stats/status) are published. Allow to be overriden by
     * legacy-style "stats-transports" config.
     */
    val presenceInterval: Duration = StatsCollector.config.transportConfigs.stream()
        .filter { tc -> tc is StatsTransportConfig.CallStatsIoStatsTransportConfig }
        .map(StatsTransportConfig::interval)
        .findFirst()
        .orElse(presenceIntervalProperty)
}

/**
 * We want the bridge to default to using "sync" as the IQ handler mode (unless the config actually overrides it).
 */
private fun MucClientConfiguration.applyDefaultIqHandlerMode() {
    if (this.iqHandlerMode == null) {
        this.iqHandlerMode = "sync"
    }
}

private fun MutableMap.MutableEntry<String, ConfigValue>.toMucClientConfiguration(): MucClientConfiguration {
    val config = MucClientConfiguration(this.key)
    (value as? ConfigObject)?.let {
        it.forEach { (propName, propValue) ->
            config.setProperty(propName, propValue.unwrapped().toString())
        }
    } ?: run { throw Exception("Invalid muc client configuration. " +
            "Expected type ConfigObject but got ${value.unwrapped()::class.java}") }

    return config.also { it.applyDefaultIqHandlerMode() }
}
