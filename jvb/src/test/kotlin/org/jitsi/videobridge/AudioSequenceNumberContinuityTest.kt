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

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import org.jitsi.ConfigTest
import org.jitsi.config.withNewConfig
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.rtp.AudioRtpPacket
import org.jitsi.rtp.util.RtpUtils
import org.jitsi.utils.MediaType
import org.jitsi.videobridge.message.ReceiverAudioSubscriptionMessage
import org.jitsi.videobridge.relay.AudioSourceDesc
import org.jxmpp.jid.impl.JidCreate

/**
 * Tests that a receiver gets contiguous audio sequence numbers when its audio subscription excludes a source for some
 * time. A gap of more than 2^15 packets makes the receiver's SRTP index estimation (RFC 3711, section 3.3.1) treat the
 * next packets as old, and it drops them.
 *
 * [Harness.accept] and [Harness.process] are separate, so that a test can let a packet wait in the send queue while
 * other packets are accepted or dropped.
 */
class AudioSequenceNumberContinuityTest : ConfigTest() {
    private val videobridge = mockk<Videobridge>(relaxed = true)
    private val name = JidCreate.entityBareFrom("roomName@somedomain.com")

    init {
        listOf(false, true).forEach { doSsrcRewriting ->
            context("A receiver with doSsrcRewriting=$doSsrcRewriting") {
                should("get contiguous sequence numbers after a long exclusion") {
                    withHarness(doSsrcRewriting) {
                        var lastSent = -1
                        (100 until 110).forEach { lastSent = send(it) }

                        exclude()
                        // 40000 packets is more than 2^15.
                        dropRun(110 until 40110)
                        include()

                        send(40110) shouldBe next(lastSent, 1)
                        send(40111) shouldBe next(lastSent, 2)
                    }
                }
                should("close the gap when the source is excluded again before the resume packet is sent") {
                    withHarness(doSsrcRewriting) {
                        val lastSent = send(100)
                        exclude()
                        dropRun(101 until 40001)
                        include()
                        // 40001 waits in the send queue while 40002 is dropped.
                        val waiting = accept(40001)
                        exclude()
                        drop(40002)
                        include()

                        process(waiting) shouldBe next(lastSent, 1)
                        send(40003) shouldBe next(lastSent, 2)
                    }
                }
                should("close the gap when the first packet waits in the send queue while the exclusion starts") {
                    withHarness(doSsrcRewriting) {
                        val waiting = accept(100)
                        exclude()
                        dropRun(101 until 40001)
                        val lastSent = process(waiting)
                        lastSent.shouldNotBeNull()
                        include()

                        send(40001) shouldBe next(lastSent, 1)
                    }
                }
                should("drop a late packet that would reuse a sequence number") {
                    withHarness(doSsrcRewriting) {
                        val lastSent = send(100)
                        exclude()
                        drop(101)
                        include()
                        send(103) shouldBe next(lastSent, 1)
                        process(accept(102)).shouldBeNull()
                        send(104) shouldBe next(lastSent, 2)
                    }
                }
                should("keep the gap of a packet loss before the bridge") {
                    withHarness(doSsrcRewriting) {
                        val first = send(100)
                        send(105) shouldBe next(first, 5)
                    }
                }
            }
        }
        // With SSRC rewriting, the SSRC cache computes a new delta when a source is added again, so only a receiver
        // without SSRC rewriting depends on the state of the rewriter here.
        context("A receiver with doSsrcRewriting=false") {
            should("keep the offset when the source is removed and added again") {
                withHarness(false) {
                    send(100)
                    exclude()
                    dropRun(101 until 40101)
                    include()
                    val lastSent = send(40101)

                    sender.audioSources = emptyList()
                    sender.audioSources = listOf(AudioSourceDesc(AUDIO_SSRC, "sender", "sender-a0"))

                    send(40102) shouldBe next(lastSent, 1)
                }
            }
        }
        context("A receiver with doSsrcRewriting=true and one audio SSRC slot") {
            should("get contiguous sequence numbers when an excluded source gets the slot again") {
                withNewConfig("videobridge.ssrc-limit.audio = 1") {
                    withHarness(true) {
                        val other = conference.createLocalEndpoint(
                            "other",
                            true,
                            false,
                            false,
                            false,
                            false,
                            false,
                            false
                        )
                        other.addReceiveSsrc(OTHER_SSRC, MediaType.AUDIO)
                        other.audioSources = listOf(AudioSourceDesc(OTHER_SSRC, "other", "other-a0"))

                        (100 until 110).forEach { send(it) }
                        exclude()
                        dropRun(110 until 40110)

                        // The other source gets the only slot.
                        var lastSent = -1
                        (500 until 510).forEach { lastSent = send(it, OTHER_SSRC) }
                        val slotSsrc = lastSsrc

                        include()
                        send(40110) shouldBe next(lastSent, 1)
                        lastSsrc shouldBe slotSsrc
                    }
                }
            }
        }
    }

