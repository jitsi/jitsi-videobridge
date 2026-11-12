/*
 * Copyright @ 2026 - Present, 8x8, Inc.
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

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jitsi.mediajson.Media
import org.jitsi.mediajson.MediaEvent
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.format.OpusPayloadType
import org.jitsi.nlj.format.PayloadType
import org.jitsi.nlj.rtp.AudioRtpPacket
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.rtp.rtp.header_extensions.AudioLevelHeaderExtension
import org.jitsi.utils.logging2.LoggerImpl
import org.jitsi.utils.time.FakeClock
import org.jitsi.videobridge.SyntheticSourceRtpContinuity.Numbers
import org.jitsi.videobridge.message.BridgeChannelMessage
import org.jitsi.videobridge.message.SyntheticSourceSendingChangeEvent
import org.jitsi.videobridge.relay.AudioSourceDesc
import org.jitsi.videobridge.relay.SyntheticSourceKind
import java.time.Duration
import java.util.Base64
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord

/**
 * Drives a [SyntheticSourceInjector] with a fake conference: which media and sending changes it drops, the packets it
 * builds, and that the numbers of a source come from one [SyntheticSourceRtpContinuity] state, kept by SSRC.
 */
class SyntheticSourceInjectorTest : ShouldSpec() {
    init {
        context("Media for a source that the conference does not know") {
            should("be dropped, with one warning for each tag") {
                with(Fixture()) {
                    val warnings = warningsOf {
                        repeat(3) { media("unknown-a0.fr", it, it * 960L) }
                        media("other-a0.fr", 0, 0)
                    }
                    conference.packets.shouldBeEmpty()
                    warnings shouldHaveSize 2
                    warnings[0] shouldContain "translated media for unknown synthetic source: unknown-a0.fr"
                    warnings[1] shouldContain "translated media for unknown synthetic source: other-a0.fr"
                }
            }
            should("be dropped without a warning, when the source was removed") {
                with(Fixture()) {
                    add(TRANSLATION)
                    remove(TRANSLATION)
                    warningsOf { media(TRANSLATION, 0, 0) }.shouldBeEmpty()
                    conference.packets.shouldBeEmpty()
                }
            }
            should("be warned about, when the source was added again and is not known any more") {
                with(Fixture()) {
                    add(TRANSLATION)
                    remove(TRANSLATION)
                    add(TRANSLATION)
                    // A source that leaves with its endpoint is not reported as removed.
                    conference.sources.clear()
                    warningsOf { media(TRANSLATION, 0, 0) } shouldHaveSize 1
                }
            }
        }
        context("The warning for media with an unknown tag") {
            should("be logged again, when a source with that name was removed since") {
                with(Fixture()) {
                    warningsOf { repeat(2) { media(TRANSLATION, it, it * 960L) } } shouldHaveSize 1
                    add(TRANSLATION)
                    remove(TRANSLATION)
                    add(TRANSLATION)
                    // A source that leaves with its endpoint is not reported as removed.
                    conference.sources.clear()
                    warningsOf { repeat(2) { media(TRANSLATION, it, it * 960L) } } shouldHaveSize 1
                }
            }
        }
        context("The creation of an injected source") {
            should("be logged once, and again when the source was removed and added again") {
                with(Fixture()) {
                    val creations = {
                        logsOf(Level.INFO) { media(TRANSLATION, 0, 0) }.filter { "Creating injected" in it }
                    }

                    add(TRANSLATION)
                    val first = creations()
                    first shouldHaveSize 1
                    first[0] shouldContain "Creating injected synthetic source human-a0.fr (ssrc 1234)"
                    creations().shouldBeEmpty()
                    remove(TRANSLATION)
                    add(TRANSLATION)
                    creations() shouldHaveSize 1
                }
            }
        }
        context("Media when no Opus payload type is negotiated") {
            should("be dropped, with one warning") {
                with(Fixture()) {
                    add(TRANSLATION)
                    conference.opusPayloadType = null
                    val warnings = warningsOf { repeat(3) { media(TRANSLATION, it, it * 960L) } }
                    conference.packets.shouldBeEmpty()
                    warnings shouldHaveSize 1
                    warnings[0] shouldContain "no Opus payload type is negotiated"
                }
            }
        }
        context("A packet built from media") {
            should("have the SSRC of the source, the Opus payload type, the payload, and the numbers of the media") {
                with(Fixture()) {
                    add(TRANSLATION)
                    media(TRANSLATION, 7, 6720)

                    conference.packets shouldHaveSize 1
                    val packetInfo = conference.packets[0]
                    packetInfo.payloadType shouldBe OPUS
                    packetInfo.endpointId shouldBe null
                    val packet = packetInfo.packet.shouldBeInstanceOf<AudioRtpPacket>()
                    // The values that the packet caches, and the ones in its bytes.
                    listOf(packet, RtpPacket(packet.buffer, packet.offset, packet.length)).forEach {
                        it.version shouldBe 2
                        it.ssrc shouldBe TRANSLATION.ssrc
                        it.payloadType shouldBe OPUS.pt.toInt()
                        it.sequenceNumber shouldBe 7
                        it.timestamp shouldBe 6720
                        it.hasExtensions shouldBe false
                        it.payload() shouldBe PAYLOAD
                    }
                }
            }
            should("take its numbers from the continuity, which wraps them") {
                with(Fixture()) {
                    add(TRANSLATION)
                    media(TRANSLATION, 65_537, 0x1_0000_03C0L)
                    numbers(TRANSLATION) shouldBe listOf(Numbers(1, 0x3C0))
                }
            }
        }
        context("A producer whose connection opens again") {
            should("have the sequence numbers of its source continued, when it starts again from chunk 0") {
                with(Fixture()) {
                    add(TRANSLATION)
                    // Only the report of the connection shows this new start: the timestamps are later, and the
                    // chunk numbers are 2^15 or more behind.
                    media(TRANSLATION, 40_000, 38_400_000)
                    clock.elapse(Duration.ofSeconds(2))
                    injector.handleProducerConnected(listOf(TRANSLATION.name))
                    media(TRANSLATION, 0, 38_400_960)
                    media(TRANSLATION, 1, 38_401_920)

                    numbers(TRANSLATION) shouldBe
                        listOf(Numbers(40_000, 38_400_000), Numbers(40_001, 38_496_000), Numbers(40_002, 38_496_960))
                }
            }
            should("start again only the synthetic sources that are named, and ignore the other names") {
                with(Fixture()) {
                    add(HUMAN)
                    add(TRANSLATION)
                    add(AGENT)
                    media(TRANSLATION, 40_000, 38_400_000)
                    media(AGENT, 40_000, 38_400_000)
                    clock.elapse(Duration.ofSeconds(2))
                    warningsOf {
                        injector.handleProducerConnected(listOf(HUMAN.name, "unknown-a0", TRANSLATION.name))
                    }.shouldBeEmpty()
                    media(TRANSLATION, 0, 38_400_960)
                    media(AGENT, 0, 38_400_960)

                    numbers(TRANSLATION).last() shouldBe Numbers(40_001, 38_496_000)
                    numbers(AGENT).last() shouldBe Numbers(0, 38_400_960)
                }
            }
        }
        context("A sending change") {
            should("be on the timeline of the media of its source") {
                with(Fixture()) {
                    add(TRANSLATION)
                    (0..9).forEach { media(TRANSLATION, it, it * 960L) }
                    clock.elapse(Duration.ofSeconds(1))
                    injector.handleProducerConnected(listOf(TRANSLATION.name))
                    injector.handleSendingChange(TRANSLATION.name, true, 0)
                    media(TRANSLATION, 0, 0)
                    injector.handleSendingChange(TRANSLATION.name, false, 960)

                    // 1 s at 48 kHz after the last timestamp.
                    val start = 9 * 960L + 48_000
                    numbers(TRANSLATION).last() shouldBe Numbers(10, start)
                    changes() shouldBe listOf(
                        Change(TRANSLATION.name, true, start, SyntheticSourceKind.TRANSLATION),
                        Change(TRANSLATION.name, false, start + 960, SyntheticSourceKind.TRANSLATION)
                    )
                }
            }
            should("carry the kind of its source, and go to the relays too") {
                with(Fixture()) {
                    add(AGENT)
                    injector.handleSendingChange(AGENT.name, true, 4800)
                    changes() shouldBe listOf(Change(AGENT.name, true, 4800, SyntheticSourceKind.AGENT))
                    conference.sentToRelays shouldBe listOf(true)
                }
            }
            should("be dropped for a source that is not known, with a warning unless the source was removed") {
                with(Fixture()) {
                    add(TRANSLATION)
                    remove(TRANSLATION)
                    val warnings = warningsOf {
                        repeat(2) { injector.handleSendingChange("unknown-a0.fr", true, 0) }
                        injector.handleSendingChange(TRANSLATION.name, true, 0)
                    }
                    conference.messages.shouldBeEmpty()
                    warnings shouldHaveSize 1
                    warnings[0] shouldContain "sending-change for unknown synthetic source: unknown-a0.fr"
                }
            }
        }
        context("Several sources") {
            should("be numbered each on its own") {
                with(Fixture()) {
                    add(TRANSLATION)
                    add(AGENT)
                    media(TRANSLATION, 100, 96_000)
                    media(AGENT, 7, 0)
                    media(TRANSLATION, 101, 96_960)
                    // Behind the numbers of the other source, and still not a new start of this one.
                    media(AGENT, 8, 960)

                    numbers(TRANSLATION) shouldBe listOf(Numbers(100, 96_000), Numbers(101, 96_960))
                    numbers(AGENT) shouldBe listOf(Numbers(7, 0), Numbers(8, 960))
                }
            }
        }
        context("The numbers of a source") {
            should("continue when the source is removed and added again with the same SSRC") {
                with(Fixture()) {
                    add(TRANSLATION)
                    (0..9).forEach { media(TRANSLATION, it, it * 960L) }
                    remove(TRANSLATION)
                    add(TRANSLATION)
                    clock.elapse(Duration.ofSeconds(1))
                    media(TRANSLATION, 0, 0)
                    injector.handleSendingChange(TRANSLATION.name, false, 960)

                    numbers(TRANSLATION).last() shouldBe Numbers(10, 9 * 960L + 48_000)
                    changes().single().timestamp shouldBe 9 * 960L + 48_960
                }
            }
            should("continue when a source with another name has the same SSRC") {
                with(Fixture()) {
                    val renamed = TRANSLATION.copy(sourceName = "human-a0.de")
                    add(TRANSLATION)
                    (0..9).forEach { media(TRANSLATION, it, it * 960L) }
                    remove(TRANSLATION)
                    add(renamed)
                    clock.elapse(Duration.ofSeconds(1))
                    media(renamed, 0, 0)

                    numbers(renamed).last() shouldBe Numbers(10, 9 * 960L + 48_000)
                }
            }
            should("be those of the producer when a source with the same name has another SSRC") {
                with(Fixture()) {
                    val renewed = AGENT.copy(ssrc = 9999L)
                    add(AGENT)
                    (0..9).forEach { media(AGENT, it, it * 960L) }
                    remove(AGENT)
                    add(renewed)
                    clock.elapse(Duration.ofSeconds(1))
                    media(renewed, 0, 0)

                    numbers(renewed) shouldBe listOf(Numbers(0, 0))
                }
            }
        }
        context("The audio level of media") {
            should("be added as a header extension, when an ID is negotiated for it") {
                with(Fixture()) {
                    add(TRANSLATION)
                    conference.audioLevelExtensionId = AUDIO_LEVEL_EXTENSION_ID
                    media(TRANSLATION, 0, 0, audioLevel = 30, vad = true)

                    val packet = conference.packets.single().packet as RtpPacket
                    val extension = packet.getHeaderExtension(AUDIO_LEVEL_EXTENSION_ID).shouldNotBeNull()
                    AudioLevelHeaderExtension.getAudioLevel(extension) shouldBe 30
                    AudioLevelHeaderExtension.getVad(extension) shouldBe true
                    packet.payload() shouldBe PAYLOAD
                }
            }
            should("not be added, when the media has none") {
                with(Fixture()) {
                    add(TRANSLATION)
                    conference.audioLevelExtensionId = AUDIO_LEVEL_EXTENSION_ID
                    warningsOf { media(TRANSLATION, 0, 0) }.shouldBeEmpty()

                    (conference.packets.single().packet as RtpPacket).hasExtensions shouldBe false
                }
            }
            should("not be added, with one warning, when no ID is negotiated for it") {
                with(Fixture()) {
                    add(TRANSLATION)
                    val warnings = warningsOf { repeat(2) { media(TRANSLATION, it, it * 960L, audioLevel = 30) } }

                    conference.packets shouldHaveSize 2
                    conference.packets.forEach { (it.packet as RtpPacket).hasExtensions shouldBe false }
                    warnings shouldHaveSize 1
                    warnings[0] shouldContain "no ssrc-audio-level extension is negotiated"
                }
            }
        }
        context("Speech activity") {
            should("be fed with the audio level of the media of an agent") {
                with(Fixture()) {
                    add(AGENT)
                    media(AGENT, 0, 0, audioLevel = 30)
                    media(AGENT, 1, 960)

                    conference.agentAudioLevels shouldBe listOf(AGENT to 30)
                    conference.packets shouldHaveSize 2
                }
            }
            should("not be fed with the audio level of translated audio") {
                with(Fixture()) {
                    add(TRANSLATION)
                    media(TRANSLATION, 0, 0, audioLevel = 30)

                    conference.agentAudioLevels.shouldBeEmpty()
                    conference.packets shouldHaveSize 1
                }
            }
        }
        context("A failure") {
            should("be caught and logged, in each of the three calls") {
                with(Fixture()) {
                    add(TRANSLATION)
                    val warnings = warningsOf {
                        injector.handleMediaEvent(MediaEvent(0, Media(TRANSLATION.name, 0, 0, "not base64")))
                        conference.failingSinks = true
                        media(TRANSLATION, 1, 960)
                        injector.handleSendingChange(TRANSLATION.name, true, 0)
                        conference.failingLookup = true
                        injector.handleProducerConnected(listOf(TRANSLATION.name))
                    }
                    warnings shouldHaveSize 4
                    warnings[0] shouldContain "Failed to handle translated media for source ${TRANSLATION.name}"
                    warnings[1] shouldContain "Failed to handle translated media for source ${TRANSLATION.name}"
                    warnings[2] shouldContain "Failed to broadcast synthetic source sending change"
                    warnings[3] shouldContain "Failed to handle the new connection of the producer"
                }
            }
        }
    }

