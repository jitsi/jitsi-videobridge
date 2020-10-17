/*
 * Copyright @ 2020 - present 8x8, Inc.
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

package org.jitsi.videobridge.stats.callstats

import io.kotest.matchers.shouldBe
import org.jitsi.ConfigTest
import org.jitsi.utils.secs

internal class CallstatsConfigTest : ConfigTest() {
    init {
        context("Setting the API configuration") {
            context("Without config") {
                val config = CallstatsConfig()
                config.enabled shouldBe false
            }
            context("With legacy config") {
                withLegacyConfig("""
                    io.callstats.sdk.CallStats.appId=1234
                    io.callstats.sdk.CallStats.keyId=foo
                    io.callstats.sdk.CallStats.keyPath=/tmp/ecpriv.jwk
                    io.callstats.sdk.CallStats.bridgeId=bar
                    io.callstats.sdk.CallStats.conferenceIDPrefix=baz
                """.trimIndent()) {
                    val config = CallstatsConfig()
                    config.appId shouldBe 1234
                    config.enabled shouldBe true
                    config.keyId shouldBe "foo"
                    config.keyPath shouldBe "/tmp/ecpriv.jwk"
                    config.bridgeId shouldBe "bar"
                    config.conferenceIdPrefix shouldBe "baz"
                }
            }
            context("With new config") {
                withNewConfig("""
                    videobridge.stats.callstats {
                      app-id = 1234
                      key-id = "foo"
                      key-path = "/tmp/ecpriv.jwk"
                      bridge-id = "bar"
                      conference-id-prefix = "baz"
                    }
                """.trimIndent(), loadDefaults = true) {
                    val config = CallstatsConfig()
                    config.appId shouldBe 1234
                    config.enabled shouldBe true
                    config.keyId shouldBe "foo"
                    config.keyPath shouldBe "/tmp/ecpriv.jwk"
                    config.bridgeId shouldBe "bar"
                    config.conferenceIdPrefix shouldBe "baz"
                }
            }
        }
        context("Setting the interval") {
            context("Default value") {
                CallstatsConfig().interval shouldBe 5.secs
            }
            context("With legacy config") {
                withLegacyConfig("""
                    org.jitsi.videobridge.STATISTICS_INTERVAL.callstats.io=6000
                    org.jitsi.videobridge.STATISTICS_TRANSPORT=callstats.io
                """.trimIndent()) {
                    CallstatsConfig().interval shouldBe 6.secs
                }
            }
            context("With new config (deprecated syntax)") {
                withNewConfig("""
                    videobridge {
                      stats {
                        transports = [
                          { type = "callstatsio", interval = 7 seconds }
                        ]
                      }
                    }
                """.trimIndent(), loadDefaults = true) {
                    CallstatsConfig().interval shouldBe 7.secs
                }
            }
            context("With new config") {
                withNewConfig("videobridge.stats.callstats.interval = 8 seconds", loadDefaults = true) {
                    CallstatsConfig().interval shouldBe 8.secs
                }
            }
        }
    }
}
