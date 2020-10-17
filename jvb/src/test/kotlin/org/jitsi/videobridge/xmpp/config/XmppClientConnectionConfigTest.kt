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

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.jitsi.ConfigTest

internal class XmppClientConnectionConfigTest : ConfigTest() {
    init {
        context("Connection configs") {
            context("when defined in the legacy config") {
                withLegacyConfig(legacyConfigSingleXmppConnection) {
                    should("parse things correctly") {
                        val configs = XmppClientConnectionConfig().clientConfigs
                        configs shouldHaveSize 1
                        with(configs[0]) {
                            this.id shouldBe "shard"
                            this.mucNickname shouldBe "test_nick"
                            this.disableCertificateVerification shouldBe true
                            this.domain shouldBe "auth.some.domain.net"
                            this.hostname shouldBe "localhost"
                            this.username shouldBe "jvb"
                            this.password shouldBe "s3cr3t"
                            this.mucJids shouldContainExactly listOf("JvbBrewery@internal.some.domain.net")
                            this.iqHandlerMode shouldBe "sync"
                        }
                    }
                }
            }
            context("when multiple are defined in legacy and one is incomplete") {
                withLegacyConfig(legacyConfigOneCompleteConnectionOneIncomplete) {
                    should("only parse the complete one") {
                        val configs = XmppClientConnectionConfig().clientConfigs
                        configs shouldHaveSize 1
                        with(configs[0]) {
                            this.id shouldBe "shard"
                            this.mucNickname shouldBe "test_nick"
                            this.disableCertificateVerification shouldBe true
                            this.domain shouldBe "auth.some.domain.net"
                            this.hostname shouldBe "localhost"
                            this.username shouldBe "jvb"
                            this.password shouldBe "s3cr3t"
                            this.mucJids shouldContainExactly listOf("JvbBrewery@internal.some.domain.net")
                            this.iqHandlerMode shouldBe "sync"
                        }
                    }
                }
            }
            context("when defined in new config") {
                withNewConfig(newConfigSingleXmppConnection, true) {
                    should("parse things correctly") {
                        val configs = XmppClientConnectionConfig().clientConfigs
                        configs shouldHaveSize 1
                        with(configs[0]) {
                            this.id shouldBe "shard"
                            this.mucNickname shouldBe "test_nick"
                            this.disableCertificateVerification shouldBe true
                            this.domain shouldBe "auth.some.domain.net"
                            this.hostname shouldBe "localhost"
                            this.username shouldBe "jvb"
                            this.password shouldBe "s3cr3t"
                            this.mucJids shouldContainExactly listOf("JvbBrewery@internal.some.domain.net")
                            this.iqHandlerMode shouldBe "sync"
                        }
                    }
                }
            }
            context("when defined in new config with some incomplete") {
                withNewConfig(newConfigOneCompleteConnectionOneIncomplete, true) {
                    should("parse things correctly") {
                        val configs = XmppClientConnectionConfig().clientConfigs
                        configs shouldHaveSize 1
                        with(configs[0]) {
                            this.id shouldBe "shard"
                            this.mucNickname shouldBe "test_nick"
                            this.disableCertificateVerification shouldBe true
                            this.domain shouldBe "auth.some.domain.net"
                            this.hostname shouldBe "localhost"
                            this.username shouldBe "jvb"
                            this.password shouldBe "s3cr3t"
                            this.mucJids shouldContainExactly listOf("JvbBrewery@internal.some.domain.net")
                            this.iqHandlerMode shouldBe "sync"
                        }
                    }
                }
            }
        }
    }
}

private val legacyConfigSingleXmppConnection = """
    org.jitsi.videobridge.xmpp.user.shard.DOMAIN=auth.some.domain.net
    org.jitsi.videobridge.xmpp.user.shard.MUC_JIDS=JvbBrewery@internal.some.domain.net
    org.jitsi.videobridge.xmpp.user.shard.MUC=JvbBrewery@internal.some.domain.net
    org.jitsi.videobridge.xmpp.user.shard.DISABLE_CERTIFICATE_VERIFICATION=true
    org.jitsi.videobridge.xmpp.user.shard.HOSTNAME=localhost
    org.jitsi.videobridge.xmpp.user.shard.USERNAME=jvb
    org.jitsi.videobridge.xmpp.user.shard.PASSWORD=s3cr3t
    org.jitsi.videobridge.xmpp.user.shard.MUC_NICKNAME=test_nick
    org.jitsi.videobridge.xmpp.user.shard.IQ_HANDLER_MODE=sync
""".trimIndent()

private val legacyConfigOneCompleteConnectionOneIncomplete = """
    org.jitsi.videobridge.xmpp.user.shard.DOMAIN=auth.some.domain.net
    org.jitsi.videobridge.xmpp.user.shard.MUC_JIDS=JvbBrewery@internal.some.domain.net
    org.jitsi.videobridge.xmpp.user.shard.MUC=JvbBrewery@internal.some.domain.net
    org.jitsi.videobridge.xmpp.user.shard.DISABLE_CERTIFICATE_VERIFICATION=true
    org.jitsi.videobridge.xmpp.user.shard.HOSTNAME=localhost
    org.jitsi.videobridge.xmpp.user.shard.USERNAME=jvb
    org.jitsi.videobridge.xmpp.user.shard.PASSWORD=s3cr3t
    org.jitsi.videobridge.xmpp.user.shard.MUC_NICKNAME=test_nick
    org.jitsi.videobridge.xmpp.user.shard.IQ_HANDLER_MODE=sync
    
    org.jitsi.videobridge.xmpp.user.incomlpete.USERNAME=jvb
""".trimIndent()

private val newConfigSingleXmppConnection = """
    videobridge {
        apis {
            xmpp-client {
                configs {
                    shard {
                        DOMAIN=auth.some.domain.net
                        MUC_JIDS="JvbBrewery@internal.some.domain.net"
                        MUC="JvbBrewery@internal.some.domain.net"
                        DISABLE_CERTIFICATE_VERIFICATION=true
                        HOSTNAME="localhost"
                        USERNAME="jvb"
                        PASSWORD="s3cr3t"
                        MUC_NICKNAME="test_nick"
                        IQ_HANDLER_MODE="sync"
                    }
                }
            }
        }
    }
""".trimIndent()

private val newConfigOneCompleteConnectionOneIncomplete = """
    videobridge {
        apis {
            xmpp-client {
                configs {
                    shard {
                        DOMAIN=auth.some.domain.net
                        MUC_JIDS="JvbBrewery@internal.some.domain.net"
                        MUC="JvbBrewery@internal.some.domain.net"
                        DISABLE_CERTIFICATE_VERIFICATION=true
                        HOSTNAME="localhost"
                        USERNAME="jvb"
                        PASSWORD="s3cr3t"
                        MUC_NICKNAME="test_nick"
                        IQ_HANDLER_MODE="sync"
                    }
                    incomplete {
                        DOMAIN="incomplete"
                    }
                }
            }
        }
    }
""".trimIndent()