    /** A [SyntheticSourceInjector] with a fake conference and a fake clock. */
    private class Fixture {
        val clock = FakeClock()
        val conference = FakeConference()
        val injector = SyntheticSourceInjector(conference, clock, LoggerImpl("test"))

        /** Adds [source] the way the conference does: it knows the source, then it tells the injector. */
        fun add(source: AudioSourceDesc) {
            conference.sources.add(source)
            injector.onSourcesAdded(setOf(source))
        }

        fun remove(source: AudioSourceDesc) {
            conference.sources.remove(source)
            injector.onSourcesRemoved(setOf(source))
        }

        fun media(source: AudioSourceDesc, chunk: Int, timestamp: Long, audioLevel: Int? = null, vad: Boolean? = null) =
            media(source.name, chunk, timestamp, audioLevel, vad)

        fun media(tag: String, chunk: Int, timestamp: Long, audioLevel: Int? = null, vad: Boolean? = null) {
            val payload = Base64.getEncoder().encodeToString(PAYLOAD)
            injector.handleMediaEvent(MediaEvent(0, Media(tag, chunk, timestamp, payload, audioLevel, vad)))
        }

        /** The sequence number and the timestamp of each packet injected on the SSRC of [source]. */
        fun numbers(source: AudioSourceDesc) = conference.packets
            .map { it.packet as RtpPacket }
            .filter { it.ssrc == source.ssrc }
            .map { Numbers(it.sequenceNumber, it.timestamp) }

