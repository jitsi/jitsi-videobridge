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

import com.typesafe.config.ConfigFactory
import io.kotlintest.inspectors.forOne
import io.kotlintest.matchers.collections.shouldHaveSize
import io.kotlintest.seconds
import io.kotlintest.shouldBe
import io.kotlintest.shouldThrow
import org.jitsi.ConfigTest
import org.jitsi.config.AbstractReadOnlyConfigurationService
import org.jitsi.config.ConfigurationServiceConfigSource
import org.jitsi.config.TypesafeConfigSource
import org.jitsi.metaconfig.ConfigException
import org.jitsi.metaconfig.ConfigSource
import java.util.Properties
import kotlin.reflect.KType

internal class StatsManagerBundleActivatorConfigTest : ConfigTest() {

    init {
        "When only new config contains stats transport config" {
            "a stats transport config" {
                "with multiple, valid stats transports configured" {
                    withNewConfig(newConfigAllStatsTransports()) {
                        val cfg = StatsManagerBundleActivatorConfig()

                        cfg.transportConfigs shouldHaveSize 2
                        cfg.transportConfigs.forOne {
                            it as StatsTransportConfig.MucStatsTransportConfig
                            it.interval shouldBe 5.seconds
                        }
                        cfg.transportConfigs.forOne {
                            it as StatsTransportConfig.CallStatsIoStatsTransportConfig
                            it.interval shouldBe 5.seconds
                        }
                    }
                }
                "with an invalid stats transport configured" {
                    withNewConfig(newConfigInvalidStatsTransports()) {
                        should("ignore the invalid config and parse the valid transport correctly") {
                            val cfg = StatsManagerBundleActivatorConfig()

                            cfg.transportConfigs shouldHaveSize 1
                            cfg.transportConfigs.forOne { it as StatsTransportConfig.MucStatsTransportConfig }
                        }
                    }
                }
                "which has valid transports but stats are disabled" {
                    withNewConfig(newConfigInvalidStatsTransports(enabled = false)) {
                        should("throw when trying to access the stats transports") {
                            val cfg = StatsManagerBundleActivatorConfig()
                            shouldThrow<ConfigException.UnableToRetrieve.ConditionNotMet> {
                                cfg.transportConfigs
                            }
                        }
                    }
                }
                "which has a custom interval" {
                    withNewConfig(newConfigOneStatsTransportCustomInterval()) {
                        should("reflect the custom interval") {
                            val cfg = StatsManagerBundleActivatorConfig()
                            cfg.transportConfigs.forOne {
                                it as StatsTransportConfig.MucStatsTransportConfig
                                it.interval shouldBe 10.seconds
                            }
                        }
                    }
                }
            }
        }
        "When old and new config contain stats transport configs" {
            withLegacyConfig(legacyConfigAllStatsTransports()) {
                withNewConfig(newConfigOneStatsTransport()) {
                    should("use the values from the old config") {
                        val cfg = StatsManagerBundleActivatorConfig()

                        cfg.transportConfigs shouldHaveSize 2
                        cfg.transportConfigs.forOne { it as StatsTransportConfig.MucStatsTransportConfig }
                        cfg.transportConfigs.forOne { it as StatsTransportConfig.CallStatsIoStatsTransportConfig }
                    }
                }
            }
            "and it's disabled in old config but enabled in new config" {
                withLegacyConfig(legacyConfigStatsEnabled(enabled = false)) {
                    withNewConfig(newConfigOneStatsTransport()) {
                        should("throw when trying to access the stats transports field") {
                            val cfg = StatsManagerBundleActivatorConfig()
                            shouldThrow<ConfigException.UnableToRetrieve.ConditionNotMet> { cfg.transportConfigs }
                        }
                    }
                }
            }
        }
    }
}

private fun createConfigFrom(configString: String): ConfigSource =
    TypesafeConfigSource("testConfig", ConfigFactory.parseString(configString))

private fun createConfigFrom(configProps: Properties): ConfigSource =
    ConfigurationServiceConfigSource("legacyConfig", TestReadOnlyConfigurationService(configProps))

private fun newConfigAllStatsTransports(enabled: Boolean = true) = """
    videobridge {
        stats {
            interval=5 seconds
            enabled=$enabled
            transports = [
                {
                    type="muc"
                },
                {
                    type="callstatsio"
                },
            ]
        }
    }
    """.trimIndent()

private fun newConfigOneStatsTransport(enabled: Boolean = true) = """
    videobridge {
        stats {
            enabled=$enabled
            interval=5 seconds
            transports = [
                {
                    type="muc"
                }
            ]
        }
    }
    """.trimIndent()

private fun newConfigOneStatsTransportCustomInterval(enabled: Boolean = true) = """
    videobridge {
        stats {
            enabled=$enabled
            interval=5 seconds
            transports = [
                {
                    type="muc"
                    interval=10 seconds
                }
            ]
        }
    }
    """.trimIndent()

private fun newConfigInvalidStatsTransports(enabled: Boolean = true) = """
    videobridge {
        stats {
            interval=5 seconds
            enabled=$enabled
            transports = [
                {
                    type="invalid"
                },
                {
                    type="muc"
                },
            ]
        }
    }
    """.trimIndent()

private fun legacyConfigStatsEnabled(enabled: Boolean = true) = "org.jitsi.videobridge.ENABLE_STATISTICS=$enabled"

private fun legacyConfigAllStatsTransports(enabled: Boolean = true) = """
    org.jitsi.videobridge.ENABLE_STATISTICS=$enabled
    org.jitsi.videobridge.STATISTICS_TRANSPORT=muc,callstats.io
""".trimIndent()

// TODO(brian): ideally move to jicoco-test-kotlin. See note below.
private class ConfigSourceWrapper(
    var innerConfigSource: ConfigSource
) : ConfigSource {
    override val name: String
        get() = innerConfigSource.name

    override fun getterFor(type: KType): (String) -> Any = innerConfigSource.getterFor(type)
}

// TODO(brian): ideally move to jicoco-test-kotlin, but it depends on jicoco (where
// AbstractReadOnlyConfigurationService is defined) which already depends on jicoco-test-kotlin.
// Once old config is removed, I think we can break the jicoco -> jicoco-test-kotlin dependency.
private class TestReadOnlyConfigurationService(
    override var properties: Properties
) : AbstractReadOnlyConfigurationService() {

    override fun reloadConfiguration() { }
}
