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

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import org.jitsi.utils.logging2.LoggerImpl
import java.net.URI

/**
 * Drives [Exporter.handleIncomingMessage] directly (no socket) to verify how inbound mediajson `start`/`stop`
 * events map to the synthetic-source sending-change callback: a start/stop with a talk timestamp fires the callback
 * (start -> sending=true, stop -> sending=false); one without a timestamp (a plain stream start/stop) does not.
 */
class ExporterTest : ShouldSpec() {
    private data class Change(val sourceName: String, val sending: Boolean, val timestamp: Long)

    /** A fresh [Exporter] plus the list capturing its sending-change callback invocations. */
    private fun fixture(): Pair<Exporter, MutableList<Change>> {
        val changes = mutableListOf<Change>()
        val exporter = Exporter(
            URI("ws://localhost:1/"),
            emptyMap(),
            LoggerImpl(javaClass.name),
            { }, // handleTranscriptionResult
            { }, // handleMediaEvent
            { name, sending, timestamp -> changes.add(Change(name, sending, timestamp)) },
            { null }, // getAudioSourceName
            { false } // getDiarize
        )
        return exporter to changes
    }

    init {
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
    }
}