        fun changes() = conference.messages.map {
            val change = it.shouldBeInstanceOf<SyntheticSourceSendingChangeEvent>()
            Change(change.sourceName, change.sending, change.timestamp, change.kind)
        }
    }

    /** Looks up the sources it is given, and records what the injector passes to it. */
    private class FakeConference : SyntheticSourceInjector.ConferenceAccess {
        val sources = mutableSetOf<AudioSourceDesc>()
        override var opusPayloadType: PayloadType? = OPUS
        override var audioLevelExtensionId: Int? = null

        val packets = mutableListOf<PacketInfo>()
        val messages = mutableListOf<BridgeChannelMessage>()

        /** For each of [messages], whether it was to go to the relays. */
        val sentToRelays = mutableListOf<Boolean>()
        val agentAudioLevels = mutableListOf<Pair<AudioSourceDesc, Int>>()

        /** Whether taking a packet or a message fails. */
        var failingSinks = false

        /** Whether looking up a source fails. */
        var failingLookup = false

        override fun findSyntheticAudioSource(sourceName: String): AudioSourceDesc? {
            check(!failingLookup)
            return sources.firstOrNull { it.synthetic && it.sourceName == sourceName }
        }

        override fun handleIncomingPacket(packetInfo: PacketInfo) {
            check(!failingSinks)
            packets.add(packetInfo)
        }

