/*
 * Copyright @ 2026 - present 8x8, Inc.
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

import io.kotest.assertions.nondeterministic.eventually
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import org.bouncycastle.tls.SRTPProtectionProfile
import org.jitsi.ConfigTest
import org.jitsi.nlj.MediaSourceDesc
import org.jitsi.nlj.PacketHandler
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.RtpEncodingDesc
import org.jitsi.nlj.RtpLayerDesc
import org.jitsi.nlj.format.RtxPayloadType
import org.jitsi.nlj.format.Vp8PayloadType
import org.jitsi.nlj.rtp.SsrcAssociationType
import org.jitsi.nlj.rtp.VideoRtpPacket
import org.jitsi.nlj.rtp.codec.vpx.VpxRtpLayerDesc
import org.jitsi.nlj.srtp.TlsRole
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.RtcpFbNackPacketBuilder
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.utils.MediaType
import org.jitsi.videobridge.message.ReceiverVideoConstraintsMessage
import org.jxmpp.jid.impl.JidCreate
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

/**
 * Tests that an endpoint is sent retransmissions as RTX, with and without SSRC rewriting.
 *
 * With SSRC rewriting, a packet sent to an endpoint carries a send SSRC in place of the SSRC of its source, and is
 * cached for retransmission with that SSRC. So the RTX SSRC to use is the RTX send SSRC which goes with it, not the
 * RTX SSRC of the source.
 */
class RtxTest : ConfigTest() {
    private val videobridge = mockk<Videobridge>(relaxed = true)
    private val name = JidCreate.entityBareFrom("roomName@somedomain.com")

    init {
        listOf(false, true).forEach { doSsrcRewriting ->
            context("An endpoint with doSsrcRewriting=$doSsrcRewriting") {
                should("receive a retransmission as RTX") {
                    withHarness(doSsrcRewriting) {
                        val media = forward(100, 101, 102)
                        val nacked = media[1]

                        nack(nacked)

                        eventually(5.seconds) { sent.size shouldBe media.size + 1 }
                        val retransmission = sent.last()
                        retransmission.payloadType shouldBe RTX_PAYLOAD_TYPE
                        retransmission.ssrc shouldBe rtxSsrc
                        // An RTX packet has the original sequence number before the payload.
                        retransmission.length shouldBe nacked.length + 2
                    }
                }
            }
        }
    }

    /**
     * A conference with a sender that has a video source with the SSRC [VIDEO_SSRC], and a receiver that the source
     * is forwarded to.
     */
    private class Harness(val sender: Endpoint, val receiver: Endpoint, val doSsrcRewriting: Boolean) {
        /** The receiver, with a transport that is connected, so that [Endpoint.wants] accepts video. */
        private val connected: Endpoint = spyk(receiver, recordPrivateCalls = true).also {
            every { it["isTransportConnected"]() } returns true
        }

        /** The packets the receiver was sent, as they leave its transceiver. */
        val sent = CopyOnWriteArrayList<RtpPacket>()

        init {
            receiver.transceiver.setOutgoingPacketHandler(object : PacketHandler {
                override fun processPacket(packetInfo: PacketInfo) {
                    val packet = packetInfo.packet
                    sent.add(RtpPacket(packet.buffer.copyOf(), packet.offset, packet.length))
                }
            })
        }

        /** The SSRC the receiver gets the media of the source with. */
        val mediaSsrc: Long
            get() = sent.first().ssrc

        /** The SSRC the receiver was told to expect retransmissions of the source with. */
        val rtxSsrc: Long
            get() = if (doSsrcRewriting) {
                receiver.transceiver.readOnlyStreamInformationStore
                    .getRemoteSecondarySsrc(mediaSsrc, SsrcAssociationType.RTX)!!
            } else {
                RTX_SSRC
            }

