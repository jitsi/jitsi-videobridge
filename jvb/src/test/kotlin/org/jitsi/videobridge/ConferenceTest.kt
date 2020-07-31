/*
 * Copyright @ 2020 - Present, 8x8, Inc.
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
package org.jitsi.videobridge

import io.kotlintest.shouldBe
import io.kotlintest.shouldThrow
import io.mockk.every
import io.mockk.mockk
import org.jitsi.ConfigTest
import org.jitsi.videobridge.octo.OctoRelayService
import org.json.simple.JSONObject
import org.json.simple.parser.JSONParser
import org.osgi.framework.BundleContext
import org.osgi.framework.ServiceReference

/**
 * This is a high-level test for [Conference] and related functionality.
 */
class ConferenceTest : ConfigTest() {
    private val octoRelayServiceReference: ServiceReference<OctoRelayService> = mockk()
    private val octoRelayService = OctoRelayService()

    private val bundleContext: BundleContext = mockk<BundleContext>().also {
        every { it.getServiceReference(OctoRelayService::class.java) } returns octoRelayServiceReference
        every { it.getService(octoRelayServiceReference) } returns octoRelayService
        every { it.registerService(any() as String, any(), any()) } returns null
    }
    private val videobridge: Videobridge = mockk<Videobridge>().also {
        every { it.bundleContext } returns bundleContext
    }

    init {
        "Adding local endpoints should work" {
            withNewConfig(newConfigOctoEnabled, loadDefaults = true) {
                with(Conference(videobridge, "id", "name", false, Conference.GID_NOT_SET)) {
                    endpointCount shouldBe 0
                    createLocalEndpoint("abcdabcd", true)
                    endpointCount shouldBe 1
                    debugState.shouldBeValidJson()
                }
            }
        }
        "Enabling octo should fail when the GID is not set" {
            withNewConfig(newConfigOctoEnabled, loadDefaults = true) {
                with(Conference(videobridge, "id", "name", false, Conference.GID_NOT_SET)) {
                    isOctoEnabled shouldBe false
                    shouldThrow<IllegalStateException> {
                        tentacle
                    }
                    debugState.shouldBeValidJson()
                }
            }
        }
        "Enabling octo should work" {
            withNewConfig(newConfigOctoEnabled, loadDefaults = true) {
                octoRelayService.start(bundleContext)
                with(Conference(videobridge, "id", "name", false, 1234)) {
                    isOctoEnabled shouldBe false
                    tentacle
                    isOctoEnabled shouldBe true
                    tentacle.setRelays(listOf("127.0.0.1:4097"))

                    debugState.shouldBeValidJson()
                }
            }
        }
    }
}

private val newConfigOctoEnabled = """
    videobridge {
        octo {
            enabled = true
            bind-address = 127.0.0.1
            bind-port = 4096
        }
    }
""".trimMargin()

fun JSONObject.shouldBeValidJson() {
    JSONParser().parse(this.toJSONString())
}
