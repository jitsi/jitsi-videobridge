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
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.jitsi.mediajson.Event
import org.jitsi.mediajson.StartEvent
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.format.OpusPayloadType
import org.jitsi.nlj.rtp.AudioRtpPacket

class MediaJsonSerializerTest : ShouldSpec() {
    init {
        context("The diarize flag on the start event") {
            should("be set to true when the resolver returns true for the source's SSRC") {
                val startEvent = capturedEvents(diarize = true).filterIsInstance<StartEvent>().single()
                startEvent.start.diarize shouldBe true
                startEvent.toJson().shouldContain("diarize")
            }
            should("be omitted (null) when the resolver returns false") {
                val startEvent = capturedEvents(diarize = false).filterIsInstance<StartEvent>().single()
                startEvent.start.diarize shouldBe null
                // With NON_NULL inclusion, a null diarize must not appear on the wire, so the transcriber falls back
                // to its global config.
                startEvent.toJson().shouldNotContain("diarize")
            }
        }
    }

    /** Runs a single audio packet through a serializer and returns the events it emitted. */
    private fun capturedEvents(diarize: Boolean): List<Event> {
        val events = mutableListOf<Event>()
        val serializer = MediaJsonSerializer(
            getSourceName = { "source-1" },
            getDiarize = { diarize }
        ) { events.add(it) }
        serializer.encode(audioPacketInfo(ssrc = 12345L, epId = "endpoint-1"))
        return events
    }

    private fun audioPacketInfo(ssrc: Long, epId: String): PacketInfo {
        val packet = AudioRtpPacket(ByteArray(1500), 0, 100).apply {
            this.ssrc = ssrc
            sequenceNumber = 1
            timestamp = 1000L
        }
        return PacketInfo(packet).apply {
            endpointId = epId
            payloadType = OpusPayloadType(111)
        }
    }
}
