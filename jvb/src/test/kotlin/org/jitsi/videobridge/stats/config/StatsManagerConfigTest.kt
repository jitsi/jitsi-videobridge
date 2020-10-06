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

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.inspectors.forOne
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.jitsi.ConfigTest
import org.jitsi.metaconfig.ConfigException
import java.time.Duration

internal class StatsManagerConfigTest : ConfigTest() {

    init {
        context("When only new config contains stats transport config") {
            context("a stats transport config") {
                context("with multiple, valid stats transports configured") {
                    withNewConfig(newConfigAllStatsTransports()) {
                        val cfg = StatsManagerConfig()

                        cfg.transportConfigs shouldHaveSize 2
                        cfg.transportConfigs.forOne {
                            it as StatsTransportConfig.MucStatsTransportConfig
                            it.interval shouldBe Duration.ofSeconds(5)
                        }
                        cfg.transportConfigs.forOne {
                            it as StatsTransportConfig.CallStatsIoStatsTransportConfig
                            it.interval shouldBe Duration.ofSeconds(5)
                        }
                    }
                }
                context("with an invalid stats transport configured") {
                    withNewConfig(newConfigInvalidStatsTransports()) {
                        should("ignore the invalid config and parse the valid transport correctly") {
                            val cfg = StatsManagerConfig()

                            cfg.transportConfigs shouldHaveSize 1
                            cfg.transportConfigs.forOne { it as StatsTransportConfig.MucStatsTransportConfig }
                        }
                    }
                }
                context("which has valid transports but stats are disabled") {
                    withNewConfig(newConfigInvalidStatsTransports(enabled = false)) {
                        should("throw when trying to access the stats transports") {
                            val cfg = StatsManagerConfig()
                            shouldThrow<ConfigException.UnableToRetrieve.ConditionNotMet> {
                                cfg.transportConfigs
                            }
                        }
                    }
                }
                context("which has a custom interval") {
                    withNewConfig(newConfigOneStatsTransportCustomInterval()) {
                        should("reflect the custom interval") {
                            val cfg = StatsManagerConfig()
                            cfg.transportConfigs.forOne {
                                it as StatsTransportConfig.MucStatsTransportConfig
                                it.interval shouldBe Duration.ofSeconds(10)
                            }
                        }
                    }
                }
            }
        }
        context("When old and new config contain stats transport configs") {
            withLegacyConfig(legacyConfigAllStatsTransports()) {
                withNewConfig(newConfigOneStatsTransport()) {
                    should("use the values from the old config") {
                        val cfg = StatsManagerConfig()

                        cfg.transportConfigs shouldHaveSize 2
                        cfg.transportConfigs.forOne { it as StatsTransportConfig.MucStatsTransportConfig }
                        cfg.transportConfigs.forOne { it as StatsTransportConfig.CallStatsIoStatsTransportConfig }
                    }
                }
            }
            context("and it's disabled in old config but enabled in new config") {
                withLegacyConfig(legacyConfigStatsEnabled(enabled = false)) {
                    withNewConfig(newConfigOneStatsTransport()) {
                        should("throw when trying to access the stats transports field") {
                            val cfg = StatsManagerConfig()
                            shouldThrow<ConfigException.UnableToRetrieve.ConditionNotMet> { cfg.transportConfigs }
                        }
                    }
                }
            }
        }
    }
}

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
