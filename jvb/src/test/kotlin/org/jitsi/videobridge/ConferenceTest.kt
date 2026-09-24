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

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.jitsi.ConfigTest
import org.jitsi.nlj.DebugStateMode
import org.jitsi.utils.logging2.LoggerImpl
import org.jitsi.utils.mins
import org.jitsi.utils.time.FakeClock
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
                createLocalEndpoint("abcdabcd", true, false, false, false, false, false, false)
                endpointCount shouldBe 1
                DebugStateMode.entries.forEach { mode ->
                    getDebugState(mode, null).shouldBeValidJsonConf()
                }
            }
        }
        context("Synthetic endpoints") {
            with(Conference(videobridge, "id", name, null, false)) {
                val clock = FakeClock()
                val bot = Endpoint(
                    "bot",
                    this,
                    LoggerImpl("test"),
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    synthetic = true,
                    clock = clock
                )
                bot.synthetic shouldBe true
                // Within the initial timeout the backstop must not fire even with no other endpoints.
                bot.shouldExpire() shouldBe false

                clock.elapse(3.mins)
                // Alone in the conference and past the timeout: the backstop allows expiry.
                bot.shouldExpire() shouldBe true

                // With a non-synthetic local endpoint present, a synthetic endpoint never expires on its own.
                createLocalEndpoint("abcdabcd", true, false, false, false, false, false, false)
                bot.shouldExpire() shouldBe false
            }
        }
        context("Creating relays should work") {
            with(Conference(videobridge, "id", name, null, false)) {
                hasRelays() shouldBe false
                createRelay("relay-id", "mesh-id", true, true)
                hasRelays() shouldBe true
                DebugStateMode.entries.forEach { mode ->
                    getDebugState(mode, null).shouldBeValidJsonConf()
                }
            }
        }
    }
}

fun ObjectNode.shouldBeValidJsonConf() = ObjectMapper().readTree(this.toString())
