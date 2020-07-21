package org.jitsi.videobridge.octo.config

import io.kotlintest.IsolationMode
import io.kotlintest.shouldBe
import org.jitsi.ConfigTest

internal class OctoConfigTest : ConfigTest() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    init {
        "enabled" {
            "when bind address and bind port are defined in legacy config" {
                newConfig["videobridge.octo.enabled"] = false
                legacyConfig["org.jitsi.videobridge.octo.BIND_ADDRESS"] = "127.0.0.1"
                legacyConfig["org.jitsi.videobridge.octo.BIND_PORT"] = 8080
                should("be true") {
                    OctoConfig().enabled shouldBe true
                }
            }
            "when bind address is set in legacy config but not bind port" {
                legacyConfig["org.jitsi.videobridge.octo.BIND_ADDRESS"] = "127.0.0.1"
                should("be false") {
                    OctoConfig().enabled shouldBe false
                }
                "and set as true in new config" {
                    newConfig["videobridge.octo.enabled"] = true
                    should("be false") {
                        OctoConfig().enabled shouldBe false
                    }
                }
            }
            "when bind port is set in legacy config but not bind address" {
                newConfig["videobridge.octo.enabled"] = true
                legacyConfig["org.jitsi.videobridge.octo.BIND_PORT"] = 8080
                should("be false") {
                    OctoConfig().enabled shouldBe false
                }
            }
            "when enabled is set to true in new config and bind address/bind port are not defined in old config" {
                newConfig["videobridge.octo.enabled"] = true
                should("be true") {
                    OctoConfig().enabled shouldBe true
                }
            }
        }
        "bindAddress" {
            "when the value isn't set in legacy config" {
                newConfig["videobridge.octo.bind-address"] = "127.0.0.1"
                should("be the value from new config") {
                    OctoConfig().bindAddress shouldBe "127.0.0.1"
                }
            }
        }
    }
}
