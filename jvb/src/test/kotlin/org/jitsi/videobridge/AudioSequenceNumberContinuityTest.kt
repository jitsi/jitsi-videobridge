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
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import org.jitsi.ConfigTest
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.rtp.AudioRtpPacket
import org.jitsi.rtp.util.RtpUtils
import org.jitsi.utils.MediaType
import org.jitsi.videobridge.message.ReceiverAudioSubscriptionMessage
import org.jitsi.videobridge.relay.AudioSourceDesc
import org.jxmpp.jid.impl.JidCreate

/**
 * Tests that a receiver gets contiguous audio sequence numbers when the bridge does not forward some audio packets to
 * it (for example because the receiver's audio subscription excluded the source for some time). A gap of 2^15 or more
 * packets makes the receiver's SRTP index estimation (RFC 3711, section 3.3.1) treat the next packets as old, and it
 * drops them.
 */
class AudioSequenceNumberContinuityTest : ConfigTest() {
    private val videobridge = mockk<Videobridge>(relaxed = true)
    private val name = JidCreate.entityBareFrom("roomName@somedomain.com")

    init {
        listOf(false, true).forEach { doSsrcRewriting ->
            context("A receiver with doSsrcRewriting=$doSsrcRewriting") {
                should("get contiguous sequence numbers after a long run of packets that it did not want") {
                    withReceiver(doSsrcRewriting) { receiver ->
                        var lastSent = -1
                        (100 until 110).forEach { lastSent = receiver.sendAudio(it) }

                        // 40000 packets is more than 2^15.
                        (110 until 40110).forEach {
                            receiver.wants(audioPacketInfo(it)).shouldBeFalse()
                        }

                        receiver.sendAudio(40110) shouldBe RtpUtils.applySequenceNumberDelta(lastSent, 1)
                        receiver.sendAudio(40111) shouldBe RtpUtils.applySequenceNumberDelta(lastSent, 2)
                    }
                }
                should("get contiguous sequence numbers after its audio subscription excluded the source") {
                    withReceiver(doSsrcRewriting) { receiver, conference, _ ->
                        // The transport of the test endpoint is not connected. Make wants() check the subscription.
                        receiver.acceptAudio = true
                        val connected = spyk(receiver, recordPrivateCalls = true)
                        every { connected["isTransportConnected"]() } returns true

                        var lastSent = -1
                        (100 until 110).forEach {
                            connected.wants(audioPacketInfo(it)).shouldBeTrue()
                            lastSent = receiver.sendAudio(it)
                        }

                        conference.setEndpointAudioSubscription(
                            "receiver",
                            ReceiverAudioSubscriptionMessage(all = true, exclude = listOf("sender-a0"))
                        )
                        (110 until 40110).forEach {
                            connected.wants(audioPacketInfo(it)).shouldBeFalse()
                        }

                        conference.setEndpointAudioSubscription(
                            "receiver",
                            ReceiverAudioSubscriptionMessage(all = true)
                        )
                        connected.wants(audioPacketInfo(40110)).shouldBeTrue()
                        receiver.sendAudio(40110) shouldBe RtpUtils.applySequenceNumberDelta(lastSent, 1)
                    }
                }
                should("keep the gap of a packet loss before the bridge") {
                    withReceiver(doSsrcRewriting) { receiver, _, _ ->
                        val first = receiver.sendAudio(100)
                        receiver.sendAudio(105) shouldBe RtpUtils.applySequenceNumberDelta(first, 5)
                    }
                }
                should("keep the offset when the source is removed and added again") {
                    withReceiver(doSsrcRewriting) { receiver, _, sender ->
                        receiver.sendAudio(100)
                        (101 until 40101).forEach {
                            receiver.wants(audioPacketInfo(it)).shouldBeFalse()
                        }
                        val lastSent = receiver.sendAudio(40101)

                        sender.audioSources = emptyList()
                        sender.audioSources = listOf(AudioSourceDesc(AUDIO_SSRC, "sender", "sender-a0"))

                        receiver.sendAudio(40102) shouldBe RtpUtils.applySequenceNumberDelta(lastSent, 1)
                    }
                }
            }
        }
    }

    /**
     * Creates a conference with a sender of [AUDIO_SSRC] and a receiver, and calls [block] with the receiver, the
     * conference and the sender.
     */
    private fun withReceiver(doSsrcRewriting: Boolean, block: (Endpoint, Conference, Endpoint) -> Unit) {
        val conference = Conference(videobridge, "id", name, null, false)
        try {
            val sender = conference.createLocalEndpoint("sender", true, false, false, false, false, false, false)
            val receiver =
                conference.createLocalEndpoint("receiver", true, doSsrcRewriting, false, false, false, false, false)
            sender.addReceiveSsrc(AUDIO_SSRC, MediaType.AUDIO)
            sender.audioSources = listOf(AudioSourceDesc(AUDIO_SSRC, "sender", "sender-a0"))
            block(receiver, conference, sender)
        } finally {
            conference.expire()
        }
    }

    private fun withReceiver(doSsrcRewriting: Boolean, block: (Endpoint) -> Unit) =
        withReceiver(doSsrcRewriting) { receiver, _, _ -> block(receiver) }

    /**
     * Passes an audio packet with sequence number [seq] through the send pre-processor of the receiver, and returns
     * the sequence number of the packet that the receiver gets.
     */
    private fun Endpoint.sendAudio(seq: Int): Int {
        val packetInfo = transceiver.rtpSender.preProcesor!!(audioPacketInfo(seq))
        packetInfo.shouldNotBeNull()
        return (packetInfo.packet as AudioRtpPacket).sequenceNumber
    }

    private fun audioPacketInfo(seq: Int): PacketInfo {
        val buffer = ByteArray(28)
        val packet = AudioRtpPacket(buffer, 0, buffer.size).apply {
            version = 2
            ssrc = AUDIO_SSRC
            sequenceNumber = seq and 0xffff
            timestamp = 960L * seq
        }
        return PacketInfo(packet)
    }

    companion object {
        private const val AUDIO_SSRC = 1111L
    }
}
