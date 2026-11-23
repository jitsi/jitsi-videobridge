/*
 * Copyright @ 2019 - present 8x8, Inc.
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
@file:Suppress("ktlint:standard:property-naming")

package org.jitsi.nlj.rtp.pacing

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.jitsi.nlj.util.DataSize
import org.jitsi.nlj.util.bytes
import org.jitsi.utils.NEVER
import org.jitsi.utils.logging2.createLogger
import org.jitsi.utils.ms
import java.time.Duration
import java.time.Instant

private const val kDefaultSsrc = 123L
private const val kDefaultPayloadSize = 789

/** The size of an RTP header without extensions, which the packets of the tests in WebRTC have. */
private const val kHeadersSize = 12

private fun createPacket(type: RtpPacketMediaType, seq: Int, ssrc: Long = kDefaultSsrc, isKeyFrame: Boolean = false) =
    RtpPacketToSend(
        packetType = type,
        ssrc = ssrc,
        sequenceNumber = seq,
        headersSize = kHeadersSize,
        payloadSize = kDefaultPayloadSize,
        isKeyFrame = isKeyFrame
    )

private fun createRetransmissionPacket(originalType: RtpPacketMediaType, seq: Int, ssrc: Long = kDefaultSsrc) =
    RtpPacketToSend(
        packetType = RtpPacketMediaType.kRetransmission,
        originalPacketType = if (originalType == RtpPacketMediaType.kVideo) {
            RtpPacketToSend.OriginalType.kVideo
        } else {
            RtpPacketToSend.OriginalType.kAudio
        },
        ssrc = ssrc,
        sequenceNumber = seq,
        headersSize = kHeadersSize,
        payloadSize = kDefaultPayloadSize
    )

private fun millis(ms: Long): Instant = Instant.ofEpochMilli(ms)

/** Unit tests for PrioritizedPacketQueue,
 * based on WebRTC modules/pacing/prioritized_packet_queue_unittest.cc in
 * WebRTC commit 42a497714596d8f726da4dae4fb4f133cbaa15df (main, 2026-11-15).
 */
class PrioritizedPacketQueueTest : FreeSpec() {
    private val logger = createLogger()

