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
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.eclipse.jetty.websocket.api.Session
import org.jitsi.config.withNewConfig
import org.jitsi.mediajson.MediaEvent
import org.jitsi.mediajson.TranscriptionResultEvent
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.rtp.AudioRtpPacket
import org.jitsi.utils.concurrent.FakeScheduledExecutorService
import org.jitsi.utils.logging2.LoggerImpl
import org.jitsi.videobridge.util.TaskPools
import org.jitsi.xmpp.extensions.colibri2.Connect
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Drives [Exporter] directly (no socket). Verifies how inbound mediajson `start`/`stop` events map to the
 * synthetic-source sending-change callback (a start/stop with a talk timestamp fires the callback, start ->
 * sending=true, stop -> sending=false; one without a timestamp, i.e. a plain stream start/stop, does not), which
 * audio [Exporter.wants] to export given the connect's exports/requests lists, and which websocket close codes lead
 * to a reconnect.
 */
class ExporterTest : ShouldSpec() {
    private data class Change(val sourceName: String, val sending: Boolean, val timestamp: Long)

    /**
     * A fresh, not-yet-connected [Exporter] plus the list capturing its sending-change callback invocations. [sources]
     * backs the audio-source lookup (SSRC -> source name); any other SSRC is unknown (resolves to null).
     */
    private fun fixture(
        exports: List<String> = emptyList(),
        requests: List<String> = emptyList(),
        sources: Map<Long, String> = emptyMap(),
        type: Connect.Types = Connect.Types.RECORDER
    ): Pair<Exporter, MutableList<Change>> {
        val changes = mutableListOf<Change>()
        val exporter = Exporter(
            URI("ws://localhost:1/"),
            emptyMap(),
            LoggerImpl(javaClass.name),
            object : ExporterEventHandler {
                override fun handleTranscriptionResult(event: TranscriptionResultEvent) {}
                override fun handleMediaEvent(event: MediaEvent) {}
                override fun handleSendingChange(sourceName: String, sending: Boolean, timestamp: Long) {
                    changes.add(Change(sourceName, sending, timestamp))
                }
                override fun getAudioSourceName(ssrc: Long): String? = sources[ssrc]
                override fun getDiarize(ssrc: Long): Boolean = false
            },
            type = type,
            exports = exports,
            requests = requests
        )
        return exporter to changes
    }

    /** Mark [exporter] connected through a mock session (no socket), returning the session to verify what it sent. */
    private fun connect(exporter: Exporter): Session =
        mockk<Session>(relaxed = true).also { exporter.recorderWebSocket.session = it }

    private fun audioPacket(ssrc: Long) = PacketInfo(AudioRtpPacket(ByteArray(1500), 0, 100).apply { this.ssrc = ssrc })

    /** Replace the shared scheduler with a fake, so reconnects are observable as pending jobs and never dial out. */
    private fun fakeScheduler() = FakeScheduledExecutorService().also { TaskPools.SCHEDULED_POOL = it }

    private fun Exporter.close(code: Int, reason: String) = recorderWebSocket.onWebSocketClose(code, reason)

    init {
        afterSpec { TaskPools.resetScheduledPool() }

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

            should("export all audio when there are no requests and exports is empty (transcriber/recorder)") {
                val (exporter, _) = fixture(sources = sources)
                connect(exporter)
                exporter.wants(audioPacket(s1)) shouldBe true
                exporter.wants(audioPacket(s2)) shouldBe true
            }
            should("export nothing when there are requests but exports is empty (voice agent)") {
                val (exporter, _) = fixture(requests = listOf("agent-a0"), sources = sources)
                connect(exporter)
                exporter.wants(audioPacket(s1)) shouldBe false
                exporter.wants(audioPacket(s2)) shouldBe false
            }
            should("export only the listed sources when there are requests and exports") {
                val (exporter, _) =
                    fixture(exports = listOf("ep1-a0"), requests = listOf("agent-a0"), sources = sources)
                connect(exporter)
                exporter.wants(audioPacket(s1)) shouldBe true
                exporter.wants(audioPacket(s2)) shouldBe false
            }
            should("start exporting a source once a live update adds it to exports") {
                val (exporter, _) = fixture(requests = listOf("agent-a0"), sources = sources)
                val session = connect(exporter)
                exporter.wants(audioPacket(s1)) shouldBe false

                exporter.update(exports = listOf("ep1-a0"), requests = listOf("agent-a0"))

                exporter.wants(audioPacket(s1)) shouldBe true
                exporter.wants(audioPacket(s2)) shouldBe false
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
                all.wants(audioPacket(unknown)) shouldBe false
                val (listed, _) = fixture(exports = listOf("ep1-a0"), requests = listOf("agent-a0"), sources = sources)
                connect(listed)
                listed.wants(audioPacket(unknown)) shouldBe false
            }
            should("export nothing while not connected") {
                val (exporter, _) = fixture(sources = sources)
                exporter.wants(audioPacket(s1)) shouldBe false
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
            should("cap reconnects at agent-max-reconnect-attempts only for agent connects") {
                withNewConfig("videobridge.exporter.agent-max-reconnect-attempts = 2") {
                    fakeScheduler()
                    val (agent, _) = fixture(requests = listOf("agent-a0"), type = Connect.Types.AGENT)
                    val (transcriber, _) = fixture()

                    repeat(3) {
                        agent.close(1006, "abnormal closure")
                        transcriber.close(1006, "abnormal closure")
                    }

                    agent.debugState().path("reconnect_exhausted").asBoolean() shouldBe true
                    val transcriberState = transcriber.debugState()
                    transcriberState.path("reconnect_exhausted").asBoolean() shouldBe false
                    transcriberState.path("reconnect_attempts").asInt() shouldBe 3
                }
            }
        }
    }
}
