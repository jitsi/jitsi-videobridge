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

package org.jitsi.videobridge.rest

import io.kotest.matchers.shouldBe
import org.jitsi.ConfigTest
import org.jitsi.videobridge.Videobridge
import org.jitsi.videobridge.rest.RestConfig.Companion.config

class RestConfigTest : ConfigTest() {
    init {
        context("Default configuration") {
            context("At least one REST API") {
                config.isEnabled() shouldBe true
            }
            context("Colibri") {
                config.isEnabled(RestApis.COLIBRI) shouldBe false
            }
            context("Debug") {
                config.isEnabled(RestApis.DEBUG) shouldBe true
            }
            context("Health") {
                config.isEnabled(RestApis.HEALTH) shouldBe true
            }
            context("Shutdown") {
                config.isEnabled(RestApis.SHUTDOWN) shouldBe false
            }
            context("Drain") {
                config.isEnabled(RestApis.DRAIN) shouldBe true
            }
            context("Version") {
                config.isEnabled(RestApis.VERSION) shouldBe true
            }
        }
        context("Enable/disable with new config") {
            context("Colibri") {
                withNewConfig("videobridge.apis.rest.enabled=true") {
                    config.isEnabled(RestApis.COLIBRI) shouldBe true
                }
            }
            context("Debug") {
                withNewConfig("videobridge.rest.debug.enabled=false") {
                    config.isEnabled(RestApis.DEBUG) shouldBe false
                }
            }
            context("Health") {
                withNewConfig("videobridge.rest.health.enabled=false") {
                    config.isEnabled(RestApis.HEALTH) shouldBe false
                }
            }
            context("Shutdown with colibri") {
                withNewConfig(
                    """
                    videobridge.apis.rest.enabled=true
                    videobridge.rest.shutdown.enabled=true
                    """.trimMargin()
                ) {
                    config.isEnabled(RestApis.SHUTDOWN) shouldBe true
                }
            }
            context("Shutdown without colibri") {
                withNewConfig(
                    """
                    videobridge.apis.rest.enabled=false
                    videobridge.rest.shutdown.enabled=true
                    """.trimMargin()
                ) {
                    config.isEnabled(RestApis.SHUTDOWN) shouldBe false
                }
            }
            context("Version") {
                withNewConfig("videobridge.rest.version.enabled=false") {
                    config.isEnabled(RestApis.VERSION) shouldBe false
                }
            }
        }
        context("Enable/disable with the old way") {
            // The old way to enable REST is via command line argument, which is now simulated with a new config
            context("When colibri is enabled") {
                withNewConfig("${Videobridge.REST_API_PNAME}=true") {
                    config.isEnabled(RestApis.COLIBRI) shouldBe true

                    context("When shutdown is enabled") {
                        withLegacyConfig("org.jitsi.videobridge.ENABLE_REST_SHUTDOWN=true") {
                            config.isEnabled(RestApis.SHUTDOWN) shouldBe true
                        }
                    }

                    withLegacyConfig("org.jitsi.videobridge.ENABLE_REST_COLIBRI=false") {
                        config.isEnabled(RestApis.COLIBRI) shouldBe false
                    }
                }
            }

            context("When colibri is disabled") {
                config.isEnabled(RestApis.SHUTDOWN) shouldBe false
                withLegacyConfig("org.jitsi.videobridge.ENABLE_REST_SHUTDOWN=true") {
                    config.isEnabled(RestApis.SHUTDOWN) shouldBe false
                }
            }
        }
    }
}
