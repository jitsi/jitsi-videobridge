package org.jitsi.videobridge.octo.config

import io.kotest.matchers.shouldBe
import org.jitsi.ConfigTest

internal class OctoConfigTest : ConfigTest() {
    init {
        context("enabled") {
            context("when bind address and bind port are defined in legacy config") {
                withLegacyConfig(legacyConfigWithBindAddressAndBindPort) {
                    withNewConfig(newConfigOctoDisabled) {
                        should("be true") {
                            OctoConfig().enabled shouldBe true
                        }
                    }
                }
            }
            context("when bind address is set in legacy config but not bind port") {
                withLegacyConfig(legacyConfigWithBindAddressNoBindPort) {
                    should("be false") {
                        OctoConfig().enabled shouldBe false
                    }
                    context("and set as true in new config") {
                        withNewConfig(newConfigOctoEnabled) {
                            should("be false") {
                                OctoConfig().enabled shouldBe false
                            }
                        }
                    }
                }
            }
            context("when bind port is set in legacy config but not bind address") {
                withLegacyConfig(legacyConfigWithBindPortNoBindAddress) {
                    withNewConfig(newConfigOctoEnabled) {
                        should("be false") {
                            OctoConfig().enabled shouldBe false
                        }
                    }
                }
            }
            context("when enabled is true in new config and bind address/bind port are not defined in old config") {
                withNewConfig(newConfigOctoEnabled) {
                    should("be true") {
                        OctoConfig().enabled shouldBe true
                    }
                }
            }
        }
        context("bindAddress") {
            context("when the value isn't set in legacy config") {
                withNewConfig(newConfigBindAddress) {
                    should("be the value from new config") {
                        OctoConfig().bindAddress shouldBe "127.0.0.1"
                    }
                }
            }
        }
    }
}

private val legacyConfigWithBindAddressAndBindPort = """
    org.jitsi.videobridge.octo.BIND_ADDRESS=127.0.0.1
    org.jitsi.videobridge.octo.BIND_PORT=8080
""".trimIndent()

private val legacyConfigWithBindAddressNoBindPort = """
    org.jitsi.videobridge.octo.BIND_ADDRESS=127.0.0.1
""".trimIndent()

private val legacyConfigWithBindPortNoBindAddress = """
    org.jitsi.videobridge.octo.BIND_PORT=8080
""".trimIndent()

private val newConfigOctoDisabled = """
    videobridge {
        octo {
            enabled=false
        }
    }
""".trimIndent()

private val newConfigOctoEnabled = """
    videobridge {
        octo {
            enabled=true
        }
    }
""".trimIndent()

private val newConfigBindAddress = """
    videobridge {
        octo {
            bind-address = "127.0.0.1"
        }
    }
""".trimIndent()
