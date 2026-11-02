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

import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.jitsi.ConfigTest
import org.jitsi.utils.logging2.LoggerImpl
import org.jitsi.utils.mins
import org.jitsi.utils.secs
import org.jitsi.utils.time.FakeClock
import org.jitsi.videobridge.relay.Relay
import org.jxmpp.jid.impl.JidCreate

class RelayTest : ConfigTest() {
    private val videobridge = mockk<Videobridge>(relaxed = true)

    init {
        val name = JidCreate.entityBareFrom("roomName@somedomain.com")

        context("Expiring a relay on its own") {
            // The entity expiration timeout is 1 minute by default, and ICE gets 2 minutes to connect.
            should("not expire a relay whose ICE has not connected yet, until the connect timeout") {
                val clock = FakeClock()
                val conference = Conference(videobridge, "id", name, null, false, clock)
                val relay = Relay("relay-id", conference, LoggerImpl("test"), "mesh-id", true, true, clock)
                try {
                    relay.shouldExpire() shouldBe false
                    clock.elapse(119.secs)
                    relay.shouldExpire() shouldBe false
                    clock.elapse(2.secs)
                    relay.shouldExpire() shouldBe true
                } finally {
                    relay.expire()
                    conference.expire()
                }
            }
            should("not expire a relay while its peer keeps sending ICE consent checks, even without media") {
                val clock = FakeClock()
                val conference = Conference(videobridge, "id", name, null, false, clock)
                val relay = Relay("relay-id", conference, LoggerImpl("test"), "mesh-id", true, true, clock)
                try {
                    repeat(100) {
                        clock.elapse(15.secs)
                        relay.transceiver.packetIOActivity.lastIceActivityInstant = clock.instant()
                        relay.shouldExpire() shouldBe false
                    }
                } finally {
                    relay.expire()
                    conference.expire()
                }
            }
            should("expire a relay whose peer has gone quiet for the inactivity timeout") {
                val clock = FakeClock()
                val conference = Conference(videobridge, "id", name, null, false, clock)
                val relay = Relay("relay-id", conference, LoggerImpl("test"), "mesh-id", true, true, clock)
                try {
                    clock.elapse(5.mins)
                    relay.transceiver.packetIOActivity.lastIceActivityInstant = clock.instant()
                    relay.shouldExpire() shouldBe false

                    clock.elapse(59.secs)
                    relay.shouldExpire() shouldBe false
                    clock.elapse(2.secs)
                    relay.shouldExpire() shouldBe true
                } finally {
                    relay.expire()
                    conference.expire()
                }
            }
            should("count received RTP as activity") {
                val clock = FakeClock()
                val conference = Conference(videobridge, "id", name, null, false, clock)
                val relay = Relay("relay-id", conference, LoggerImpl("test"), "mesh-id", true, true, clock)
                try {
                    clock.elapse(5.mins)
                    relay.shouldExpire() shouldBe true
                    relay.transceiver.packetIOActivity.lastRtpPacketReceivedInstant = clock.instant()
                    relay.shouldExpire() shouldBe false
                    clock.elapse(61.secs)
                    relay.shouldExpire() shouldBe true
                } finally {
                    relay.expire()
                    conference.expire()
                }
            }
        }
    }
}