    private fun next(seq: Int, delta: Int) = RtpUtils.applySequenceNumberDelta(seq, delta)

    /**
     * A conference with a sender of [AUDIO_SSRC] and a receiver. [connected] is the receiver, with a transport that is
     * connected, so that [Endpoint.wants] checks the audio subscription.
     */
    private class Harness(val conference: Conference, val sender: Endpoint, val receiver: Endpoint) {
        val connected: Endpoint = spyk(receiver, recordPrivateCalls = true).also {
            every { it["isTransportConnected"]() } returns true
        }

        /** The SSRC of the last packet that [process] sent. */
        var lastSsrc = -1L

        fun exclude() = conference.setEndpointAudioSubscription(
            "receiver",
            ReceiverAudioSubscriptionMessage(all = true, exclude = listOf("sender-a0"))
        )

        fun include() = conference.setEndpointAudioSubscription(
            "receiver",
            ReceiverAudioSubscriptionMessage(all = true)
        )

        /** Passes a packet through [Endpoint.wants], which must accept it. */
        fun accept(seq: Int, ssrc: Long = AUDIO_SSRC): PacketInfo = audioPacketInfo(seq, ssrc).also {
            connected.wants(it).shouldBeTrue()
        }

        /** Passes a packet through [Endpoint.wants], which must reject it. */
        fun drop(seq: Int, ssrc: Long = AUDIO_SSRC) {
            connected.wants(audioPacketInfo(seq, ssrc)).shouldBeFalse()
        }

        /**
         * Drops the first and the last packet of [range], as if every packet of [range] was dropped. The rewriter does
         * not count drops, and the spy records every call, so a call for each packet would use too much memory.
         */
        fun dropRun(range: IntRange) {
            drop(range.first)
            drop(range.last)
        }

        /**
         * Passes [packetInfo] through the send pre-processor of the receiver. Returns the sequence number of the packet
         * that the receiver gets, or null if the packet is dropped.
         */
        fun process(packetInfo: PacketInfo): Int? {
            val result = receiver.transceiver.rtpSender.preProcesor!!(packetInfo) ?: return null
            val packet = result.packet as AudioRtpPacket
            lastSsrc = packet.ssrc
            return packet.sequenceNumber
        }

        /** Accepts a packet and sends it, and returns the sequence number that the receiver gets. */
        fun send(seq: Int, ssrc: Long = AUDIO_SSRC): Int {
            val sequenceNumber = process(accept(seq, ssrc))
            sequenceNumber.shouldNotBeNull()
            return sequenceNumber
        }

        private fun audioPacketInfo(seq: Int, ssrc: Long): PacketInfo {
            val buffer = ByteArray(28)
            val packet = AudioRtpPacket(buffer, 0, buffer.size).apply {
                version = 2
                this.ssrc = ssrc
                sequenceNumber = seq and 0xffff
                timestamp = 960L * seq
            }
            return PacketInfo(packet)
        }
    }

    private fun withHarness(doSsrcRewriting: Boolean, block: Harness.() -> Unit) {
        val conference = Conference(videobridge, "id", name, null, false)
        try {
            val sender = conference.createLocalEndpoint("sender", true, false, false, false, false, false, false)
            val receiver =
                conference.createLocalEndpoint("receiver", true, doSsrcRewriting, false, false, false, false, false)
            sender.addReceiveSsrc(AUDIO_SSRC, MediaType.AUDIO)
            sender.audioSources = listOf(AudioSourceDesc(AUDIO_SSRC, "sender", "sender-a0"))
            // The spy copies the fields, so set this first.
            receiver.acceptAudio = true
            Harness(conference, sender, receiver).block()
        } finally {
            conference.expire()
        }
    }

    companion object {
        private const val AUDIO_SSRC = 1111L
        private const val OTHER_SSRC = 2222L
    }
}