        override fun broadcastMessage(message: BridgeChannelMessage, sendToRelays: Boolean) {
            check(!failingSinks)
            messages.add(message)
            sentToRelays.add(sendToRelays)
        }

        override fun feedAgentSpeechActivity(source: AudioSourceDesc, audioLevel: Int) {
            agentAudioLevels.add(source to audioLevel)
        }
    }

    private data class Change(
        val sourceName: String,
        val sending: Boolean,
        val timestamp: Long,
        val kind: SyntheticSourceKind?
    )
}

/** The warnings that a [SyntheticSourceInjector] logs while [block] runs. */
private fun warningsOf(block: () -> Unit): List<String> = logsOf(Level.WARNING, block)

/** What a [SyntheticSourceInjector] logs at [level] while [block] runs. */
private fun logsOf(level: Level, block: () -> Unit): List<String> {
    val warnings = mutableListOf<String>()
    val capture = object : Handler() {
        override fun publish(record: LogRecord) {
            if (record.level == level) {
                warnings.add(record.message)
            }
        }
        override fun flush() = Unit
        override fun close() = Unit
    }
    // The injector creates its own logger, so capture at the java.util.logging logger it delegates to. That one is
    // found by its name, the class name that createChildLogger gives it: under another name nothing is captured.
    val julLogger = java.util.logging.Logger.getLogger(SyntheticSourceInjector::class.java.name)
    julLogger.addHandler(capture)
    try {
        block()
    } finally {
        julLogger.removeHandler(capture)
    }
    return warnings
}

private fun RtpPacket.payload() = buffer.copyOfRange(payloadOffset, payloadOffset + payloadLength)

private val AudioSourceDesc.name: String
    get() = sourceName!!

private val OPUS = OpusPayloadType(111)
private val PAYLOAD = byteArrayOf(1, 2, 3, 4, 5)
private const val AUDIO_LEVEL_EXTENSION_ID = 1

private val HUMAN = AudioSourceDesc(1L, "human", "human-a0")
private val TRANSLATION =
    AudioSourceDesc(1234L, "human", "human-a0.fr", synthetic = true, kind = SyntheticSourceKind.TRANSLATION)
private val AGENT = AudioSourceDesc(5678L, "bot", "bot-a0", synthetic = true, kind = SyntheticSourceKind.AGENT)
