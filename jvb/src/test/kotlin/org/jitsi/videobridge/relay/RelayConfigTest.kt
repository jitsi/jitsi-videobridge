/*
 * Copyright @ 2020 - Present, 8x8 Inc
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
package org.jitsi.videobridge.relay

import io.kotest.matchers.shouldBe
import org.jitsi.ConfigTest
import org.jitsi.config.withNewConfig

internal class RelayConfigTest : ConfigTest() {
    init {
        context("enabled") {
            should("Be false by default") {
                RelayConfig.config.enabled shouldBe false
            }
            context("When only the old name is present") {
                withNewConfig("videobridge.octo.enabled=true") {
                    RelayConfig.config.enabled shouldBe true
                }
            }
            context("When only the new name is present") {
                withNewConfig("videobridge.relay.enabled=true") {
                    RelayConfig.config.enabled shouldBe true
                }
            }
            context("When both names are present") {
                withNewConfig("videobridge.octo.enabled=true\nvideobridge.relay.enabled=false") {
                    RelayConfig.config.enabled shouldBe true
                }
                withNewConfig("videobridge.octo.enabled=false\nvideobridge.relay.enabled=true") {
                    RelayConfig.config.enabled shouldBe false
                }
            }
        }
        context("relay-id") {
            withNewConfig("videobridge.octo.relay-id=abc") {
                RelayConfig.config.relayId shouldBe "abc"
            }
            withNewConfig("videobridge.relay.relay-id=abc") {
                RelayConfig.config.relayId shouldBe "abc"
            }
            withNewConfig("videobridge.octo.relay-id=xyz\nvidebridge.relay.relay-id=abc") {
                RelayConfig.config.relayId shouldBe "xyz"
            }
        }
    }
}
