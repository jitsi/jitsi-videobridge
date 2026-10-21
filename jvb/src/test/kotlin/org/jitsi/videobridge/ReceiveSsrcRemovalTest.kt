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

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.jitsi.ConfigTest
import org.jitsi.nlj.DebugStateMode
import org.jitsi.nlj.MediaSourceDesc
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.RtpEncodingDesc
import org.jitsi.nlj.RtpLayerDesc
import org.jitsi.nlj.rtp.AudioRtpPacket
import org.jitsi.nlj.rtp.codec.vpx.VpxRtpLayerDesc
import org.jitsi.utils.MediaType
import org.jitsi.videobridge.relay.AudioSourceDesc
import org.jxmpp.jid.impl.JidCreate

/**
 * Tests that a receiver which uses SSRC rewriting forgets the remote SSRCs of sources that are removed, so that their
 * state is not kept, and a later packet from such an SSRC is looked up (and logged) again.
 */
class ReceiveSsrcRemovalTest : ConfigTest() {
    private val videobridge = mockk<Videobridge>(relaxed = true)
    private val name = JidCreate.entityBareFrom("roomName@somedomain.com")

    init {
        context("A received audio SSRC") {
            should("be forgotten when its source is removed") {
                withEndpoints { sender, receiver ->
                    sender.audioSources = listOf(AudioSourceDesc(AUDIO_SSRC, "sender", "sender-a0"))
                    receiver.preProcess(audioPacketInfo(AUDIO_SSRC)).shouldNotBeNull()
                    receiver.numReceivedAudioSsrcs() shouldBe 1

                    sender.audioSources = emptyList()
                    receiver.numReceivedAudioSsrcs() shouldBe 0
                }
            }
            should("be forgotten when the sender expires") {
                withEndpoints { sender, receiver ->
                    sender.audioSources = listOf(AudioSourceDesc(AUDIO_SSRC, "sender", "sender-a0"))
                    receiver.preProcess(audioPacketInfo(AUDIO_SSRC)).shouldNotBeNull()
                    receiver.numReceivedAudioSsrcs() shouldBe 1

                    sender.expire()
                    receiver.numReceivedAudioSsrcs() shouldBe 0
                }
            }
        }
        context("An unknown SSRC which is signaled and then removed") {
            should("be logged again (audio)") {
                withEndpoints { sender, receiver ->
                    receiver.findAudioSourceProps(AUDIO_SSRC).shouldBeNull()
                    receiver.loggedUnknownSsrcs shouldContain AUDIO_SSRC

                    sender.audioSources = listOf(AudioSourceDesc(AUDIO_SSRC, "sender", "sender-a0"))
                    receiver.findAudioSourceProps(AUDIO_SSRC).shouldNotBeNull()

                    sender.audioSources = emptyList()
                    receiver.loggedUnknownSsrcs shouldNotContain AUDIO_SSRC
                }
            }
            should("be logged again (video)") {
                withEndpoints { sender, receiver ->
                    receiver.findVideoSourceProps(VIDEO_SSRC).shouldBeNull()
                    receiver.loggedUnknownSsrcs shouldContain VIDEO_SSRC

                    sender.addReceiveSsrc(VIDEO_SSRC, MediaType.VIDEO)
                    sender.mediaSources = arrayOf(videoSource(VIDEO_SSRC))
                    receiver.findVideoSourceProps(VIDEO_SSRC).shouldNotBeNull()

                    sender.mediaSources = emptyArray()
                    receiver.loggedUnknownSsrcs shouldNotContain VIDEO_SSRC
                }
            }
        }
        context("An audio SSRC whose endpoint is known but whose source is not") {
            should("be logged") {
                withEndpoints { sender, receiver ->
                    sender.audioSources = listOf(AudioSourceDesc(AUDIO_SSRC, "sender", "sender-a0"))
                    sender.audioSources = emptyList()

                    receiver.findAudioSourceProps(AUDIO_SSRC).shouldBeNull()
                    receiver.loggedUnknownSsrcs shouldContain AUDIO_SSRC
                }
            }
        }
    }

    /**
     * Creates a conference with a sender and a receiver that uses SSRC rewriting, and calls [block] with them. The
     * sender has [AUDIO_SSRC] as a receive SSRC, but no sources.
     */
    private fun withEndpoints(block: (Endpoint, Endpoint) -> Unit) {
        val conference = Conference(videobridge, "id", name, null, false)
        try {
            val sender = conference.createLocalEndpoint("sender", true, false, false, false, false, false, false)
            val receiver = conference.createLocalEndpoint("receiver", true, true, false, false, false, false, false)
            sender.addReceiveSsrc(AUDIO_SSRC, MediaType.AUDIO)
            block(sender, receiver)
        } finally {
            conference.expire()
        }
    }

    private fun Endpoint.preProcess(packetInfo: PacketInfo): PacketInfo? =
        transceiver.rtpSender.preProcesor!!(packetInfo)

    private fun Endpoint.numReceivedAudioSsrcs(): Int =
        debugState(DebugStateMode.FULL)["audio_ssrcs"]["received"].asInt()

    private fun audioPacketInfo(ssrc: Long): PacketInfo {
        val buffer = ByteArray(28)
        val packet = AudioRtpPacket(buffer, 0, buffer.size).apply {
            version = 2
            this.ssrc = ssrc
            sequenceNumber = 1
            timestamp = 960
        }
        return PacketInfo(packet)
    }

    private fun videoSource(ssrc: Long) = MediaSourceDesc(
        arrayOf(RtpEncodingDesc(ssrc, arrayOf<RtpLayerDesc>(VpxRtpLayerDesc(0, 0, -1, 180, 30.0)))),
        "sender",
        "sender-v0"
    )

    companion object {
        private const val AUDIO_SSRC = 1111L
        private const val VIDEO_SSRC = 2222L
    }
}
