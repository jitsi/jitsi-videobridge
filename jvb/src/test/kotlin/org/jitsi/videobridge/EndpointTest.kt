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

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldNotBe
import io.mockk.mockk
import org.jitsi.ConfigTest
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.rtp.AudioRtpPacket
import org.jitsi.utils.MediaType
import org.jitsi.videobridge.relay.AudioSourceDesc
import org.jxmpp.jid.impl.JidCreate

class EndpointTest : ConfigTest() {
    private val videobridge = mockk<Videobridge>(relaxed = true)
    private val name = JidCreate.entityBareFrom("roomName@somedomain.com")

    init {
        context("Audio SSRC rewriting in the send pre-processor") {
            should("rewrite audio from a signaled source") {
                withEndpoints { preProcess ->
                    val packetInfo = preProcess(audioPacketInfo(NAMED_SSRC))
                    packetInfo.shouldNotBeNull()
                    (packetInfo.packet as AudioRtpPacket).ssrc shouldNotBe NAMED_SSRC
                }
            }
            should("drop audio from a source with no name") {
                withEndpoints { preProcess ->
                    preProcess(audioPacketInfo(UNNAMED_SSRC)).shouldBeNull()
                }
            }
            should("drop audio from an SSRC that was not signaled") {
                withEndpoints { preProcess ->
                    preProcess(audioPacketInfo(UNKNOWN_SSRC)).shouldBeNull()
                }
            }
        }
    }

    /**
     * Creates a conference with a sender and a receiver that uses SSRC rewriting, and calls [block] with the
     * receiver's send pre-processor.
     */
    private fun withEndpoints(block: ((PacketInfo) -> PacketInfo?) -> Unit) {
        val conference = Conference(videobridge, "id", name, null, false)
        try {
            val sender = conference.createLocalEndpoint("sender", true, false, false, false, false, false)
            val receiver = conference.createLocalEndpoint("receiver", true, true, false, false, false, false)

            sender.addReceiveSsrc(NAMED_SSRC, MediaType.AUDIO)
            sender.addReceiveSsrc(UNNAMED_SSRC, MediaType.AUDIO)
            sender.audioSources = listOf(
                AudioSourceDesc(NAMED_SSRC, "sender", "sender-a0"),
                AudioSourceDesc(UNNAMED_SSRC, "sender", null)
            )

            block(receiver.transceiver.rtpSender.preProcesor!!)
        } finally {
            conference.expire()
        }
    }

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

    companion object {
        private const val NAMED_SSRC = 1111L
        private const val UNNAMED_SSRC = 2222L
        private const val UNKNOWN_SSRC = 3333L
    }
}
