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
import io.kotlintest.TestCase
import io.kotlintest.inspectors.forOne
import io.kotlintest.matchers.collections.shouldHaveSize
import io.kotlintest.seconds
import io.kotlintest.shouldBe
import io.kotlintest.shouldThrow
import org.jitsi.config.TypesafeConfigSource
import org.jitsi.utils.config.ConfigSource
import org.jitsi.videobridge.JitsiConfigTest
import org.jitsi.videobridge.config.ConditionalPropertyConditionNotMetException
import org.jitsi.videobridge.testutils.resetSingleton
import org.jxmpp.jid.impl.JidCreate
import java.util.Properties
import org.jitsi.videobridge.stats.config.StatsManagerBundleActivatorConfig.Config.Companion as Config

class StatsManagerBundleActivatorConfigTest : JitsiConfigTest() {

    override fun beforeTest(testCase: TestCase) {
        resetSingletons()
    }

    init {
        "When only new config contains stats transport config" {
            withLegacyConfig(legacyConfigNoStatsTransports)
            "A stats transport config" {
                "with a multiple, valid stats transport configured" {
                    withNewConfig(newConfigAllStatsTransports())
                    should("parse the transport correctly") {
                        val cfg = Config.StatsTransportsProperty()

                        cfg.value shouldHaveSize 4
                        cfg.value.forOne {
                            it as StatsTransportConfig.ColibriStatsTransportConfig
                            it.interval shouldBe 5.seconds
                        }
                        cfg.value.forOne {
                            it as StatsTransportConfig.MucStatsTransportConfig
                            it.interval shouldBe 5.seconds
                        }
                        cfg.value.forOne {
                            it as StatsTransportConfig.CallStatsIoStatsTransportConfig
                            it.interval shouldBe 5.seconds
                        }
                        cfg.value.forOne {
                            it as StatsTransportConfig.PubSubStatsTransportConfig
                            it.service shouldBe JidCreate.from("meet.jit.si")
                            it.node shouldBe "jvb"
                            it.interval shouldBe 5.seconds
                        }
                    }
                }
                "with an invalid stats transport configured" {
                    withNewConfig(newConfigInvalidStatsTransports())
                    should("ignore the invalid config and parse the valid transport correctly") {
                        val cfg = Config.StatsTransportsProperty()

                        cfg.value shouldHaveSize 1
                        cfg.value.forOne { it as StatsTransportConfig.MucStatsTransportConfig }
                    }
                }
                "which have valid transports but stats are disabled" {
                    withNewConfig(newConfigAllStatsTransports(enabled = false))
                    should("throw when trying to access the stats transports") {
                        val cfg = Config.StatsTransportsProperty()
                        shouldThrow<ConditionalPropertyConditionNotMetException> { cfg.value }
                    }
                }
                "which has a custom interval" {
                    withNewConfig(newConfigOneStatsTransportCustomInterval())
                    should("reflect the custom interval") {
                        val cfg = Config.StatsTransportsProperty()
                        cfg.value.forOne {
                            it as StatsTransportConfig.ColibriStatsTransportConfig
                            it.interval shouldBe 10.seconds
                        }
                    }
                }
            }
        }
        "When old and new config contain stats transport config" {
            withLegacyConfig(legacyConfigAllStatsTransports())
            withNewConfig(newConfigOneStatsTransport())
            should("use the values from the old config") {
                val cfg = Config.StatsTransportsProperty()

                cfg.value shouldHaveSize 4
                cfg.value.forOne { it as StatsTransportConfig.ColibriStatsTransportConfig }
                cfg.value.forOne { it as StatsTransportConfig.MucStatsTransportConfig }
                cfg.value.forOne { it as StatsTransportConfig.CallStatsIoStatsTransportConfig }
                cfg.value.forOne {
                    it as StatsTransportConfig.PubSubStatsTransportConfig
                    it.service shouldBe JidCreate.from("meet.jit.si")
                    it.node shouldBe "jvb"
                }
            }
            "and it's disabled in old config but enabled in new config" {
                withLegacyConfig(legacyConfigStatsEnabled(enabled = false))
                withNewConfig(newConfigOneStatsTransport())
                should("throw when trying to access the stats transports") {
                    val cfg = Config.StatsTransportsProperty()
                    shouldThrow<ConditionalPropertyConditionNotMetException> { cfg.value }
                }
            }
        }
    }

    private fun createConfigFrom(configString: String): ConfigSource =
        TypesafeConfigSource("testConfig") { ConfigFactory.parseString(configString) }

    private fun createConfigFrom(configProps: Properties): ConfigSource =
        TypesafeConfigSource("testConfig") { ConfigFactory.parseProperties(configProps) }

    private fun resetSingletons() {
        resetSingleton(
            "enabledProp",
            Config
        )
        resetSingleton(
            "statsIntervalProp",
            Config
        )
    }

    private fun newConfigAllStatsTransports(enabled: Boolean = true) = createConfigFrom("""
        videobridge {
            stats {
                interval=5 seconds
                enabled=$enabled
                transports = [
                    {
                        type="colibri"
                    },
                    {
                        type="muc"
                    },
                    {
                        type="callstatsio"
                    },
                    {
                        type="pubsub"
                        service="meet.jit.si"
                        node="jvb"
                    }
                ]
            }
        }
        """.trimIndent()
    )
    private fun newConfigOneStatsTransport(enabled: Boolean = true) = createConfigFrom("""
        videobridge {
            stats {
                enabled=$enabled
                interval=5 seconds
                transports = [
                    {
                        type="colibri"
                    }
                ]
            }
        }
        """.trimIndent()
    )
    private fun newConfigOneStatsTransportCustomInterval(enabled: Boolean = true) = createConfigFrom("""
        videobridge {
            stats {
                enabled=$enabled
                interval=5 seconds
                transports = [
                    {
                        type="colibri"
                        interval=10 seconds
                    }
                ]
            }
        }
        """.trimIndent()
    )
    private fun newConfigInvalidStatsTransports(enabled: Boolean = true) = createConfigFrom("""
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
    )
    private val legacyConfigNoStatsTransports = createConfigFrom(Properties().apply {
        setProperty("org.jitsi.videobridge.some_other_prop=", "42")
    })

    private fun legacyConfigStatsEnabled(enabled: Boolean = true) = createConfigFrom(Properties().apply {
        setProperty("org.jitsi.videobridge.ENABLE_STATISTICS", "$enabled")
    })

    private fun legacyConfigAllStatsTransports(enabled: Boolean = true) = createConfigFrom(Properties().apply {
        setProperty("org.jitsi.videobridge.ENABLE_STATISTICS", "$enabled")
        setProperty("org.jitsi.videobridge.STATISTICS_TRANSPORT", "muc,colibri,callstats.io,pubsub")
        setProperty("org.jitsi.videobridge.PUBSUB_SERVICE", "meet.jit.si")
        setProperty("org.jitsi.videobridge.PUBSUB_NODE", "jvb")
    })
}