        /**
         * Forwards packets of the source with these sequence numbers to the receiver, and returns them as the receiver
         * was sent them.
         */
        suspend fun forward(vararg sequenceNumbers: Int): List<RtpPacket> {
            val before = sent.size
            sequenceNumbers.forEach { sequenceNumber ->
                val buffer = ByteArray(1500)
                val packet = VideoRtpPacket(buffer, 0, PACKET_LENGTH, encodingId = 0).apply {
                    version = 2
                    payloadType = VIDEO_PAYLOAD_TYPE
                    ssrc = VIDEO_SSRC
                    this.sequenceNumber = sequenceNumber
                    timestamp = 3000L * sequenceNumber
                }
                val packetInfo = PacketInfo(packet)
                connected.wants(packetInfo).shouldBeTrue()
                receiver.send(packetInfo)
            }
            // The receiver sends from a queue, on another thread.
            eventually(5.seconds) { sent.size shouldBe before + sequenceNumbers.size }
            return sent.drop(before).also { media ->
                media.map { it.payloadType } shouldContainExactly sequenceNumbers.map { VIDEO_PAYLOAD_TYPE }
                media.map { it.ssrc }.toSet().size shouldBe 1
                if (doSsrcRewriting) {
                    mediaSsrc shouldNotBe VIDEO_SSRC
                } else {
                    mediaSsrc shouldBe VIDEO_SSRC
                }
            }
        }

        /** Has the receiver ask for [packet] again, a packet it was sent. */
        fun nack(packet: RtpPacket) {
            val nack = RtcpFbNackPacketBuilder(
                mediaSourceSsrc = packet.ssrc,
                missingSeqNums = sortedSetOf(packet.sequenceNumber)
            ).build()
            receiver.transceiver.rtcpEventNotifier.notifyRtcpReceived(nack, null)
        }
    }

    private suspend fun withHarness(doSsrcRewriting: Boolean, block: suspend Harness.() -> Unit) {
        val conference = Conference(videobridge, "id", name, null, false)
        try {
            val sender = conference.createLocalEndpoint("sender", true, false, false, false, false, false, false)
            val receiver =
                conference.createLocalEndpoint("receiver", true, doSsrcRewriting, false, false, false, false, false)
            sender.addReceiveSsrc(VIDEO_SSRC, MediaType.VIDEO)
            sender.mediaSources = arrayOf(
                MediaSourceDesc(
                    arrayOf(RtpEncodingDesc(VIDEO_SSRC, arrayOf<RtpLayerDesc>(VpxRtpLayerDesc(0, 0, -1, 180, 30.0)))),
                    "sender",
                    "sender-v0"
                )
            )
            receiver.addPayloadType(Vp8PayloadType(VIDEO_PAYLOAD_TYPE.toByte()))
            receiver.addPayloadType(
                RtxPayloadType(RTX_PAYLOAD_TYPE.toByte(), mapOf("apt" to VIDEO_PAYLOAD_TYPE.toString()))
            )
            // Signaling adds this association for the RTX SSRC of the source. A receiver that uses SSRC rewriting
            // never receives a packet with an SSRC of the source.
            receiver.onNewSsrcAssociation("sender", VIDEO_SSRC, RTX_SSRC, SsrcAssociationType.RTX)
            // Packets leave the transceiver once it can protect them. SRTP leaves the RTP header readable.
            receiver.transceiver.setSrtpInformation(
                SRTPProtectionProfile.SRTP_AES128_CM_HMAC_SHA1_80,
                TlsRole.SERVER,
                ByteArray(60),
                false
            )
            // The spy copies the fields, so set this first.
            receiver.acceptVideo = true
            // Allocate the source to the receiver. With SSRC rewriting, this maps the source to send SSRCs.
            receiver.setBandwidthAllocationSettings(ReceiverVideoConstraintsMessage())
            Harness(sender, receiver, doSsrcRewriting).block()
        } finally {
            conference.expire()
        }
    }

    companion object {
        private const val VIDEO_SSRC = 1111L
        private const val RTX_SSRC = 2222L
        private const val VIDEO_PAYLOAD_TYPE = 100
        private const val RTX_PAYLOAD_TYPE = 96

        /** The length of the packets of the source. */
        private const val PACKET_LENGTH = 200
    }
}