    init {
        "ReturnsPacketsInPrioritizedOrder" {
            val now = Instant.EPOCH
            val queue = PrioritizedPacketQueue(logger, now)

            // Add packets in low to high packet order.
            queue.push(now, createPacket(RtpPacketMediaType.kPadding, seq = 1))
            queue.push(now, createPacket(RtpPacketMediaType.kVideo, seq = 2))
            queue.push(now, createPacket(RtpPacketMediaType.kForwardErrorCorrection, seq = 3))
            queue.push(now, createRetransmissionPacket(RtpPacketMediaType.kVideo, seq = 4))
            queue.push(now, createRetransmissionPacket(RtpPacketMediaType.kAudio, seq = 5))
            queue.push(now, createPacket(RtpPacketMediaType.kAudio, seq = 6))

            // Packets should be returned in high to low order.
            queue.pop()!!.sequenceNumber shouldBe 6
            // Audio and video retransmission has same prio, but video was enqueued first.
            queue.pop()!!.sequenceNumber shouldBe 4
            queue.pop()!!.sequenceNumber shouldBe 5
            // Video and FEC prioritized equally - but video was enqueued first.
            queue.pop()!!.sequenceNumber shouldBe 2
            queue.pop()!!.sequenceNumber shouldBe 3
            queue.pop()!!.sequenceNumber shouldBe 1
        }

        "PrioritizeAudioRetransmissionBeforeVideoRetransmissionIfConfigured" {
            val now = Instant.EPOCH
            val queue = PrioritizedPacketQueue(logger, now, prioritizeAudioRetransmission = true)

            // Add packets in low to high packet order.
            queue.push(now, createPacket(RtpPacketMediaType.kVideo, seq = 3))
            queue.push(now, createRetransmissionPacket(RtpPacketMediaType.kVideo, seq = 4))
            queue.push(now, createRetransmissionPacket(RtpPacketMediaType.kAudio, seq = 5))
            queue.push(now, createPacket(RtpPacketMediaType.kAudio, seq = 6))

            // Packets should be returned in high to low order.
            queue.pop()!!.sequenceNumber shouldBe 6
            queue.pop()!!.sequenceNumber shouldBe 5
            queue.pop()!!.sequenceNumber shouldBe 4
        }

        "ReturnsEqualPrioPacketsInRoundRobinOrder" {
            val now = Instant.EPOCH
            val queue = PrioritizedPacketQueue(logger, now)

            // Insert video packets (prioritized equally), simulating a simulcast-type use
            // case.
            queue.push(now, createPacket(RtpPacketMediaType.kVideo, seq = 1, ssrc = 100))

            queue.push(now, createPacket(RtpPacketMediaType.kVideo, seq = 2, ssrc = 101))
            queue.push(now, createPacket(RtpPacketMediaType.kVideo, seq = 3, ssrc = 101))

            queue.push(now, createPacket(RtpPacketMediaType.kVideo, seq = 4, ssrc = 102))
            queue.push(now, createPacket(RtpPacketMediaType.kVideo, seq = 5, ssrc = 102))
            queue.push(now, createPacket(RtpPacketMediaType.kVideo, seq = 6, ssrc = 102))
            queue.push(now, createPacket(RtpPacketMediaType.kVideo, seq = 7, ssrc = 102))

            // First packet from each SSRC.
            queue.pop()!!.sequenceNumber shouldBe 1
            queue.pop()!!.sequenceNumber shouldBe 2
            queue.pop()!!.sequenceNumber shouldBe 4

            // Second packets from streams that have packets left.
            queue.pop()!!.sequenceNumber shouldBe 3
            queue.pop()!!.sequenceNumber shouldBe 5

            // Only packets from last stream remaining.
            queue.pop()!!.sequenceNumber shouldBe 6
            queue.pop()!!.sequenceNumber shouldBe 7
        }

        "ReportsSizeInPackets" {
            val queue = PrioritizedPacketQueue(logger, creationTime = Instant.EPOCH)
            queue.sizeInPackets() shouldBe 0

            queue.push(enqueueTime = Instant.EPOCH, createPacket(RtpPacketMediaType.kVideo, seq = 1))
            queue.sizeInPackets() shouldBe 1

            queue.pop()
            queue.sizeInPackets() shouldBe 0
        }

        "ReportsPayloadSize" {
            val queue = PrioritizedPacketQueue(logger, creationTime = Instant.EPOCH)
            queue.sizeInPayloadBytes() shouldBe DataSize.ZERO

            queue.push(enqueueTime = Instant.EPOCH, createPacket(RtpPacketMediaType.kVideo, seq = 1))
            queue.sizeInPayloadBytes() shouldBe kDefaultPayloadSize.bytes

            queue.pop()
            queue.sizeInPayloadBytes() shouldBe DataSize.ZERO
        }

        "ReportsPaddingSize" {
            val queue = PrioritizedPacketQueue(logger, creationTime = Instant.EPOCH)
            queue.sizeInPayloadBytes() shouldBe DataSize.ZERO
            val kPaddingSize = 190.bytes

            val packet = RtpPacketToSend(
                packetType = RtpPacketMediaType.kPadding,
                ssrc = kDefaultSsrc,
                sequenceNumber = 1,
                headersSize = kHeadersSize,
                payloadSize = 0,
                paddingSize = 190
            )
            queue.push(enqueueTime = Instant.EPOCH, packet)
            queue.sizeInPayloadBytes() shouldBe kPaddingSize

            queue.pop()
            queue.sizeInPayloadBytes() shouldBe DataSize.ZERO
        }

        "ReportsOldestEnqueueTime" {
            val queue = PrioritizedPacketQueue(logger, creationTime = Instant.EPOCH)
            queue.oldestEnqueueTime() shouldBe NEVER

            // Add three packets, with the middle packet having higher prio.
            queue.push(millis(10), createPacket(RtpPacketMediaType.kPadding, seq = 1))
            queue.push(millis(20), createPacket(RtpPacketMediaType.kVideo, seq = 2))
            queue.push(millis(30), createPacket(RtpPacketMediaType.kPadding, seq = 3))
            queue.oldestEnqueueTime() shouldBe millis(10)

            queue.pop() // Pop packet with enqueue time 20.
            queue.oldestEnqueueTime() shouldBe millis(10)

            queue.pop() // Pop packet with enqueue time 10.
            queue.oldestEnqueueTime() shouldBe millis(30)

            queue.pop() // Pop packet with enqueue time 30, queue empty again.
            queue.oldestEnqueueTime() shouldBe NEVER
        }

        "ReportsAverageQueueTime" {
            val queue = PrioritizedPacketQueue(logger, creationTime = Instant.EPOCH)
            queue.averageQueueTime() shouldBe Duration.ZERO

            // Add three packets, with the middle packet having higher prio.
            queue.push(millis(10), createPacket(RtpPacketMediaType.kPadding, seq = 1))
            queue.push(millis(20), createPacket(RtpPacketMediaType.kVideo, seq = 2))
            queue.push(millis(30), createPacket(RtpPacketMediaType.kPadding, seq = 3))

            queue.updateAverageQueueTime(millis(40))
            // Packets have waited 30, 20, 10 ms -> average = 20ms.
            queue.averageQueueTime() shouldBe 20.ms

            queue.pop() // Pop packet with enqueue time 20.
            queue.averageQueueTime() shouldBe 20.ms

            queue.pop() // Pop packet with enqueue time 10.
            queue.averageQueueTime() shouldBe 10.ms

            queue.pop() // Pop packet with enqueue time 30, queue empty again.
            queue.averageQueueTime() shouldBe Duration.ZERO
        }

        "SubtractsPusedTimeFromAverageQueueTime" {
            val queue = PrioritizedPacketQueue(logger, creationTime = Instant.EPOCH)
            queue.averageQueueTime() shouldBe Duration.ZERO

            // Add a packet and then enable paused state.
            queue.push(millis(100), createPacket(RtpPacketMediaType.kPadding, seq = 1))
            queue.setPauseState(true, millis(600))
            queue.averageQueueTime() shouldBe 500.ms

            // Enqueue a packet 500ms into the paused state. Queue time of
            // original packet is still seen as 500ms and new one has 0ms giving
            // an average of 250ms.
            queue.push(millis(1100), createPacket(RtpPacketMediaType.kVideo, seq = 2))
            queue.averageQueueTime() shouldBe 250.ms

            // Unpause some time later, queue time still unchanged.
            queue.setPauseState(false, millis(1600))
            queue.averageQueueTime() shouldBe 250.ms

            // Update queue time 500ms after pause state ended.
            queue.updateAverageQueueTime(millis(2100))
            queue.averageQueueTime() shouldBe 750.ms
        }

        "ReportsLeadingPacketEnqueueTime" {
            val queue = PrioritizedPacketQueue(logger, creationTime = Instant.EPOCH)
            queue.leadingPacketEnqueueTime(RtpPacketMediaType.kAudio) shouldBe NEVER
            queue.leadingPacketEnqueueTime(RtpPacketMediaType.kVideo) shouldBe NEVER

            queue.push(millis(10), createPacket(RtpPacketMediaType.kVideo, seq = 1))
            queue.leadingPacketEnqueueTime(RtpPacketMediaType.kAudio) shouldBe NEVER
            queue.leadingPacketEnqueueTime(RtpPacketMediaType.kVideo) shouldBe millis(10)

            queue.push(millis(20), createPacket(RtpPacketMediaType.kAudio, seq = 2))

            queue.leadingPacketEnqueueTime(RtpPacketMediaType.kAudio) shouldBe millis(20)
            queue.leadingPacketEnqueueTime(RtpPacketMediaType.kVideo) shouldBe millis(10)

            queue.pop() // Pop audio packet.
            queue.leadingPacketEnqueueTime(RtpPacketMediaType.kAudio) shouldBe NEVER
            queue.leadingPacketEnqueueTime(RtpPacketMediaType.kVideo) shouldBe millis(10)

            queue.pop() // Pop video packet.
            queue.leadingPacketEnqueueTime(RtpPacketMediaType.kAudio) shouldBe NEVER
            queue.leadingPacketEnqueueTime(RtpPacketMediaType.kVideo) shouldBe NEVER
        }

        "ReportsLeadingPacketEnqueueTimeForRetransmission" {
            val queue = PrioritizedPacketQueue(
                logger,
                creationTime = Instant.EPOCH,
                prioritizeAudioRetransmission = true
            )
            queue.leadingPacketEnqueueTimeForRetransmission() shouldBe Instant.MAX

            queue.push(millis(10), createRetransmissionPacket(RtpPacketMediaType.kVideo, seq = 1))
            queue.push(millis(11), createRetransmissionPacket(RtpPacketMediaType.kAudio, seq = 2))
            queue.leadingPacketEnqueueTimeForRetransmission() shouldBe millis(10)
            queue.pop() // Pop audio retransmission since it has higher prio.
            queue.leadingPacketEnqueueTimeForRetransmission() shouldBe millis(10)
            queue.pop() // Pop video retransmission.
            queue.leadingPacketEnqueueTimeForRetransmission() shouldBe Instant.MAX
        }

        "PushAndPopUpdatesSizeInPacketsPerRtpPacketMediaType" {
            val now = Instant.EPOCH
            val queue = PrioritizedPacketQueue(logger, now)

            // Initially all sizes are zero.
            for (i in 0 until RtpPacketMediaType.kNumMediaTypes) {
                queue.sizeInPacketsPerRtpPacketMediaType()[i] shouldBe 0
            }

            // Push packets.
            queue.push(now, createPacket(RtpPacketMediaType.kAudio, 1))
            queue.sizeInPacketsPerRtpPacketMediaType()[RtpPacketMediaType.kAudio.ordinal] shouldBe 1

            queue.push(now, createPacket(RtpPacketMediaType.kVideo, 2))
            queue.sizeInPacketsPerRtpPacketMediaType()[RtpPacketMediaType.kVideo.ordinal] shouldBe 1

            queue.push(now, createRetransmissionPacket(RtpPacketMediaType.kVideo, 3))
            queue.sizeInPacketsPerRtpPacketMediaType()[RtpPacketMediaType.kRetransmission.ordinal] shouldBe 1

            queue.push(now, createPacket(RtpPacketMediaType.kForwardErrorCorrection, 4))
            queue.sizeInPacketsPerRtpPacketMediaType()[RtpPacketMediaType.kForwardErrorCorrection.ordinal] shouldBe 1

            queue.push(now, createPacket(RtpPacketMediaType.kPadding, 5))
            queue.sizeInPacketsPerRtpPacketMediaType()[RtpPacketMediaType.kPadding.ordinal] shouldBe 1

            // Now all sizes are 1.
            for (i in 0 until RtpPacketMediaType.kNumMediaTypes) {
                queue.sizeInPacketsPerRtpPacketMediaType()[i] shouldBe 1
            }

            // Popping happens in a priority order based on media type. This test does not
            // assert what this order is, only that the counter for the popped packet's
            // media type is decremented.
            for (i in 0 until RtpPacketMediaType.kNumMediaTypes) {
                val poppedPacket = queue.pop()!!
                queue.sizeInPacketsPerRtpPacketMediaType()[poppedPacket.packetType.ordinal] shouldBe 0
            }

            // We've popped all packets, so all sizes are zero.
            for (i in 0 until RtpPacketMediaType.kNumMediaTypes) {
                queue.sizeInPacketsPerRtpPacketMediaType()[i] shouldBe 0
            }
        }

        "ClearsPackets" {
            val now = Instant.EPOCH
            val queue = PrioritizedPacketQueue(logger, now)
            val kSsrc = 1L

            // Add two packets of each type, all using the same SSRC.
            var sequenceNumber = 0
            for (i in 0 until RtpPacketMediaType.kNumMediaTypes) {
                queue.push(now, createPacket(RtpPacketMediaType.entries[i], sequenceNumber++, kSsrc))
                queue.push(now, createPacket(RtpPacketMediaType.entries[i], sequenceNumber++, kSsrc))
            }
            queue.sizeInPackets() shouldBe 2 * RtpPacketMediaType.kNumMediaTypes

            // Remove all of them.
            queue.removePacketsForSsrc(kSsrc) shouldHaveSize 2 * RtpPacketMediaType.kNumMediaTypes
            queue.empty() shouldBe true

            // There should be no more packets removed when called again.
            queue.removePacketsForSsrc(kSsrc).shouldBeEmpty()
            queue.empty() shouldBe true
        }

        "ClearPacketsAffectsOnlySpecifiedSsrc" {
            val now = Instant.EPOCH
            val queue = PrioritizedPacketQueue(logger, now)
            val kRemovingSsrc = 1L
            val kStayingSsrc = 2L

            // Add an audio packet and a retransmission for the SSRC we will remove,
            // ensuring they are first in line.
            queue.push(now, createPacket(RtpPacketMediaType.kAudio, seq = 1, kRemovingSsrc))
            queue.push(now, createRetransmissionPacket(RtpPacketMediaType.kVideo, seq = 2, kRemovingSsrc))

            // Add a video packet and a retransmission for the SSRC that will remain.
            // The retransmission packets now both have pointers to their respective qeues
            // from the same prio level.
            queue.push(now, createPacket(RtpPacketMediaType.kVideo, seq = 3, kStayingSsrc))
            queue.push(now, createRetransmissionPacket(RtpPacketMediaType.kVideo, seq = 4, kStayingSsrc))

            queue.sizeInPackets() shouldBe 4

            // Clear the first two packets.
            queue.removePacketsForSsrc(kRemovingSsrc).map { it.sequenceNumber } shouldBe listOf(1, 2)
            queue.sizeInPackets() shouldBe 2

            // We should get the single remaining retransmission first, then the video
            // packet.
            queue.pop()!!.sequenceNumber shouldBe 4
            queue.pop()!!.sequenceNumber shouldBe 3
            queue.empty() shouldBe true
        }

        "ReportsKeyframePackets" {
            val now = Instant.EPOCH
            val queue = PrioritizedPacketQueue(logger, now)
            val kVideoSsrc1 = 1234L
            val kVideoSsrc2 = 2345L

            queue.hasKeyframePackets(kVideoSsrc1) shouldBe false
            queue.hasKeyframePackets(kVideoSsrc2) shouldBe false

            queue.push(now, createPacket(RtpPacketMediaType.kVideo, seq = 1, kVideoSsrc1, isKeyFrame = true))
            queue.push(now, createPacket(RtpPacketMediaType.kVideo, seq = 11, kVideoSsrc2, isKeyFrame = false))

            queue.hasKeyframePackets(kVideoSsrc1) shouldBe true
            queue.hasKeyframePackets(kVideoSsrc2) shouldBe false

            queue.push(now, createPacket(RtpPacketMediaType.kVideo, seq = 2, kVideoSsrc1, isKeyFrame = true))
            queue.push(now, createPacket(RtpPacketMediaType.kVideo, seq = 12, kVideoSsrc2, isKeyFrame = true))

            queue.hasKeyframePackets(kVideoSsrc1) shouldBe true
            queue.hasKeyframePackets(kVideoSsrc2) shouldBe true

            queue.push(now, createPacket(RtpPacketMediaType.kVideo, seq = 3, kVideoSsrc1, isKeyFrame = false))
            queue.push(now, createPacket(RtpPacketMediaType.kVideo, seq = 13, kVideoSsrc2, isKeyFrame = true))

            queue.hasKeyframePackets(kVideoSsrc1) shouldBe true
            queue.hasKeyframePackets(kVideoSsrc2) shouldBe true

            queue.pop()!!.sequenceNumber shouldBe 1
            queue.pop()!!.sequenceNumber shouldBe 11

            queue.hasKeyframePackets(kVideoSsrc1) shouldBe true
            queue.hasKeyframePackets(kVideoSsrc2) shouldBe true

            queue.pop()!!.sequenceNumber shouldBe 2
            queue.pop()!!.sequenceNumber shouldBe 12

            queue.hasKeyframePackets(kVideoSsrc1) shouldBe false
            queue.hasKeyframePackets(kVideoSsrc2) shouldBe true

            queue.removePacketsForSsrc(kVideoSsrc2)

            queue.hasKeyframePackets(kVideoSsrc1) shouldBe false
            queue.hasKeyframePackets(kVideoSsrc2) shouldBe false
        }

        "PacketsDroppedIfNotPulledWithinTttl" {
            var now = Instant.EPOCH
            val ttls = PacketQueueTTL(audioRetransmission = 200.ms)
            val queue = PrioritizedPacketQueue(logger, now, prioritizeAudioRetransmission = true, ttls)

            queue.push(now, createRetransmissionPacket(RtpPacketMediaType.kAudio, seq = 1))
            now += ttls.audioRetransmission + 1.ms
            queue.sizeInPackets() shouldBe 1
            queue.push(now, createRetransmissionPacket(RtpPacketMediaType.kAudio, seq = 2))
            queue.sizeInPackets() shouldBe 1
            queue.pop()!!.sequenceNumber shouldBe 2
        }

        "DontSendPacketsAfterTttl" {
            var now = Instant.EPOCH
            val ttls = PacketQueueTTL(audioRetransmission = 200.ms)
            val queue = PrioritizedPacketQueue(logger, now, prioritizeAudioRetransmission = true, ttls)

            queue.push(now, createRetransmissionPacket(RtpPacketMediaType.kAudio, seq = 1))
            now += ttls.audioRetransmission + 1.ms
            queue.sizeInPackets() shouldBe 1
            queue.push(now, createPacket(RtpPacketMediaType.kVideo, seq = 2))
            queue.push(now, createPacket(RtpPacketMediaType.kAudio, seq = 3))
            // Expect the old packet to have been removed since it was not popped in time.
            queue.sizeInPackets() shouldBe 3
            queue.pop()!!.sequenceNumber shouldBe 3
            queue.sizeInPackets() shouldBe 1
            queue.pop()!!.sequenceNumber shouldBe 2
            queue.sizeInPackets() shouldBe 0
        }

        "SendsNewVideoPacketAfterPurgingLastOldRtxPacket" {
            var now = Instant.EPOCH
            val ttls = PacketQueueTTL(videoRetransmission = 400.ms)
            val queue = PrioritizedPacketQueue(logger, now, prioritizeAudioRetransmission = true, ttls)

            queue.push(now, createRetransmissionPacket(RtpPacketMediaType.kVideo, seq = 1))
            now += ttls.videoRetransmission + 1.ms
            queue.push(now, createPacket(RtpPacketMediaType.kAudio, seq = 2))
            queue.sizeInPackets() shouldBe 2
            // Expect the audio packet to be send and the video retransmission packet to
            // be dropped since it is old.
            queue.pop()!!.sequenceNumber shouldBe 2
            queue.sizeInPackets() shouldBe 0

            queue.push(now, createPacket(RtpPacketMediaType.kVideo, seq = 3))
            queue.sizeInPackets() shouldBe 1
            queue.pop()!!.sequenceNumber shouldBe 3
            queue.sizeInPackets() shouldBe 0
        }

        "SendsPacketsAfterTttlIfPrioHigherThanPushedPackets" {
            var now = Instant.EPOCH
            val ttls = PacketQueueTTL(audioRetransmission = 200.ms)
            val queue = PrioritizedPacketQueue(logger, now, prioritizeAudioRetransmission = true, ttls)

            queue.push(now, createRetransmissionPacket(RtpPacketMediaType.kAudio, seq = 1))
            now += ttls.audioRetransmission + 1.ms
            queue.sizeInPackets() shouldBe 1
            queue.push(now, createPacket(RtpPacketMediaType.kVideo, seq = 2))

            // This test just show that TTL is not enforced strictly. If a new audio
            // packet had been queued before a packet was popped, the audio retransmission
            // packet would have been dropped.
            queue.sizeInPackets() shouldBe 2
            queue.pop()!!.sequenceNumber shouldBe 1
            queue.sizeInPackets() shouldBe 1
        }

        // Jitsi local: WebRTC frees a packet that it drops, and has no test of that.
        "PacketsDroppedAfterTtlAreGivenToTheCallback" {
            var now = Instant.EPOCH
            val ttls = PacketQueueTTL(audioRetransmission = 200.ms)
            val dropped = mutableListOf<RtpPacketToSend>()
            val queue = PrioritizedPacketQueue(logger, now, prioritizeAudioRetransmission = true, ttls) {
                dropped.add(it)
            }

            queue.push(now, createRetransmissionPacket(RtpPacketMediaType.kAudio, seq = 1))
            now += ttls.audioRetransmission + 1.ms
            dropped.shouldBeEmpty()
            queue.push(now, createRetransmissionPacket(RtpPacketMediaType.kAudio, seq = 2))
            dropped.map { it.sequenceNumber } shouldBe listOf(1)
            queue.pop()!!.sequenceNumber shouldBe 2
            dropped.map { it.sequenceNumber } shouldBe listOf(1)
        }
    }
}
