/*
 * Copyright @ 2026 - present 8x8, Inc.
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

import io.kotest.assertions.nondeterministic.continually
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.jitsi.ConfigTest
import org.jitsi.utils.hours
import org.jitsi.utils.time.FakeClock
import org.jxmpp.jid.impl.JidCreate
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for the periodic check in [VideobridgeExpireThread], which is the backstop that expires what jicofo did not.
 */
class VideobridgeExpireThreadTest : ConfigTest() {
    private val videobridge = mockk<Videobridge>(relaxed = true)
    private val name = JidCreate.entityBareFrom("roomName@somedomain.com")

    init {
        context("The periodic expire check") {
            should("expire a relay whose peer is gone, but leave the conference and the others") {
                val clock = FakeClock()
                val conference = Conference(videobridge, "id", name, null, false, clock)
                every { videobridge.conferences } returns listOf(conference)
                try {
                    val local =
                        conference.createLocalEndpoint("abcdabcd", true, false, false, false, false, false, false)
                    // Relays created by the conference share its clock.
                    conference.createRelay("dead", "mesh-id", true, true)
                    val alive = conference.createRelay("alive", "mesh-id", true, true)

                    clock.elapse(1.hours)
                    alive.transceiver.packetIOActivity.lastIceActivityInstant = clock.instant()
                    // The local endpoint shares the conference's clock, so it needs activity too or it expires.
                    local.transceiver.packetIOActivity.lastIceActivityInstant = clock.instant()

                    VideobridgeExpireThread(videobridge).expire(videobridge)

                    eventually(5.seconds) {
                        conference.getRelay("dead") shouldBe null
                    }
                    conference.getRelay("alive") shouldBe alive
                    conference.getLocalEndpoint("abcdabcd") shouldBe local
                    verify(exactly = 0) { videobridge.expireConference(any()) }
                } finally {
                    conference.expire()
                }
            }
            should("expire a conference with only relayed endpoints once the grace period passed") {
                val clock = FakeClock()
                val conference = Conference(videobridge, "id", name, null, false, clock)
                every { videobridge.conferences } returns listOf(conference)
                try {
                    val relay = conference.createRelay("relay-id", "mesh-id", true, true)
                    val remote = relay.addRemoteEndpoint("remote", null, emptyList(), emptyList())!!
                    conference.addEndpoints(setOf(remote))
                    val local =
                        conference.createLocalEndpoint("abcdabcd", true, false, false, false, false, false, false)
                    local.expire()

                    // Within the grace period nothing is expired, even though the relay has had no activity: the
                    // relayed endpoint has no activity to judge, and the relay itself is still within its connect
                    // timeout.
                    VideobridgeExpireThread(videobridge).expire(videobridge)
                    continually(1.seconds) {
                        verify(exactly = 0) { videobridge.expireConference(any()) }
                        conference.getRelay("relay-id") shouldBe relay
                    }

                    clock.elapse(1.hours)
                    VideobridgeExpireThread(videobridge).expire(videobridge)
                    eventually(5.seconds) {
                        verify(exactly = 1) { videobridge.expireConference(conference) }
                    }
                } finally {
                    conference.expire()
                }
            }
            should("not expire a conference between two meshes with no local endpoints") {
                val clock = FakeClock()
                val conference = Conference(videobridge, "id", name, null, false, clock)
                every { videobridge.conferences } returns listOf(conference)
                try {
                    val relay1 = conference.createRelay("relay-1", "mesh-1", true, true)
                    val relay2 = conference.createRelay("relay-2", "mesh-2", true, true)
                    val remote = relay1.addRemoteEndpoint("remote", null, emptyList(), emptyList())!!
                    conference.addEndpoints(setOf(remote))

                    clock.elapse(1.hours)
                    // The peers are alive.
                    relay1.transceiver.packetIOActivity.lastIceActivityInstant = clock.instant()
                    relay2.transceiver.packetIOActivity.lastIceActivityInstant = clock.instant()

                    VideobridgeExpireThread(videobridge).expire(videobridge)
                    continually(1.seconds) {
                        verify(exactly = 0) { videobridge.expireConference(any()) }
                        conference.getRelay("relay-1") shouldBe relay1
                        conference.getRelay("relay-2") shouldBe relay2
                    }
                } finally {
                    conference.expire()
                }
            }
        }
    }
}
