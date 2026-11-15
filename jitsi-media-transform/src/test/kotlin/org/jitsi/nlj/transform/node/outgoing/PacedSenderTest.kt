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
package org.jitsi.nlj.transform.node.outgoing

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.PacketOrigin
import org.jitsi.nlj.resources.logging.StdoutLogger
import org.jitsi.nlj.resources.node.onOutput
import org.jitsi.nlj.rtp.AudioRtpPacket
import org.jitsi.nlj.rtp.PaddingVideoPacket
import org.jitsi.nlj.rtp.VideoRtpPacket
import org.jitsi.nlj.rtp.bandwidthestimation2.PacedPacketInfo
import org.jitsi.nlj.rtp.bandwidthestimation2.PacerConfig
import org.jitsi.nlj.rtp.bandwidthestimation2.ProbeClusterConfig
import org.jitsi.nlj.util.Bandwidth
import org.jitsi.nlj.util.DataSize
import org.jitsi.nlj.util.bytes
import org.jitsi.nlj.util.kbps
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.utils.concurrent.FakeScheduledExecutorService
import org.jitsi.utils.ms
import org.jitsi.utils.secs
import org.jitsi.utils.time.FakeClock
import java.time.Duration
import java.time.Instant

class PacedSenderTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode = IsolationMode.InstancePerLeaf

    private val clock = FakeClock().apply { setTime(Instant.ofEpochSecond(1000)) }

    /** Runs both the timer of the pacer and the tasks that the timer submits. */
    private val executor = FakeScheduledExecutorService(clock)

    /** The sizes that the pacer asked for padding with. */
    private val paddingRequests = mutableListOf<Int>()

    private val pacedSender: PacedSender = PacedSender(
        StdoutLogger(),
        executor,
        sendPadding = { numBytes ->
            paddingRequests.add(numBytes)
            pacedSender.processPacket(
                PacketInfo(paddingPacket(numBytes.coerceAtMost(MAX_PADDING_PACKET_SIZE))).apply {
                    packetOrigin = PacketOrigin.Padding
                }
            )
        },
        timer = executor,
        clock = clock
    )

    /** The packets that the pacer has passed on, with the time at which it did. */
    private val sent = mutableListOf<Pair<Instant, PacketInfo>>()

    private fun sentSequenceNumbers() = sent.map { (it.second.packet as RtpPacket).sequenceNumber }

    /** Lets the given time pass, and runs the tasks of the pacer that are due in it. */
    private fun elapse(duration: Duration) {
        val end = clock.instant() + duration
        executor.runUntil(end)
        clock.setTime(end)
    }

    private fun videoPacket(sequenceNumber: Int, size: Int = 1000, ssrc: Long = VIDEO_SSRC) = PacketInfo(
        VideoRtpPacket(ByteArray(size + 20), 0, size).apply {
            version = 2
            this.ssrc = ssrc
            this.sequenceNumber = sequenceNumber
        }
    ).apply { packetOrigin = PacketOrigin.Routed }

    private fun audioPacket(sequenceNumber: Int) = PacketInfo(
        AudioRtpPacket(ByteArray(120), 0, 100).apply {
            version = 2
            ssrc = AUDIO_SSRC
            this.sequenceNumber = sequenceNumber
        }
    ).apply { packetOrigin = PacketOrigin.Routed }

    private fun paddingPacket(size: Int) = PaddingVideoPacket.create(size).apply {
        ssrc = PADDING_SSRC
        sequenceNumber = nextPaddingSequenceNumber++
    }

    private var nextPaddingSequenceNumber = 5000

    private fun pacerConfig(rate: Bandwidth, paddingRate: Bandwidth = Bandwidth.ZERO, burst: Duration = Duration.ZERO) =
        PacerConfig.create(clock.instant(), rate, paddingRate, burst)

    init {
        pacedSender.onOutput { sent.add(Pair(clock.instant(), it)) }

        context("Before a pacing rate has been set") {
            should("pass every packet on unchanged") {
                for (i in 1..50) {
                    pacedSender.processPacket(videoPacket(i))
                }
                sentSequenceNumbers() shouldBe (1..50).toList()
                pacedSender.queueSizeData() shouldBe DataSize.ZERO
            }
        }
        context("With a pacing rate") {
            // 100 kB per second.
            pacedSender.setPacerConfig(pacerConfig(800.kbps))
            elapse(1.ms)

            should("pass on a packet that the rate allows on the thread that brought it") {
                pacedSender.processPacket(videoPacket(1))
                sentSequenceNumbers() shouldBe listOf(1)
            }
            should("hold the packets that the rate does not allow, and send them at the pacing rate") {
                val start = clock.instant()
                for (i in 1..21) {
                    pacedSender.processPacket(videoPacket(i))
                }
                // The first packet uses up the budget.
                sentSequenceNumbers() shouldBe listOf(1)
                pacedSender.queueSizeData() shouldBe (20 * 988).bytes

                elapse(1.secs)
                sentSequenceNumbers() shouldBe (1..21).toList()
                pacedSender.queueSizeData() shouldBe DataSize.ZERO
                // 20 packets of 988 bytes of payload at 100 kB per second take 198 ms.
                val lastSendTime = Duration.between(start, sent.last().first)
                lastSendTime shouldBeGreaterThan 180.ms
                lastSendTime shouldBeLessThan 220.ms
                // They are spread over that time, not sent together.
                Duration.between(start, sent[10].first) shouldBeGreaterThan 80.ms
                Duration.between(start, sent[10].first) shouldBeLessThan 120.ms
            }
            should("send audio at once, ahead of the video that is waiting") {
                for (i in 1..5) {
                    pacedSender.processPacket(videoPacket(i))
                }
                pacedSender.processPacket(audioPacket(100))
                sentSequenceNumbers() shouldBe listOf(1, 100)
            }
            should("send retransmissions before other video") {
                for (i in 1..3) {
                    pacedSender.processPacket(videoPacket(i))
                }
                pacedSender.processPacket(
                    videoPacket(200, ssrc = RTX_SSRC).apply { packetOrigin = PacketOrigin.Retransmission }
                )
                elapse(1.secs)
                sentSequenceNumbers() shouldBe listOf(1, 200, 2, 3)
            }
            should("send a burst when the pacer config has a burst interval") {
                // 100 kB per second in bursts of up to 40 ms, which is 4000 bytes.
                pacedSender.setPacerConfig(pacerConfig(800.kbps, burst = 40.ms))
                elapse(1.ms)
                for (i in 1..10) {
                    pacedSender.processPacket(videoPacket(i))
                }
                // The burst is over once the debt covers 40 ms: that takes five packets of 988 bytes.
                sentSequenceNumbers() shouldBe (1..5).toList()
                elapse(1.secs)
                sentSequenceNumbers() shouldBe (1..10).toList()
            }
            should("send only audio while congested, and the rest once the congestion is over") {
                pacedSender.setCongested(true)
                elapse(1.ms)
                for (i in 1..3) {
                    pacedSender.processPacket(videoPacket(i))
                }
                pacedSender.processPacket(audioPacket(100))
                elapse(300.ms)
                sentSequenceNumbers() shouldBe listOf(100)
                pacedSender.queueSizeData() shouldBe (3 * 988).bytes

                pacedSender.setCongested(false)
                elapse(300.ms)
                sentSequenceNumbers() shouldBe listOf(100, 1, 2, 3)
            }
            should("drop the packets that are waiting when it stops") {
                for (i in 1..5) {
                    pacedSender.processPacket(videoPacket(i))
                }
                sentSequenceNumbers() shouldBe listOf(1)
                pacedSender.stop()
                elapse(1.secs)
                sentSequenceNumbers() shouldBe listOf(1)
                pacedSender.queueSizeData() shouldBe DataSize.ZERO
                pacedSender.getNodeStats().toJson()["num_packets_dropped"].asLong() shouldBe 4L
            }
        }
        context("With a padding rate") {
            pacedSender.setPacerConfig(pacerConfig(800.kbps, paddingRate = 400.kbps))
            elapse(1.ms)

            should("not send padding before a media packet has been sent") {
                elapse(1.secs)
                paddingRequests.shouldBeEmpty()
                sent.shouldBeEmpty()
            }
            should("fill up to the padding rate when there is no media to send") {
                pacedSender.processPacket(videoPacket(1))
                sent shouldHaveSize 1
                elapse(1.secs)

                val padding = sent.drop(1).map { it.second }
                padding.all { it.packetOrigin == PacketOrigin.Padding } shouldBe true
                // 50 kB per second of padding, less the media packet at the start.
                val paddingBytes = padding.sumOf { it.packet.length }
                paddingBytes shouldBeGreaterThan 40_000
                paddingBytes shouldBeLessThan 55_000
            }
        }
        context("With a probe cluster") {
            pacedSender.setPacerConfig(pacerConfig(800.kbps))
            pacedSender.createProbeClusters(
                listOf(
                    ProbeClusterConfig(
                        atTime = clock.instant(),
                        targetDataRate = 2000.kbps,
                        targetDuration = 15.ms,
                        targetProbeCount = 5,
                        id = 7
                    )
                )
            )
            elapse(1.ms)

            should("send the probe, and mark its packets with the probe cluster") {
                // The prober starts when a packet of at least its minimum size is sent.
                pacedSender.processPacket(videoPacket(1))
                elapse(200.ms)

                val probePackets = sent.map { it.second }.filter { it.probingInfo != null }
                probePackets.all { (it.probingInfo as PacedPacketInfo).probeClusterId == 7 } shouldBe true
                // 2 Mbit/s for 15 ms is 3750 bytes.
                probePackets.sumOf { it.packet.length } shouldBeGreaterThan 3750
                paddingRequests.isNotEmpty() shouldBe true
                // The probe is sent at the rate of the cluster, not at the pacing rate.
                Duration.between(sent.first().first, sent.last().first) shouldBeLessThan 30.ms
            }
        }
    }

    companion object {
        private const val VIDEO_SSRC = 1000L
        private const val RTX_SSRC = 1001L
        private const val AUDIO_SSRC = 2000L
        private const val PADDING_SSRC = 3000L

        /** An RTP header and the most padding that a packet can have. */
        private const val MAX_PADDING_PACKET_SIZE = 12 + 255
    }
}
