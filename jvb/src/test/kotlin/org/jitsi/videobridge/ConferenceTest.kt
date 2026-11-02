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
import io.kotest.assertions.nondeterministic.continually
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.mockk
import org.jitsi.ConfigTest
import org.jitsi.nlj.DebugStateMode
import org.jitsi.utils.hours
import org.jitsi.utils.logging2.LoggerImpl
import org.jitsi.utils.mins
import org.jitsi.utils.secs
import org.jitsi.utils.time.FakeClock
import org.jitsi.videobridge.relay.AudioSourceDesc
import org.jitsi.videobridge.relay.SyntheticSourceKind
import org.jxmpp.jid.impl.JidCreate
import kotlin.time.Duration.Companion.seconds

/**
 * This is a high-level test for [Conference] and related functionality.
 */
class ConferenceTest : ConfigTest() {
    private val videobridge = mockk<Videobridge>(relaxed = true)

    init {
        val name = JidCreate.entityBareFrom("roomName@somedomain.com")

        // The conferences are expired at the end, so that their endpoints' and relays' periodic tasks (e.g. bandwidth
        // allocation, which reschedules itself on TaskPools.SCHEDULED_POOL) do not keep running into other specs.
        context("Adding local endpoints should work") {
            with(Conference(videobridge, "id", name, null, false)) {
                try {
                    endpointCount shouldBe 0
                    // TODO cover the case when they're true
                    createLocalEndpoint("abcdabcd", true, false, false, false, false, false, false)
                    endpointCount shouldBe 1
                    DebugStateMode.entries.forEach { mode ->
                        getDebugState(mode, null).shouldBeValidJsonConf()
                    }
                } finally {
                    expire()
                }
            }
        }
        context("A local endpoint with the transcriber ID") {
            with(Conference(videobridge, "id", name, null, false)) {
                try {
                    shouldThrow<IllegalArgumentException> {
                        createLocalEndpoint(Conference.TRANSCRIBER_ID, true, false, false, false, false, false, false)
                    }
                    endpointCount shouldBe 0
                } finally {
                    expire()
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
                try {
                    bot.synthetic shouldBe true
                    // Within the initial timeout the backstop must not fire even with no other endpoints.
                    bot.shouldExpire() shouldBe false

                    clock.elapse(3.mins)
                    // Alone in the conference and past the timeout: the backstop allows expiry.
                    bot.shouldExpire() shouldBe true

                    // With a non-synthetic local endpoint present, a synthetic endpoint never expires on its own.
                    val human = createLocalEndpoint("abcdabcd", true, false, false, false, false, false, false)
                    bot.shouldExpire() shouldBe false

                    // The backstop is a grace period from the last time a human was present, not a snapshot: a
                    // human leaving (a reload, a re-invite) must not expire the bot at the next check.
                    human.expire()
                    bot.shouldExpire() shouldBe false
                    clock.elapse(1.mins)
                    bot.shouldExpire() shouldBe false
                    clock.elapse(3.mins)
                    bot.shouldExpire() shouldBe true
                } finally {
                    expire()
                    // Never added to the conference, so expiring the conference doesn't reach it.
                    bot.expire()
                }
            }
        }
        context("Synthetic endpoints on a relay-only bridge") {
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
                try {
                    val relay = createRelay("relay-id", "mesh-id", true, true)
                    clock.elapse(3.mins)

                    should("expire when the only other endpoints are another bridge's synthetic ones") {
                        val remoteBot = relay.addRemoteEndpoint(
                            "remote-bot",
                            null,
                            emptyList(),
                            emptyList(),
                            synthetic = true
                        )!!
                        addEndpoints(setOf(remoteBot))
                        bot.shouldExpire() shouldBe true
                    }
                    should("stay alive while a human is reachable through a relay, with no local humans") {
                        val remoteHuman = relay.addRemoteEndpoint("remote-human", null, emptyList(), emptyList())!!
                        addEndpoints(setOf(remoteHuman))
                        bot.shouldExpire() shouldBe false
                    }
                } finally {
                    expire()
                    bot.expire()
                }
            }
        }
        context("Speech activity and synthetic sources") {
            with(Conference(videobridge, "id", name, null, false)) {
                try {
                    val human = createLocalEndpoint("human", true, false, false, false, false, false, false)
                    val bot = createLocalEndpoint("bot", true, false, false, false, false, false, true)
                    human.audioSources = listOf(
                        AudioSourceDesc(1L, "human", "human-a0"),
                        AudioSourceDesc(
                            2L,
                            "human",
                            "human-a0.fr",
                            synthetic = true,
                            kind = SyntheticSourceKind.TRANSLATION
                        )
                    )
                    bot.audioSources = listOf(
                        AudioSourceDesc(3L, "bot", "bot-a0", synthetic = true, kind = SyntheticSourceKind.AGENT)
                    )
                    should("count a voice agent's audio as speech, so a talking bot becomes the dominant speaker") {
                        eventually(5.seconds) {
                            // Never dropped by loudest-only filtering, whatever the ranking.
                            levelChanged(bot, 3L, 100) shouldBe false
                            speechActivity.dominantEndpoint shouldBe bot
                        }
                    }
                    should("keep translated audio out of speech activity") {
                        // The first speaker heard becomes dominant at once (see above), so this would flip to human.
                        continually(2.seconds) {
                            levelChanged(human, 2L, 100) shouldBe false
                            speechActivity.dominantEndpoint shouldNotBe human
                        }
                    }
                } finally {
                    expire()
                }
            }
        }
        context("Creating relays should work") {
            with(Conference(videobridge, "id", name, null, false)) {
                try {
                    hasRelays() shouldBe false
                    createRelay("relay-id", "mesh-id", true, true)
                    hasRelays() shouldBe true
                    DebugStateMode.entries.forEach { mode ->
                        getDebugState(mode, null).shouldBeValidJsonConf()
                    }
                } finally {
                    expire()
                }
            }
        }
        context("Expiring a conference") {
            // The entity expiration timeout is 1 minute by default, and a conference may have no endpoints at all for
            // its first 20 seconds.
            should("not expire a new conference with no endpoints before the initial grace period") {
                val clock = FakeClock()
                with(Conference(videobridge, "id", name, null, false, clock)) {
                    try {
                        shouldExpire() shouldBe false
                        clock.elapse(19.secs)
                        shouldExpire() shouldBe false
                        clock.elapse(2.secs)
                        shouldExpire() shouldBe true
                    } finally {
                        expire()
                    }
                }
            }
            should("not expire while a local endpoint is present") {
                val clock = FakeClock()
                with(Conference(videobridge, "id", name, null, false, clock)) {
                    try {
                        createLocalEndpoint("abcdabcd", true, false, false, false, false, false, false)
                        clock.elapse(1.hours)
                        shouldExpire() shouldBe false
                    } finally {
                        expire()
                    }
                }
            }
            should("expire once its last local endpoint is gone, after the grace period") {
                val clock = FakeClock()
                with(Conference(videobridge, "id", name, null, false, clock)) {
                    try {
                        val local = createLocalEndpoint("abcdabcd", true, false, false, false, false, false, false)
                        clock.elapse(1.hours)
                        local.expire()
                        endpointCount shouldBe 0
                        // The 20-second grace period is counted from creation, which is long past.
                        shouldExpire() shouldBe true
                    } finally {
                        expire()
                    }
                }
            }
            context("with relays and relayed endpoints") {
                should("not expire a new conference with only relayed endpoints before the grace period") {
                    val clock = FakeClock()
                    with(Conference(videobridge, "id", name, null, false, clock)) {
                        try {
                            val relay = createRelay("relay-id", "mesh-id", true, true)
                            val remote = relay.addRemoteEndpoint("remote", null, emptyList(), emptyList())!!
                            addEndpoints(setOf(remote))
                            endpointCount shouldBe 1
                            localEndpointCount shouldBe 0

                            // The local endpoint for this conference may still be on its way.
                            clock.elapse(59.secs)
                            shouldExpire() shouldBe false
                            clock.elapse(2.secs)
                            shouldExpire() shouldBe true
                        } finally {
                            expire()
                        }
                    }
                }
                should("not expire while a local endpoint is present, however long") {
                    val clock = FakeClock()
                    with(Conference(videobridge, "id", name, null, false, clock)) {
                        try {
                            val relay = createRelay("relay-id", "mesh-id", true, true)
                            val remote = relay.addRemoteEndpoint("remote", null, emptyList(), emptyList())!!
                            addEndpoints(setOf(remote))
                            createLocalEndpoint("abcdabcd", true, false, false, false, false, false, false)

                            clock.elapse(1.hours)
                            shouldExpire() shouldBe false
                        } finally {
                            expire()
                        }
                    }
                }
                should("expire once the last local endpoint has been gone for the grace period") {
                    // This is the case where jicofo lost track of the conference: the local endpoint expired on its
                    // own because its client left, but nobody removed the relayed endpoint or the relay.
                    val clock = FakeClock()
                    with(Conference(videobridge, "id", name, null, false, clock)) {
                        try {
                            val relay = createRelay("relay-id", "mesh-id", true, true)
                            val remote = relay.addRemoteEndpoint("remote", null, emptyList(), emptyList())!!
                            addEndpoints(setOf(remote))
                            val local = createLocalEndpoint("abcdabcd", true, false, false, false, false, false, false)
                            clock.elapse(1.hours)

                            local.expire()
                            localEndpointCount shouldBe 0
                            endpointCount shouldBe 1
                            hasRelays() shouldBe true

                            // The grace period is counted from the time the local endpoint left, not from creation,
                            // so that a reload or a re-invite does not expire the conference under the new endpoint.
                            shouldExpire() shouldBe false
                            clock.elapse(59.secs)
                            shouldExpire() shouldBe false
                            clock.elapse(2.secs)
                            shouldExpire() shouldBe true

                            // Expiring the conference takes the relay and the relayed endpoint with it.
                            expire()
                            hasRelays() shouldBe false
                            endpointCount shouldBe 0
                            remote.isExpired shouldBe true
                        } finally {
                            expire()
                        }
                    }
                }
                should("not expire a bridge between meshes, even with no local endpoints") {
                    // A known gap, chosen on purpose: a bridge in more than one mesh may legitimately have no local
                    // endpoints, and silently expiring it would break the conference. See doc/relay.md.
                    val clock = FakeClock()
                    with(Conference(videobridge, "id", name, null, false, clock)) {
                        try {
                            val relay1 = createRelay("relay-1", "mesh-1", true, true)
                            createRelay("relay-2", "mesh-2", true, true)
                            val remote = relay1.addRemoteEndpoint("remote", null, emptyList(), emptyList())!!
                            addEndpoints(setOf(remote))

                            clock.elapse(1.hours)
                            shouldExpire() shouldBe false

                            // Not even with no endpoints at all.
                            relay1.removeRemoteEndpoint("remote")
                            endpointCount shouldBe 0
                            clock.elapse(1.hours)
                            shouldExpire() shouldBe false
                        } finally {
                            expire()
                        }
                    }
                }
            }
        }
    }
}

fun ObjectNode.shouldBeValidJsonConf() = ObjectMapper().readTree(this.toString())
