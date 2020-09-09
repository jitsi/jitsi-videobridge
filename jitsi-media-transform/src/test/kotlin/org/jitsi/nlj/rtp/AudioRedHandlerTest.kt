/*
 * Copyright @ 2020 - present 8x8, Inc.
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
package org.jitsi.nlj.rtp

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.Spec
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeTypeOf
import org.jitsi.config.useNewConfig
import org.jitsi.metaconfig.MetaconfigSettings
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.format.AudioRedPayloadType
import org.jitsi.nlj.transform.node.AudioRedHandler
import org.jitsi.nlj.transform.node.ConsumerNode
import org.jitsi.nlj.transform.node.RedPolicy
import org.jitsi.nlj.util.StreamInformationStoreImpl
import org.jitsi.rtp.rtp.RtpPacket

/**
 * Tests the handling and generation of RED packets with two simple streams. One is a stream of Opus packets with
 * sequence numbers 1, 2, 3, 5, 6 (packet 4 is lost). The other is a stream of RED packets with the same sequence
 * numbers and redundancy with distance 2 whenever available.
 */
class AudioRedHandlerTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf
    override fun beforeSpec(spec: Spec) {
        super.beforeSpec(spec)
        MetaconfigSettings.cacheEnabled = false
    }

    private inline fun withNewConfig(config: String, block: () -> Unit) {
        useNewConfig("new-${this::class.simpleName}", config, true, block)
    }

    private val streamInformationStore = StreamInformationStoreImpl()

    init {
        context("Policy STRIP") {
            withNewConfig("jmt.audio.red.policy=STRIP") {
                val redHandler = AudioRedHandler(streamInformationStore)
                redHandler.config.policy shouldBe RedPolicy.STRIP

                context("Target supports RED") {
                    streamInformationStore.addRtpPayloadType(AudioRedPayloadType(112))
                    redHandler.redPayloadType shouldBe 112

                    context("Receiving RED") {
                        val result = redHandler.processRedPackets()

                        // We should strip RED from the stream and recover the lost packet
                        result.shouldBeTheExpectedOpusStream(packet4isPresent = true)
                    }
                    context("Receiving Opus") {
                        val result = redHandler.processOpusPackets()

                        // We should forward the Opus packets without encapsulation, the lost packet is not recovered
                        result.shouldBeTheExpectedOpusStream(packet4isPresent = false)
                    }
                }
                context("Target does not support RED") {
                    redHandler.redPayloadType shouldBe null

                    context("Receiving RED") {
                        val result = redHandler.processRedPackets()

                        // We should strip RED from the stream and recover the lost packet
                        result.shouldBeTheExpectedOpusStream(packet4isPresent = true)
                    }
                    context("Receiving Opus") {
                        val result = redHandler.processOpusPackets()

                        // We should forward the Opus packets without encapsulation, the lost packet is not recovered
                        result.shouldBeTheExpectedOpusStream(packet4isPresent = false)
                    }
                }
            }
        }
        context("Policy PROTECT_ALL") {
            withNewConfig("""
                jmt.audio.red.policy=PROTECT_ALL
                # TODO: add tests for the VAD-only case too.
                jmt.audio.red.vad-only=false
            """.trimIndent()) {
                val redHandler = AudioRedHandler(streamInformationStore)
                redHandler.config.policy shouldBe RedPolicy.PROTECT_ALL

                context("Target supports RED") {
                    streamInformationStore.addRtpPayloadType(AudioRedPayloadType(112))
                    redHandler.redPayloadType shouldBe 112

                    context("Receiving RED") {
                        val result = redHandler.processRedPackets()

                        // We should forward the RED without changes
                        result.shouldBeTheExpectedRedStream(packet4WasAvailable = true)
                    }
                    context("Receiving Opus") {
                        val result = redHandler.processOpusPackets()

                        // We should encapsulate the Opus packets in RED.
                        result.shouldBeTheExpectedRedStream(packet4WasAvailable = false)
                    }
                }
                context("Target does not support RED") {
                    redHandler.redPayloadType shouldBe null

                    context("Receiving RED") {
                        val result = redHandler.processRedPackets()

                        // We should strip RED from the stream and recover the lost packet
                        result.shouldBeTheExpectedOpusStream(packet4isPresent = true)
                    }
                    context("Receiving Opus") {
                        val result = redHandler.processOpusPackets()

                        // We should forward the Opus packets without encapsulation, the lost packet is not recovered
                        result.shouldBeTheExpectedOpusStream(packet4isPresent = false)
                    }
                }
            }
        }
    }

    private fun AudioRedHandler.processPackets(packets: List<RtpPacket>): List<RtpPacket> {
        val collector = CollectorNode()
        attach(collector)
        packets.forEach { processPacket(PacketInfo(it)) }
        detachNext()
        return collector.result
    }

    /**
     * Process the sample stream of RED packets.
     */
    private fun AudioRedHandler.processRedPackets() = processPackets(
        listOf(
            redPackets[0],
            redPackets[1],
            redPackets[2],
            redPackets[3],
            // 4 is lost
            redPackets[5],
            redPackets[6]
        )
    )

    /**
     * Process the sample stream of Opus packets.
     */
    private fun AudioRedHandler.processOpusPackets() = processPackets(
        listOf(
            audioPackets[0],
            audioPackets[1],
            audioPackets[2],
            audioPackets[3],
            // 4 is lost
            audioPackets[5],
            audioPackets[6]
        )
    )

    private class CollectorNode : ConsumerNode("Collector") {
        val result = mutableListOf<RtpPacket>()
        override fun consume(packetInfo: PacketInfo) {
            result.add(packetInfo.packetAs())
        }

        override fun trace(f: () -> Unit) {}
    }

    private val redPt = 112

    private val audioPacketBytes = org.jitsi.rtp.extensions.bytearray.byteArrayOf(
        // RTP Header. PT=111, seq=0x7b2b
        0x90, 0x6f, 0x7b, 0x2b,
        // TS
        0x44, 0xb2, 0x83, 0x6e,
        // SSRC
        0x16, 0x49, 0x3f, 0x2d,
        // Extension
        0xbe, 0xde, 0x00, 0x01,
        // ID=1, value=0xbd (VAD=true)
        0x10, 0xbd, 0x00, 0x00,

        // RTP Payload (length = 65)
        0x68, 0x2f, 0x3b, 0x25,
        0xab, 0x1a, 0x72, 0xfb,
        0x75, 0x2d, 0xf0, 0x9b,
        0xa2, 0xa3, 0xfc, 0x20,
        0x51, 0xf7, 0x9c, 0xe8,
        0x75, 0xe9, 0x02, 0xd6,
        0xc1, 0xe4, 0xa1, 0x51,
        0x02, 0x00, 0x59, 0x55,
        0x04, 0x56, 0x5e, 0xed,
        0x31, 0x55, 0xb5, 0x04,
        0x9d, 0xf6, 0x1c, 0x40,
        0x7b, 0xb7, 0x00, 0x0c,
        0xd9, 0x7b, 0x5d, 0x13,
        0x4c, 0xeb, 0x7d, 0xf1,
        0x74, 0xf8, 0xd5, 0xb9,
        0x07, 0xda, 0x18, 0x19,
        0x92
    )

    private fun createAudioPacket(seq: Int) = AudioRtpPacket(audioPacketBytes.clone(), 0, audioPacketBytes.size).apply {
        sequenceNumber = seq
        timestamp = seq * 960.toLong()
        // Encode the SEQ in the payload
        buffer[payloadOffset] = seq.toByte()
    }

    /**
     * Get the ID encoded in the payload of the audio packets we create (since the sequence number might be lost)
     */
    private fun RtpPacket.getPacketId(): Int = buffer[payloadOffset].toInt()

    private val audioPackets = List(7) { i -> createAudioPacket(i) }
    private val redPackets = List(7) { i ->
        when (i) {
            0 -> RedAudioRtpPacket.builder.build(redPt, audioPackets[0].clone(), emptyList())
            1 -> RedAudioRtpPacket.builder.build(redPt, audioPackets[1].clone(), listOf(audioPackets[0]))
            else -> RedAudioRtpPacket.builder.build(
                redPt,
                audioPackets[i].clone(),
                listOf(audioPackets[i - 2], audioPackets[i - 1])
            )
        }
    }

    private fun List<RtpPacket>.shouldBeTheExpectedRedStream(
        /**
         * Whether the packet with sequence number 4 was available when the RED stream was encoded (it would have been
         * available to the original encoder, but not to the SFU receiving a partial opus stream).
         */
        packet4WasAvailable: Boolean
    ) {
        size shouldBe 6
        map { it.sequenceNumber }.toList() shouldBe listOf(0, 1, 2, 3, /* 4 is lost */ 5, 6)
        forEach {
            it.payloadType shouldBe 112
            it.shouldBeTypeOf<RedAudioRtpPacket>()
            it as RedAudioRtpPacket

            val parsedRedundancy = it.removeRedAndGetRedundancyPackets()
            when (it.sequenceNumber) {
                0 -> parsedRedundancy.size shouldBe 0
                1 -> {
                    parsedRedundancy.size shouldBe 1
                    parsedRedundancy[0].sequenceNumber shouldBe 0
                    parsedRedundancy[0].getPacketId() shouldBe 0
                }
                2, 3 -> {
                    parsedRedundancy.size shouldBe 2
                    parsedRedundancy[0].sequenceNumber shouldBe it.sequenceNumber - 2
                    parsedRedundancy[0].getPacketId() shouldBe it.sequenceNumber - 2
                    parsedRedundancy[1].sequenceNumber shouldBe it.sequenceNumber - 1
                    parsedRedundancy[1].getPacketId() shouldBe it.sequenceNumber - 1
                }
                5 -> {
                    if (packet4WasAvailable) {
                        parsedRedundancy.size shouldBe 2
                        parsedRedundancy[0].sequenceNumber shouldBe 3
                        parsedRedundancy[0].getPacketId() shouldBe 3
                        parsedRedundancy[1].sequenceNumber shouldBe 4
                        parsedRedundancy[1].getPacketId() shouldBe 4
                    } else {
                        // When 4 was unavailable the encoder should have produced no redundancy.
                        parsedRedundancy.size shouldBe 0
                    }
                }
                6 -> {
                    if (packet4WasAvailable) {
                        parsedRedundancy.size shouldBe 2
                        parsedRedundancy[0].sequenceNumber shouldBe 4
                        parsedRedundancy[0].getPacketId() shouldBe 4
                        parsedRedundancy[1].sequenceNumber shouldBe 5
                        parsedRedundancy[1].getPacketId() shouldBe 5
                    } else {
                        parsedRedundancy.size shouldBe 1
                        parsedRedundancy[0].sequenceNumber shouldBe 5
                        parsedRedundancy[0].getPacketId() shouldBe 5
                    }
                }
            }
        }
    }

    private fun List<RtpPacket>.shouldBeTheExpectedOpusStream(
        /**
         * Whether the packet with sequence number 4 should be present in the stream.
         */
        packet4isPresent: Boolean
    ) {
        size shouldBe if (packet4isPresent) 7 else 6
        forEach {
            it.payloadType shouldBe 111
            it.shouldBeTypeOf<AudioRtpPacket>()
        }

        val expectedSequenceNumbers = if (packet4isPresent) listOf(0, 1, 2, 3, 4, 5, 6) else listOf(0, 1, 2, 3, 5, 6)
        map { it.sequenceNumber }.toList() shouldBe expectedSequenceNumbers
    }
}
