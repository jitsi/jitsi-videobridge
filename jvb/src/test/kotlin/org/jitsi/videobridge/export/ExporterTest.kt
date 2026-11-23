/*
 * Copyright @ 2024 - Present, 8x8 Inc
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
package org.jitsi.videobridge.export

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.eclipse.jetty.websocket.api.Session
import org.eclipse.jetty.websocket.core.CloseStatus
import org.jitsi.config.withNewConfig
import org.jitsi.mediajson.Media
import org.jitsi.mediajson.MediaEvent
import org.jitsi.mediajson.TranscriptionResultEvent
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.rtp.AudioRtpPacket
import org.jitsi.utils.concurrent.FakeScheduledExecutorService
import org.jitsi.utils.logging2.LoggerImpl
import org.jitsi.videobridge.AbstractEndpoint
import org.jitsi.videobridge.relay.AudioSourceDesc
import org.jitsi.videobridge.util.LogCapture
import org.jitsi.videobridge.util.TaskPools
import org.jitsi.xmpp.extensions.colibri2.Connect
import java.net.URI
import java.nio.channels.ClosedChannelException
import java.util.concurrent.TimeUnit

/**
 * Drives [Exporter] directly (no socket). Verifies how inbound mediajson `start`/`stop` events map to the
 * synthetic-source sending-change callback (a start/stop with a talk timestamp fires the callback, start ->
 * sending=true, stop -> sending=false; one without a timestamp, i.e. a plain stream start/stop, does not), which
 * audio [Exporter.wants] to export given the connect's exports/requests lists, which websocket close codes lead
 * to a reconnect, and that a lost connection is replaced once.
 */
class ExporterTest : ShouldSpec() {
    private data class Change(val sourceName: String, val sending: Boolean, val timestamp: Long)

    /** The sources reported to the handler each time the websocket of the last [fixture] opened. */
    private val producerConnections = mutableListOf<List<String>>()

    /**
     * A fresh, not-yet-connected [Exporter] plus the list capturing its sending-change callback invocations. [sources]
     * backs the audio-source lookup (SSRC -> source name); any other SSRC is unknown (resolves to null).
     */
    private fun fixture(
        exports: List<String> = emptyList(),
        requests: List<String> = emptyList(),
        sources: Map<Long, String> = emptyMap(),
        type: Connect.Types = Connect.Types.RECORDER,
        ping: Boolean = false,
        handler: ExporterEventHandler? = null
    ): Pair<Exporter, MutableList<Change>> {
        val changes = mutableListOf<Change>()
        producerConnections.clear()
        val exporter = Exporter(
            URI("ws://localhost:1/"),
            emptyMap(),
            LoggerImpl(javaClass.name),
            handler ?: object : ExporterEventHandler {
                override fun handleTranscriptionResult(event: TranscriptionResultEvent) {}
                override fun handleMediaEvent(event: MediaEvent) {}
                override fun handleSendingChange(sourceName: String, sending: Boolean, timestamp: Long) {
                    changes.add(Change(sourceName, sending, timestamp))
                }
                override fun handleProducerConnected(sourceNames: List<String>) {
                    producerConnections.add(sourceNames)
                }
                override fun getAudioSourceName(ssrc: Long): String? = sources[ssrc]
                override fun getDiarize(ssrc: Long): Boolean = false
            },
            pingEnabled = ping,
            pingIntervalMs = 10_000,
            pingTimeoutMs = 3_000,
            type = type,
            exports = exports,
            requests = requests
        )
        return exporter to changes
    }

    /** Mark [exporter] connected through a mock session (no socket), returning the session to verify what it sent. */
    private fun connect(exporter: Exporter): Session =
        mockk<Session>(relaxed = true).also { exporter.recorderWebSocket.session = it }

    /** An audio packet on [ssrc], received from the endpoint [sender]. */
    private fun audioPacket(ssrc: Long, sender: String? = null) =
        PacketInfo(AudioRtpPacket(ByteArray(1500), 0, 100).apply { this.ssrc = ssrc }).apply { endpointId = sender }

    /** Replace the shared scheduler with a fake, so reconnects are observable as pending jobs and never dial out. */
    private fun fakeScheduler() = FakeScheduledExecutorService().also { TaskPools.SCHEDULED_POOL = it }

    private fun Exporter.close(code: Int, reason: String) = recorderWebSocket.onWebSocketClose(code, reason)

