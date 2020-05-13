package org.jitsi.videobridge.ice

import org.junit.Assert.*
import org.junit.*

import io.kotlintest.shouldBe
import io.kotlintest.shouldNotBe
import com.typesafe.config.ConfigFactory

import org.jitsi.videobridge.JitsiConfigTest
import org.jitsi.videobridge.testutils.resetSingleton
import org.jitsi.config.TypesafeConfigSource
import org.jitsi.utils.config.ConfigSource

import org.jitsi.videobridge.ice.IceConfig
import org.jitsi.config.JitsiConfig

import java.util.Properties

class HarvestersTest : JitsiConfigTest() {
    private fun resetProperties() {
        resetSingleton("tcpEnabledProp", IceConfig.Config.Companion)
        resetSingleton("tcpPortProperty", IceConfig.Config.Companion)
        resetSingleton("tcpMappedPortProperty", IceConfig.Config.Companion)
    }

    fun getTestConfig(tcpEnabled: Boolean, tcpPort: String, tcpMappedPort: String): ConfigSource {
        return TypesafeConfigSource("testConfig") {
            ConfigFactory.parseString("""
                videobridge {
                    ice {
                        tcp {
                            enabled=$tcpEnabled
                            port="$tcpPort"
                            mapped-port="$tcpMappedPort"
                        }
                    }
                }
                """.trimIndent()
            )
        }
    }

    fun getLegacyTestConfig(tcpEnabled: Boolean, tcpPort: String, tcpMappedPort: String): ConfigSource {
        return TypesafeConfigSource("testConfig") {
            ConfigFactory.parseProperties(
                Properties().apply {
                    val disabled = !tcpEnabled
                    setProperty("org.jitsi.videobridge.DISABLE_TCP_HARVESTER", "$disabled")
                    setProperty("org.jitsi.videobridge.TCP_HARVESTER_PORT", "$tcpPort")
                    setProperty("org.jitsi.videobridge.TCP_HARVESTER_MAPPED_PORT", "$tcpMappedPort")
                }
            )
        }
    }

    @Test
    fun initializeStaticConfigurationWithoutTcpEnabled() {
        withNewConfig(getTestConfig(false, "", ""))
        withLegacyConfig(getLegacyTestConfig(false, "", ""))
        resetProperties()
        Harvesters.initializeStaticConfiguration()
        Harvesters.isHealthy() shouldBe true
        Harvesters.tcpHarvester shouldBe null
        Harvesters.singlePortHarvesters shouldNotBe null
        (Harvesters.singlePortHarvesters.size > 0) shouldBe true
    }

    @Test
    fun initializeStaticConfigurationWithTcpEnabled() {
        withNewConfig(getTestConfig(true, "33333", ""))
        withLegacyConfig(getLegacyTestConfig(true, "33333", ""))
        resetProperties()
        Harvesters.initializeStaticConfiguration()
        Harvesters.isHealthy() shouldBe true
        Harvesters.tcpHarvester shouldNotBe null
        Harvesters.singlePortHarvesters shouldNotBe null
        (Harvesters.singlePortHarvesters.size > 0) shouldBe true
    }

    @Test
    fun initializeStaticConfigurationWithTcpMapedPort() {
        withNewConfig(getTestConfig(true, "33333", "3334"))
        withLegacyConfig(getLegacyTestConfig(true, "33333", "3334"))
        resetProperties()
        Harvesters.initializeStaticConfiguration()
        Harvesters.isHealthy() shouldBe true
        Harvesters.tcpHarvester shouldNotBe null
        Harvesters.singlePortHarvesters shouldNotBe null
        (Harvesters.singlePortHarvesters.size > 0) shouldBe true
    }

    @Test
    fun closeStaticConfiguration() {
        withNewConfig(getTestConfig(true, "33333", ""))
        withLegacyConfig(getLegacyTestConfig(true, "33333", ""))
        resetProperties()
        Harvesters.initializeStaticConfiguration()
        Harvesters.closeStaticConfiguration()
        Harvesters.tcpHarvester shouldBe null
        Harvesters.singlePortHarvesters shouldBe null
    }
}
