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

import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.jitsi.ConfigTest
import org.jitsi.nlj.DebugStateMode
import org.json.simple.JSONObject
import org.json.simple.parser.JSONParser
import org.jxmpp.jid.impl.JidCreate

/**
 * This is a high-level test for [Conference] and related functionality.
 */
class ConferenceTest : ConfigTest() {
    private val videobridge = mockk<Videobridge>(relaxed = true)

    init {
        val name = JidCreate.entityBareFrom("roomName@somedomain.com")

        context("Adding local endpoints should work") {
            with(Conference(videobridge, "id", name, null, false)) {
                endpointCount shouldBe 0
                // TODO cover the case when they're true
                createLocalEndpoint("abcdabcd", true, false, false, false)
                endpointCount shouldBe 1
                DebugStateMode.entries.forEach { mode ->
                    getDebugState(mode, null).shouldBeValidJson()
                }
            }
        }
        context("Creating relays should work") {
            with(Conference(videobridge, "id", name, null, false)) {
                hasRelays() shouldBe false
                createRelay("relay-id", "mesh-id", true, true)
                hasRelays() shouldBe true
                DebugStateMode.entries.forEach { mode ->
                    getDebugState(mode, null).shouldBeValidJson()
                }
            }
        }
    }
}

fun JSONObject.shouldBeValidJson() = JSONParser().parse(this.toJSONString())