    init {
        afterSpec { TaskPools.resetScheduledPool() }

        context("the opening of the websocket of a peer that produces synthetic sources") {
            should("be reported to the handler with the requested sources, each time it opens") {
                val (exporter, _) = fixture(requests = listOf("agent-a0"), type = Connect.Types.AGENT)

                exporter.recorderWebSocket.onWebSocketOpen(mockk<Session>(relaxed = true))
                producerConnections shouldBe listOf(listOf("agent-a0"))
                // The peer numbers its media from the start again after a reconnect.
                exporter.recorderWebSocket.onWebSocketOpen(mockk<Session>(relaxed = true))
                producerConnections shouldBe listOf(listOf("agent-a0"), listOf("agent-a0"))
            }
            should("be reported with the requests of a live update") {
                val (exporter, _) = fixture(requests = listOf("ep1-a0.fr"), type = Connect.Types.TRANSLATOR)
                exporter.update(emptyList(), listOf("ep1-a0.fr", "ep1-a0.de"))

                exporter.recorderWebSocket.onWebSocketOpen(mockk<Session>(relaxed = true))
                producerConnections shouldBe listOf(listOf("ep1-a0.fr", "ep1-a0.de"))
            }
            should("not be reported for a peer that produces nothing") {
                val (exporter, _) = fixture(exports = listOf("ep1-a0"))

                exporter.recorderWebSocket.onWebSocketOpen(mockk<Session>(relaxed = true))
                producerConnections shouldBe emptyList()
            }
            should("not be reported for a transcriber, whose requests are text-translation languages") {
                val (exporter, _) = fixture(requests = listOf("hi"), type = Connect.Types.TRANSCRIBER)
                val session = mockk<Session>(relaxed = true)

                exporter.recorderWebSocket.onWebSocketOpen(session)
                producerConnections shouldBe emptyList()
                // The languages still reach the peer, in the sources event.
                val sent = mutableListOf<String>()
                verify { session.sendText(capture(sent), any()) }
                val sourcesEvent = sent.map { ObjectMapper().readTree(it) }
                    .single { it.path("event").asText() == "sources" }
                sourcesEvent.path("exports").map { it.asText() } shouldBe emptyList()
                sourcesEvent.path("requests").map { it.asText() } shouldBe listOf("hi")
            }
        }
        context("inbound start/stop dispatch to the sending-change callback") {
            should("fire sending=true for a start carrying a talk timestamp") {
                val (exporter, changes) = fixture()
                exporter.handleIncomingMessage(
                    """
                    {"event":"start","sequenceNumber":1,"start":{"tag":"55555555-a0.hi",
                    "mediaFormat":{"encoding":"opus","sampleRate":48000,"channels":1},"timestamp":384000}}
                    """.trimIndent().replace("\n", "")
                )
                changes shouldBe listOf(Change("55555555-a0.hi", true, 384000))
            }
            should("fire sending=false for a stop carrying a talk timestamp") {
                val (exporter, changes) = fixture()
                exporter.handleIncomingMessage(
                    """{"event":"stop","sequenceNumber":2,"stop":{"tag":"55555555-a0.hi","timestamp":960000}}"""
                )
                changes shouldBe listOf(Change("55555555-a0.hi", false, 960000))
            }
            should("ignore a start with no timestamp (a plain stream-start announcement)") {
                val (exporter, changes) = fixture()
                exporter.handleIncomingMessage(
                    """
                    {"event":"start","sequenceNumber":3,"start":{"tag":"55555555-a0.hi",
                    "mediaFormat":{"encoding":"opus","sampleRate":48000,"channels":1}}}
                    """.trimIndent().replace("\n", "")
                )
                changes shouldBe emptyList()
            }
            should("ignore a stop with no timestamp (a plain stop)") {
                val (exporter, changes) = fixture()
                exporter.handleIncomingMessage(
                    """{"event":"stop","sequenceNumber":4,"stop":{"tag":"55555555-a0.hi"}}"""
                )
                changes shouldBe emptyList()
            }
        }

        context("ping/pong handling") {
            should("cancel pending ping timeout when matching pong is received") {
                val (exporter, _) = fixture()
                val future = TaskPools.SCHEDULED_POOL.schedule({ }, 10, TimeUnit.SECONDS)
                exporter.lastPingSentId.set(10)
                exporter.pingTimeoutFuture = future

                exporter.handleIncomingMessage("""{"event":"pong","id":10}""")

                future.isCancelled shouldBe true
                exporter.pingTimeoutFuture shouldBe null
                (exporter.lastPongReceivedMs.get() > 0) shouldBe true
            }

            should("ignore outdated pong with lower id") {
                val (exporter, _) = fixture()
                val future = TaskPools.SCHEDULED_POOL.schedule({ }, 10, TimeUnit.SECONDS)
                exporter.lastPingSentId.set(10)
                exporter.pingTimeoutFuture = future

                exporter.handleIncomingMessage("""{"event":"pong","id":9}""")

                future.isCancelled shouldBe false
                exporter.pingTimeoutFuture shouldBe future
                exporter.lastPongReceivedMs.get() shouldBe 0L
                future.cancel(false)
            }

            should("ignore pong with higher id") {
                val (exporter, _) = fixture()
                val future = TaskPools.SCHEDULED_POOL.schedule({ }, 10, TimeUnit.SECONDS)
                exporter.lastPingSentId.set(10)
                exporter.pingTimeoutFuture = future

                exporter.handleIncomingMessage("""{"event":"pong","id":11}""")

                future.isCancelled shouldBe false
                exporter.pingTimeoutFuture shouldBe future
                exporter.lastPongReceivedMs.get() shouldBe 0L
                future.cancel(false)
            }
        }
        context("wants(): which audio a connect exports") {
            val s1 = 1111L
            val s2 = 2222L
            val sources = mapOf(s1 to "ep1-a0", s2 to "ep2-a0")

            /**
             * A packet on [ssrc] from [sender]. By default, the sender is the part of the source name before the first
             * '-', and null for an SSRC that is not in [sources].
             */
            fun packet(ssrc: Long, sender: String? = sources[ssrc]?.substringBefore('-')) = audioPacket(ssrc, sender)

            should("export all audio when there are no requests and exports is empty (transcriber/recorder)") {
                val (exporter, _) = fixture(sources = sources)
                connect(exporter)
                exporter.wants(packet(s1)) shouldBe true
                exporter.wants(packet(s2)) shouldBe true
            }
            should("export all audio to a transcriber with text-translation languages and an empty exports") {
                val (exporter, _) =
                    fixture(requests = listOf("hi"), sources = sources, type = Connect.Types.TRANSCRIBER)
                connect(exporter)
                exporter.wants(packet(s1)) shouldBe true
                exporter.wants(packet(s2)) shouldBe true
            }
            should("keep exporting all audio to a transcriber when an update adds a text-translation language") {
                val (exporter, _) = fixture(sources = sources, type = Connect.Types.TRANSCRIBER)
                connect(exporter)
                exporter.wants(packet(s1)) shouldBe true

                exporter.update(exports = emptyList(), requests = listOf("hi"))

                exporter.wants(packet(s1)) shouldBe true
                exporter.wants(packet(s2)) shouldBe true
            }
            should("export only the listed sources to a transcriber with exports and text-translation languages") {
                val (exporter, _) = fixture(
                    exports = listOf("ep1-a0"),
                    requests = listOf("hi"),
                    sources = sources,
                    type = Connect.Types.TRANSCRIBER
                )
                connect(exporter)
                exporter.wants(packet(s1)) shouldBe true
                exporter.wants(packet(s2)) shouldBe false
            }
            should("export nothing when there are requests but exports is empty (voice agent)") {
                val (exporter, _) =
                    fixture(requests = listOf("agent-a0"), sources = sources, type = Connect.Types.AGENT)
                connect(exporter)
                exporter.wants(packet(s1)) shouldBe false
                exporter.wants(packet(s2)) shouldBe false
            }
            should("export nothing when there are requests but exports is empty (translator)") {
                val (exporter, _) =
                    fixture(requests = listOf("ep1-a0.fr"), sources = sources, type = Connect.Types.TRANSLATOR)
                connect(exporter)
                exporter.wants(packet(s1)) shouldBe false
                exporter.wants(packet(s2)) shouldBe false
            }
            should("export nothing when there are requests but exports is empty (any type but transcriber)") {
                // Only a transcriber's requests are not source names. Every other type, also a recorder, is treated
                // as a producer of the requested sources.
                val (exporter, _) =
                    fixture(requests = listOf("ep1-a0.fr"), sources = sources, type = Connect.Types.RECORDER)
                connect(exporter)
                exporter.wants(packet(s1)) shouldBe false
                exporter.wants(packet(s2)) shouldBe false
            }
            should("export only the listed sources when there are requests and exports") {
                val (exporter, _) = fixture(
                    exports = listOf("ep1-a0"),
                    requests = listOf("agent-a0"),
                    sources = sources,
                    type = Connect.Types.AGENT
                )
                connect(exporter)
                exporter.wants(packet(s1)) shouldBe true
                exporter.wants(packet(s2)) shouldBe false
            }
            should("start exporting a source once a live update adds it to exports") {
                val (exporter, _) =
                    fixture(requests = listOf("agent-a0"), sources = sources, type = Connect.Types.AGENT)
                val session = connect(exporter)
                exporter.wants(packet(s1)) shouldBe false

                exporter.update(exports = listOf("ep1-a0"), requests = listOf("agent-a0"))

                exporter.wants(packet(s1)) shouldBe true
                exporter.wants(packet(s2)) shouldBe false
                // The peer learns the new lists through the (unchanged) sources event.
                val sent = slot<String>()
                verify(exactly = 1) { session.sendText(capture(sent), any()) }
                withClue(sent.captured) {
                    val json = ObjectMapper().readTree(sent.captured)
                    json.path("event").asText() shouldBe "sources"
                    json.path("exports").map { it.asText() } shouldBe listOf("ep1-a0")
                    json.path("requests").map { it.asText() } shouldBe listOf("agent-a0")
                }
            }
            should("never export audio from an unknown SSRC") {
                val unknown = 9999L
                val (all, _) = fixture(sources = sources)
                connect(all)
                all.wants(packet(unknown)) shouldBe false
                val (listed, _) = fixture(
                    exports = listOf("ep1-a0"),
                    requests = listOf("agent-a0"),
                    sources = sources,
                    type = Connect.Types.AGENT
                )
                connect(listed)
                listed.wants(packet(unknown)) shouldBe false
            }
            should("export nothing while not connected") {
                val (exporter, _) = fixture(sources = sources)
                exporter.wants(packet(s1)) shouldBe false
            }
            should("export all audio, also a source with a name that does not start with the ID of its endpoint") {
                val s3 = 3333L
                val (exporter, _) = fixture(sources = sources + (s3 to "ep1-a0"))
                connect(exporter)
                exporter.wants(packet(s3, sender = "ep2")) shouldBe true
            }
            mapOf(
                Connect.Types.AGENT to listOf("agent-a0"),
                Connect.Types.TRANSLATOR to listOf("ep1-a0.fr"),
                Connect.Types.TRANSCRIBER to listOf("hi")
            ).forEach { (type, requests) ->
                should("export a listed name only from the endpoint whose ID the name starts with ($type)") {
                    // ep2 signals a second source with the name of the source of ep1.
                    val s3 = 3333L
                    val (exporter, _) = fixture(
                        exports = listOf("ep1-a0"),
                        requests = requests,
                        sources = sources + (s3 to "ep1-a0"),
                        type = type
                    )
                    connect(exporter)
                    exporter.wants(packet(s1)) shouldBe true
                    exporter.wants(packet(s3, sender = "ep2")) shouldBe false
                }
            }
            should("export a listed name of an endpoint with '-' in its ID") {
                val s3 = 3333L
                val (exporter, _) = fixture(
                    exports = listOf("sip-user-a0"),
                    requests = listOf("agent-a0"),
                    sources = mapOf(s3 to "sip-user-a0"),
                    type = Connect.Types.AGENT
                )
                connect(exporter)
                exporter.wants(packet(s3, sender = "sip-user")) shouldBe true
                exporter.wants(packet(s3, sender = "sip-use")) shouldBe false
            }
            should("not export a listed name that starts with the endpoint ID with no '-' after it") {
                val s3 = 3333L
                val (exporter, _) = fixture(
                    exports = listOf("ep12-a0"),
                    requests = listOf("agent-a0"),
                    sources = mapOf(s3 to "ep12-a0"),
                    type = Connect.Types.AGENT
                )
                connect(exporter)
                exporter.wants(packet(s3, sender = "ep1")) shouldBe false
            }
            should("not export a listed name from a packet with no sender") {
                val (exporter, _) = fixture(
                    exports = listOf("ep1-a0"),
                    requests = listOf("agent-a0"),
                    sources = sources,
                    type = Connect.Types.AGENT
                )
                connect(exporter)
                exporter.wants(packet(s1, sender = null)) shouldBe false
            }
            should("count each packet of a listed name that it does not export, and log each name and sender once") {
                val s3 = 3333L
                LogCapture(ExporterTest::class.java.name).use { log ->
                    val (exporter, _) = fixture(
                        exports = listOf("ep1-a0"),
                        requests = listOf("agent-a0"),
                        sources = sources + (s3 to "ep1-a0"),
                        type = Connect.Types.AGENT
                    )
                    connect(exporter)

                    repeat(3) { exporter.wants(packet(s3, sender = "ep2")) shouldBe false }
                    exporter.wants(packet(s3, sender = "ep3")) shouldBe false
                    // Not counted: audio of a source that is not listed, and audio that is exported.
                    exporter.wants(packet(s2)) shouldBe false
                    exporter.wants(packet(s1)) shouldBe true

                    exporter.debugState().path("listed_source_packets_not_exported").asLong() shouldBe 4
                    val lines = log.text.lines().filter { it.contains("Not exporting the audio of source") }
                    lines.size shouldBe 2
                    lines[0] shouldContain "source ep1-a0 from endpoint ep2"
                    lines[1] shouldContain "source ep1-a0 from endpoint ep3"
                }
            }
        }
        context("isNamedFor(): whether a source name starts with an endpoint ID") {
            listOf(
                Triple("ep1-a0", "ep1", true),
                Triple("ep1-v1", "ep1", true),
                Triple("ep1-a0.fr", "ep1", true),
                Triple("sip-user-a0", "sip-user", true),
                Triple("ep1", "ep1", false),
                Triple("ep", "ep1", false),
                Triple("ep1-", "ep1", false),
                Triple("ep12-a0", "ep1", false),
                Triple("xep1-a0", "ep1", false),
                Triple("ep1a0", "ep1", false)
            ).forEach { (name, id, expected) ->
                should("be $expected for the name '$name' and the endpoint '$id'") {
                    Exporter.isNamedFor(name, id) shouldBe expected
                }
            }
        }

        context("speakers: passing the dominant-speaker order on to the peer") {
            val sources = mapOf(1L to "ep1-a0", 2L to "ep2-a0")

            /** Speaker sources with these names. The endpoint of each is the part of the name before the first '-'. */
            fun speakers(vararg names: String) = names.map { SpeakerSource(it, it.substringBefore('-')) }
            fun Session.sentEvents(): List<ObjectNode> {
                val sent = mutableListOf<String>()
                verify { sendText(capture(sent), any()) }
                return sent.map { ObjectMapper().readTree(it) as ObjectNode }
            }

            should("send the order limited to the exported sources, on an agent connect") {
                val (exporter, _) = fixture(
                    exports = listOf("ep1-a0"),
                    requests = listOf("agent-a0"),
                    sources = sources,
                    type = Connect.Types.AGENT
                )
                val session = connect(exporter)

                exporter.speakersChanged(speakers("ep2-a0", "ep1-a0", "ep3-a0"), silence = false)

                val speakers = session.sentEvents().single { it.path("event").asText() == "speakers" }
                speakers.path("speakers").map { it.asText() } shouldBe listOf("ep1-a0")
                speakers.path("silence").asBoolean() shouldBe false
            }
            should("send the last known order when the connection opens, after the sources") {
                val (exporter, _) = fixture(
                    exports = listOf("ep1-a0", "ep2-a0"),
                    requests = listOf("agent-a0"),
                    sources = sources,
                    type = Connect.Types.AGENT
                )
                exporter.speakersChanged(speakers("ep2-a0", "ep1-a0"), silence = true)
                val session = mockk<Session>(relaxed = true)

                exporter.recorderWebSocket.onWebSocketOpen(session)

                val events = session.sentEvents().map { it.path("event").asText() }
                events.take(2) shouldBe listOf("sources", "speakers")
                val speakers = session.sentEvents().single { it.path("event").asText() == "speakers" }
                speakers.path("speakers").map { it.asText() } shouldBe listOf("ep2-a0", "ep1-a0")
                speakers.path("silence").asBoolean() shouldBe true
            }
            should("send nothing before the order is known") {
                val (exporter, _) = fixture(requests = listOf("agent-a0"), type = Connect.Types.AGENT)
                val session = mockk<Session>(relaxed = true)

                exporter.recorderWebSocket.onWebSocketOpen(session)

                session.sentEvents().none { it.path("event").asText() == "speakers" } shouldBe true
            }
            should("re-send the order when a live update changes the exports") {
                val (exporter, _) = fixture(
                    requests = listOf("agent-a0"),
                    sources = sources,
                    type = Connect.Types.AGENT
                )
                val session = connect(exporter)
                exporter.speakersChanged(speakers("ep2-a0", "ep1-a0"), silence = false)

                exporter.update(exports = listOf("ep2-a0"), requests = listOf("agent-a0"))

                val orders = session.sentEvents()
                    .filter { it.path("event").asText() == "speakers" }
                    .map { e -> e.path("speakers").map { it.asText() } }
                orders shouldBe listOf(emptyList(), listOf("ep2-a0"))
            }
            should("leave out a listed name of a speaker whose ID does not start the name, on an agent connect") {
                val (exporter, _) = fixture(
                    exports = listOf("ep1-a0"),
                    requests = listOf("agent-a0"),
                    sources = sources,
                    type = Connect.Types.AGENT
                )
                val session = connect(exporter)

                // ep2 signals a source with the name of the source of ep1.
                exporter.speakersChanged(
                    listOf(SpeakerSource("ep1-a0", "ep2"), SpeakerSource("ep1-a0", "ep1")),
                    silence = false
                )

                val speakers = session.sentEvents().single { it.path("event").asText() == "speakers" }
                speakers.path("speakers").map { it.asText() } shouldBe listOf("ep1-a0")
            }
            should("send every speaker, also a name in another form, on an agent connect that exports all audio") {
                // The same sources as wants() exports for this connect.
                val (exporter, _) = fixture(sources = sources, type = Connect.Types.AGENT)
                val session = connect(exporter)

                exporter.speakersChanged(
                    listOf(SpeakerSource("mic0", "ep2"), SpeakerSource("ep1-a0", "ep1")),
                    silence = false
                )

                val speakers = session.sentEvents().single { it.path("event").asText() == "speakers" }
                speakers.path("speakers").map { it.asText() } shouldBe listOf("mic0", "ep1-a0")
            }
            should("not send the order to a non-agent peer") {
                val (exporter, _) = fixture(sources = sources, type = Connect.Types.TRANSCRIBER)
                val session = connect(exporter)

                exporter.speakersChanged(speakers("ep1-a0"), silence = false)

                verify(exactly = 0) { session.sendText(any(), any()) }
            }
        }
        context("SpeakerSource.of(): the sources that the speaker order carries") {
            fun endpoint(id: String, vararg sources: AudioSourceDesc) = mockk<AbstractEndpoint> {
                every { this@mockk.id } returns id
                every { audioSources } returns sources.toList()
            }

            should("give the own named audio sources of the endpoints with their IDs, in the order of the endpoints") {
                val ep1 = endpoint("ep1", AudioSourceDesc(1L, "ep1", "ep1-a0"))
                val ep2 = endpoint("ep2", AudioSourceDesc(2L, "ep2", "ep2-a0"), AudioSourceDesc(3L, "ep2", "mic0"))
                SpeakerSource.of(listOf(ep2, ep1)) shouldBe listOf(
                    SpeakerSource("ep2-a0", "ep2"),
                    SpeakerSource("mic0", "ep2"),
                    SpeakerSource("ep1-a0", "ep1")
                )
            }
            should("leave out synthetic sources and sources with no name") {
                val ep1 = endpoint(
                    "ep1",
                    AudioSourceDesc(1L, "ep1", "ep1-a0"),
                    AudioSourceDesc(2L, "ep1", "ep1-a0.fr", synthetic = true),
                    AudioSourceDesc(3L, "ep1", null)
                )
                SpeakerSource.of(listOf(ep1)) shouldBe listOf(SpeakerSource("ep1-a0", "ep1"))
            }
        }
        context("reconnecting after a websocket close") {
            should("schedule a reconnect after an abnormal close (1006)") {
                val scheduler = fakeScheduler()
                val (exporter, _) = fixture(requests = listOf("agent-a0"))

                exporter.close(1006, "abnormal closure")

                scheduler.numPendingJobs() shouldBe 1
                val debugState = exporter.debugState()
                debugState.path("reconnect_attempts").asInt() shouldBe 1
                debugState.has("terminal_close") shouldBe false
            }
            should("count 1011 as an internal error and still reconnect") {
                val scheduler = fakeScheduler()
                val (exporter, _) = fixture()

                exporter.close(1011, "internal error")

                scheduler.numPendingJobs() shouldBe 1
                exporter.debugState().path("websocket_internal_errors").asLong() shouldBe 1
            }
            listOf(
                Exporter.CLOSE_AGENT_ENDED to "agent ended",
                Exporter.CLOSE_ENDPOINT_UNREACHABLE to "endpoint unreachable: 404"
            ).forEach { (code, reason) ->
                should("not reconnect after a terminal close ($code) and record it in debugState") {
                    val scheduler = fakeScheduler()
                    val (exporter, _) = fixture(requests = listOf("agent-a0"))

                    exporter.close(code, reason)

                    scheduler.numPendingJobs() shouldBe 0
                    exporter.isConnected() shouldBe false
                    val debugState = exporter.debugState()
                    debugState.path("reconnect_attempts").asInt() shouldBe 0
                    debugState.path("terminal_close").path("code").asInt() shouldBe code
                    debugState.path("terminal_close").path("reason").asText() shouldBe reason
                }
            }
            should("stay terminal: a later update or websocket error doesn't reconnect either") {
                val scheduler = fakeScheduler()
                val (exporter, _) = fixture(requests = listOf("agent-a0"))
                exporter.close(Exporter.CLOSE_AGENT_ENDED, "agent ended")

                exporter.update(exports = listOf("ep1-a0"), requests = listOf("agent-a0"))
                exporter.recorderWebSocket.onWebSocketError(RuntimeException("late error"))

                scheduler.numPendingJobs() shouldBe 0
                exporter.debugState().path("reconnect_attempts").asInt() shouldBe 0
            }
            should("cancel a reconnect already scheduled when a terminal close arrives") {
                val scheduler = fakeScheduler()
                val (exporter, _) = fixture(requests = listOf("agent-a0"), type = Connect.Types.AGENT)
                exporter.recorderWebSocket.onWebSocketError(RuntimeException("handshake failed"))
                scheduler.numPendingJobs() shouldBe 1

                exporter.close(Exporter.CLOSE_ENDPOINT_UNREACHABLE, "endpoint refused: HTTP 401")

                scheduler.numPendingJobs() shouldBe 0
                exporter.debugState().path("terminal_close").path("code").asInt() shouldBe
                    Exporter.CLOSE_ENDPOINT_UNREACHABLE
            }
            should("cap reconnects at agent-max-reconnect-attempts only for agent connects") {
                withNewConfig("videobridge.exporter.agent-max-reconnect-attempts = 2") {
                    fakeScheduler()
                    val (agent, _) = fixture(requests = listOf("agent-a0"), type = Connect.Types.AGENT)
                    val (transcriber, _) = fixture()

                    repeat(3) {
                        // Each close is that of a new connection, as after a redial.
                        agent.newConnection().onWebSocketClose(1006, "abnormal closure")
                        transcriber.newConnection().onWebSocketClose(1006, "abnormal closure")
                    }

                    agent.debugState().path("reconnect_exhausted").asBoolean() shouldBe true
                    val transcriberState = transcriber.debugState()
                    transcriberState.path("reconnect_exhausted").asBoolean() shouldBe false
                    transcriberState.path("reconnect_attempts").asInt() shouldBe 3
                }
            }
        }
        context("one reconnect for each lost connection") {
            should("reconnect once when the loss is reported as an error and then as a close") {
                val scheduler = fakeScheduler()
                val (exporter, _) = fixture(requests = listOf("agent-a0"))
                exporter.recorderWebSocket.onWebSocketOpen(mockk<Session>(relaxed = true))

                exporter.recorderWebSocket.onWebSocketError(ClosedChannelException())
                exporter.close(1006, "Session Closed")

                scheduler.numPendingJobs() shouldBe 1
                exporter.debugState().path("reconnect_attempts").asInt() shouldBe 1
            }
            should("reconnect once when a ping times out and the close it asks for follows") {
                val scheduler = fakeScheduler()
                val (exporter, _) = fixture(ping = true)
                val session = mockk<Session>(relaxed = true)
                exporter.recorderWebSocket.onWebSocketOpen(session)
                // The ping, then its timeout.
                scheduler.runOne()
                scheduler.runOne()
                verify { session.close(1000, "Ping timeout", any()) }

                exporter.close(1000, "Ping timeout")

                scheduler.numPendingJobs() shouldBe 1
                exporter.debugState().path("reconnect_attempts").asInt() shouldBe 1
            }
            should("leave a new connection and its ping alone when the one it replaced reports its close") {
                val scheduler = fakeScheduler()
                val (exporter, _) = fixture(ping = true)
                val replaced = exporter.recorderWebSocket
                replaced.onWebSocketOpen(mockk<Session>(relaxed = true))
                replaced.onWebSocketError(ClosedChannelException())
                // What the reconnect does, without the dial.
                val session = mockk<Session>(relaxed = true)
                exporter.newConnection().onWebSocketOpen(session)

                replaced.onWebSocketClose(1006, "Session Closed")

                exporter.isConnected() shouldBe true
                exporter.debugState().path("reconnect_attempts").asInt() shouldBe 1
                // The only job left is the ping of the new connection.
                scheduler.numPendingJobs() shouldBe 1
                scheduler.runOne()
                verify { session.sendText(match { "\"event\":\"ping\"" in it }, any()) }
            }
            should("ignore the reports of a connection that was replaced before it was lost") {
                val scheduler = fakeScheduler()
                val (exporter, _) = fixture(ping = true)
                val replaced = exporter.recorderWebSocket
                exporter.newConnection().onWebSocketOpen(mockk<Session>(relaxed = true))

                replaced.onWebSocketError(ClosedChannelException())
                replaced.onWebSocketClose(1006, "Session Closed")

                exporter.isConnected() shouldBe true
                exporter.debugState().path("reconnect_attempts").asInt() shouldBe 0
                // The only job is the ping of the connection in use.
                scheduler.numPendingJobs() shouldBe 1
            }
            should("disconnect the session that a connection still has when it is replaced") {
                val scheduler = fakeScheduler()
                val (exporter, _) = fixture(ping = true)
                val session = mockk<Session>(relaxed = true)
                exporter.recorderWebSocket.onWebSocketOpen(session)
                // The ping, then its timeout: the peer may never answer the close that it asks for.
                scheduler.runOne()
                scheduler.runOne()
                verify(exactly = 0) { session.disconnect() }

                exporter.newConnection()

                verify { session.disconnect() }
            }
            should("close a connection that opens after a terminal close") {
                fakeScheduler()
                val (exporter, _) = fixture(requests = listOf("agent-a0"), type = Connect.Types.AGENT)
                val replaced = exporter.recorderWebSocket
                replaced.onWebSocketError(ClosedChannelException())
                // The reconnect was already dialing when the peer said not to redial.
                val dialing = exporter.newConnection()
                replaced.onWebSocketClose(Exporter.CLOSE_AGENT_ENDED, "agent ended")
                val session = mockk<Session>(relaxed = true)

                dialing.onWebSocketOpen(session)

                verify { session.close(CloseStatus.SHUTDOWN, "closing", any()) }
                exporter.isConnected() shouldBe false
                producerConnections shouldBe emptyList()
            }
            should("close a connection that opens after the exporter stopped") {
                fakeScheduler()
                val (exporter, _) = fixture(requests = listOf("agent-a0"))
                exporter.stop()
                val session = mockk<Session>(relaxed = true)

                exporter.recorderWebSocket.onWebSocketOpen(session)

                verify { session.close(CloseStatus.SHUTDOWN, "closing", any()) }
                exporter.isConnected() shouldBe false
                producerConnections shouldBe emptyList()
            }
            should("count the attempts from one after a stable connection, also when an error reports its loss") {
                // Any connection that opened counts as stable.
                withNewConfig("videobridge.exporter.stable-connection-threshold = -1 ms") {
                    fakeScheduler()
                    val (exporter, _) = fixture()
                    repeat(2) { exporter.newConnection().onWebSocketClose(1006, "abnormal closure") }
                    exporter.debugState().path("reconnect_attempts").asInt() shouldBe 2
                    val stable = exporter.newConnection()
                    stable.onWebSocketOpen(mockk<Session>(relaxed = true))

                    stable.onWebSocketError(ClosedChannelException())

                    exporter.debugState().path("reconnect_attempts").asInt() shouldBe 1
                }
            }
        }

        context("the log lines about messages that hold the content of a meeting") {
            val secret = "SECRET-WORDS-31415"
            should("not quote an incoming message that cannot be parsed") {
                LogCapture(ExporterTest::class.java.name).use { log ->
                    val (exporter, _) = fixture()

                    exporter.handleIncomingMessage("""{"event":"no-such-event","text":"$secret"}""")
                    exporter.handleIncomingMessage(
                        """{"event":"transcription-result","transcript":[{"text":"$secret""""
                    )
                    exporter.handleIncomingMessage("""{"event":"transcription-result","text":$secret}""")

                    log.text shouldContain "Failed to parse incoming websocket message"
                    log.text shouldNotContain secret
                    exporter.debugState().path("parse_failures").asInt() shouldBe 3
                }
            }
            should("not quote an incoming message that the handler fails to handle") {
                LogCapture(ExporterTest::class.java.name).use { log ->
                    val failing = object : ExporterEventHandler {
                        override fun handleTranscriptionResult(event: TranscriptionResultEvent) =
                            throw IllegalStateException("handler failed")
                        override fun handleMediaEvent(event: MediaEvent) {}
                        override fun handleSendingChange(sourceName: String, sending: Boolean, timestamp: Long) {}
                        override fun handleProducerConnected(sourceNames: List<String>) {}
                        override fun getAudioSourceName(ssrc: Long): String? = null
                        override fun getDiarize(ssrc: Long): Boolean = false
                    }
                    val (exporter, _) = fixture(handler = failing)

                    exporter.handleIncomingMessage("""{"event":"transcription-result","text":"$secret"}""")

                    log.text shouldContain "Failed to handle incoming websocket message of type transcription-result"
                    log.text shouldNotContain secret
                }
            }
            should("not log the audio of an event that cannot be sent") {
                LogCapture(ExporterTest::class.java.name).use { log ->
                    val (exporter, _) = fixture()

                    exporter.sendEvent(MediaEvent(7, Media("tag-a0", 1, 2, secret)))

                    log.text shouldContain "Not connected, cannot send event"
                    log.text shouldContain "tag-a0"
                    log.text shouldNotContain secret
                }
            }
        }
    }
}
