/*
 * Copyright @ 2019-present 8x8, Inc
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
package org.jitsi.nlj.rtp.bandwidthestimation2

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.jitsi.nlj.util.Bandwidth
import org.jitsi.nlj.util.DataSize
import org.jitsi.nlj.util.bytes
import org.jitsi.nlj.util.bytesPerSec
import org.jitsi.nlj.util.div
import org.jitsi.utils.ms
import org.jitsi.utils.secs
import java.time.Duration
import java.time.Instant

class FeedbackGenerator {
    fun createFeedbackVector(
        numberOfPackets: Int,
        packetSize: DataSize,
        sendRate: Bandwidth,
        recvRate: Bandwidth
    ): List<PacketResult> {
        val packetFeedbackVector = MutableList<PacketResult>(numberOfPackets) { PacketResult() }
        for (i in 0 until numberOfPackets) {
            packetFeedbackVector[i].sentPacket.sendTime = sendClock
            packetFeedbackVector[i].sentPacket.sequenceNumber = sequenceNumber
            packetFeedbackVector[i].sentPacket.size = packetSize
            sendClock += packetSize / sendRate
            recvClock += packetSize / recvRate
            sequenceNumber += 1
            packetFeedbackVector[i].receiveTime = recvClock
        }
        return packetFeedbackVector
    }

    fun currentReceiveClock() = recvClock
    fun advanceReceiveClock(delta: Duration) {
        recvClock += delta
    }
    fun advanceSendClock(delta: Duration) {
        sendClock += delta
    }

    private var sendClock = Instant.ofEpochMilli(100000)
    private var recvClock = Instant.ofEpochMilli(10000)
    private var sequenceNumber = 100L
}

/**
 * Unit tests of AcknowledgedBitrateEstimator.
 *
 * Based on WebRTC modules/congestion_controller/goog_cc/robust_throughput_estimator_unittest.cc in
 * WebRTC tag branch-heads/7204 (Chromium 138)
 */
@SuppressFBWarnings("IM_BAD_CHECK_FOR_ODD")
class RobustThroughputEstimatorTest : FreeSpec() {
    init {
        "DefaultEnabled" {
            val settings = RobustThroughputEstimatorSettings()
            settings.enabled shouldBe true
        }

        "CanDisable" {
            val settings = RobustThroughputEstimatorSettings(enabled = false)
            settings.enabled shouldBe false
        }

        "InitialEstimate" {
            val feedbackGenerator = FeedbackGenerator()
            val throughputEstimator = RobustThroughputEstimator(RobustThroughputEstimatorSettings(enabled = true))
            val sendRate = 100000.bytesPerSec
            val recvRate = 100000.bytesPerSec

            // No estimate until the estimator has enough data.
            var packetFeedback = feedbackGenerator.createFeedbackVector(
                9,
                1000.bytes,
                sendRate,
                recvRate
            )
            throughputEstimator.incomingPacketFeedbackVector(packetFeedback)
            throughputEstimator.bitrate() shouldBe null

            // Estimate once `required_packets` packets have been received.
            packetFeedback = feedbackGenerator.createFeedbackVector(
                1,
                1000.bytes,
                sendRate,
                recvRate
            )
            throughputEstimator.incomingPacketFeedbackVector(packetFeedback)
            var throughput = throughputEstimator.bitrate()
            throughput shouldBe sendRate

            // Estimate remains stable when send and receive rates are stable.
            packetFeedback = feedbackGenerator.createFeedbackVector(
                15,
                1000.bytes,
                sendRate,
                recvRate
            )
            throughputEstimator.incomingPacketFeedbackVector(packetFeedback)
            throughput = throughputEstimator.bitrate()
            throughput shouldBe sendRate
        }

        "EstimateAdapts" {
            val feedbackGenerator = FeedbackGenerator()
            val throughputEstimator = RobustThroughputEstimator(RobustThroughputEstimatorSettings(enabled = true))

            // 1 second, 800kbps, estimate is stable.
            var sendRate = 100000.bytesPerSec
            var recvRate = 100000.bytesPerSec

            repeat(10) {
                val packetFeedback = feedbackGenerator.createFeedbackVector(
                    10,
                    1000.bytes,
                    sendRate,
                    recvRate
                )
                throughputEstimator.incomingPacketFeedbackVector(packetFeedback)
                val throughput = throughputEstimator.bitrate()
                throughput shouldBe sendRate
            }

            // 1 second, 1600kbps, estimate increases
            sendRate = 200000.bytesPerSec
            recvRate = 200000.bytesPerSec
            repeat(20) {
                val packetFeedback = feedbackGenerator.createFeedbackVector(
                    10,
                    1000.bytes,
                    sendRate,
                    recvRate
                )
                throughputEstimator.incomingPacketFeedbackVector(packetFeedback)
                val throughput = throughputEstimator.bitrate()
                throughput shouldNotBe null
                throughput!! shouldBeGreaterThanOrEqualTo 100000.bytesPerSec
                throughput shouldBeLessThanOrEqualTo sendRate
            }

            // 1 second, 1600kbps, estimate is stable
            repeat(20) {
                val packetFeedback = feedbackGenerator.createFeedbackVector(
                    10,
                    1000.bytes,
                    sendRate,
                    recvRate
                )
                throughputEstimator.incomingPacketFeedbackVector(packetFeedback)
                val throughput = throughputEstimator.bitrate()
                throughput shouldBe sendRate
            }

            // 1 second, 400kbps, estimate decreases
            sendRate = 50000.bytesPerSec
            recvRate = 50000.bytesPerSec
            repeat(5) {
                val packetFeedback = feedbackGenerator.createFeedbackVector(
                    10,
                    1000.bytes,
                    sendRate,
                    recvRate
                )
                throughputEstimator.incomingPacketFeedbackVector(packetFeedback)
                val throughput = throughputEstimator.bitrate()
                throughput shouldNotBe null
                throughput!! shouldBeLessThanOrEqualTo 200000.bytesPerSec
                throughput shouldBeGreaterThanOrEqualTo sendRate
            }

            // 1 second, 400kbps, estimate is stable
            sendRate = 50000.bytesPerSec
            recvRate = 50000.bytesPerSec
            repeat(5) {
                val packetFeedback = feedbackGenerator.createFeedbackVector(
                    10,
                    1000.bytes,
                    sendRate,
                    recvRate
                )
                throughputEstimator.incomingPacketFeedbackVector(packetFeedback)
                val throughput = throughputEstimator.bitrate()
                throughput shouldBe sendRate
            }
        }

        "CappedByReceiveRate" {
            val feedbackGenerator = FeedbackGenerator()
            val throughputEstimator = RobustThroughputEstimator(RobustThroughputEstimatorSettings(enabled = true))
            val sendRate = 100000.bytesPerSec
            val recvRate = 25000.bytesPerSec

            val packetFeedback = feedbackGenerator.createFeedbackVector(
                20,
                1000.bytes,
                sendRate,
                recvRate
            )
            throughputEstimator.incomingPacketFeedbackVector(packetFeedback)
            val throughput = throughputEstimator.bitrate()
            throughput shouldNotBe null
            throughput!!.bytesPerSec shouldBe recvRate.bytesPerSec plusOrMinus
                0.05 * recvRate.bytesPerSec // Allow 5% error
        }

        "CappedBySendRate" {
            val feedbackGenerator = FeedbackGenerator()
            val throughputEstimator = RobustThroughputEstimator(RobustThroughputEstimatorSettings(enabled = true))
            val sendRate = 50000.bytesPerSec
            val recvRate = 100000.bytesPerSec

            val packetFeedback = feedbackGenerator.createFeedbackVector(
                20,
                1000.bytes,
                sendRate,
                recvRate
            )
            throughputEstimator.incomingPacketFeedbackVector(packetFeedback)
            val throughput = throughputEstimator.bitrate()
            throughput shouldNotBe null
            throughput!!.bytesPerSec shouldBe sendRate.bytesPerSec plusOrMinus
                0.05 * sendRate.bytesPerSec // Allow 5% error
        }

        "DelaySpike" {
            val feedbackGenerator = FeedbackGenerator()
            // This test uses a 500ms window to amplify the effect
            // of a delay spike.
            val throughputEstimator = RobustThroughputEstimator(
                RobustThroughputEstimatorSettings(enabled = true, minWindowDuration = 500.ms)
            )
            val sendRate = 100000.bytesPerSec
            var recvRate = 100000.bytesPerSec

            var packetFeedback = feedbackGenerator.createFeedbackVector(
                20,
                1000.bytes,
                sendRate,
                recvRate
            )
            throughputEstimator.incomingPacketFeedbackVector(packetFeedback)
            var throughput = throughputEstimator.bitrate()
            throughput shouldBe sendRate

            // Delay spike. 25 packets sent, but none received.
            feedbackGenerator.advanceReceiveClock(250.ms)

            // Deliver all of the packets during the next 50 ms. (During this time,
            // we'll have sent an additional 5 packets, so we need to receive 30
            // packets at 1000 bytes each in 50 ms, i.e. 600000 bytes per second).
            recvRate = 600000.bytesPerSec
            // Estimate should not drop
            repeat(30) {
                packetFeedback = feedbackGenerator.createFeedbackVector(
                    1,
                    1000.bytes,
                    sendRate,
                    recvRate
                )
                throughputEstimator.incomingPacketFeedbackVector(packetFeedback)
                throughput = throughputEstimator.bitrate()
                throughput shouldNotBe null
                throughput!!.bytesPerSec shouldBe sendRate.bytesPerSec plusOrMinus
                    0.05 * sendRate.bytesPerSec // Allow 5% error
            }

            // Delivery at normal rate. When the packets received before the gap
            // has left the estimator's window, the receive rate will be high, but the
            // estimate should be capped by the send rate.
            recvRate = 100000.bytesPerSec
            repeat(20) {
                packetFeedback = feedbackGenerator.createFeedbackVector(
                    5,
                    1000.bytes,
                    sendRate,
                    recvRate
                )
                throughputEstimator.incomingPacketFeedbackVector(packetFeedback)
                throughput = throughputEstimator.bitrate()
                throughput shouldNotBe null
                throughput!!.bytesPerSec shouldBe sendRate.bytesPerSec plusOrMinus
                    0.05 * sendRate.bytesPerSec // Allow 5% error
            }
        }

        "HighLoss" {
            val feedbackGenerator = FeedbackGenerator()
            val throughputEstimator = RobustThroughputEstimator(RobustThroughputEstimatorSettings(enabled = true))
            val sendRate = 100000.bytesPerSec
            val recvRate = 100000.bytesPerSec

            var packetFeedback = feedbackGenerator.createFeedbackVector(
                20,
                1000.bytes,
                sendRate,
                recvRate
            )

            // 50% loss
            for (i in packetFeedback.indices) {
                if (i % 2 == 1) {
                    packetFeedback[i].receiveTime = Instant.MAX
                }
            }

            packetFeedback = packetFeedback.sortedBy { it.receiveTime }
            throughputEstimator.incomingPacketFeedbackVector(packetFeedback)
            val throughput = throughputEstimator.bitrate()
            throughput shouldNotBe null
            throughput!!.bytesPerSec shouldBe sendRate.bytesPerSec / 2 plusOrMinus
                0.05 * sendRate.bytesPerSec / 2 // Allow 5% error
        }

        "ReorderedFeedback" {
            val feedbackGenerator = FeedbackGenerator()
            val throughputEstimator = RobustThroughputEstimator(RobustThroughputEstimatorSettings(enabled = true))
            val sendRate = 100000.bytesPerSec
            val recvRate = 100000.bytesPerSec

            var packetFeedback = feedbackGenerator.createFeedbackVector(
                20,
                1000.bytes,
                sendRate,
                recvRate
            )
            throughputEstimator.incomingPacketFeedbackVector(packetFeedback)
            var throughput = throughputEstimator.bitrate()
            throughput shouldBe sendRate

            val delayedFeedback = feedbackGenerator.createFeedbackVector(
                10,
                1000.bytes,
                sendRate,
                recvRate
            )
            packetFeedback = feedbackGenerator.createFeedbackVector(
                10,
                1000.bytes,
                sendRate,
                recvRate
            )

            // Since we're missing some feedback, it's expected that the
            // estimate will drop.
            throughputEstimator.incomingPacketFeedbackVector(packetFeedback)
            throughput = throughputEstimator.bitrate()
            throughput shouldNotBe null
            throughput!! shouldBeLessThan sendRate

            // But it should completely recover as soon as we get the feedback.
            throughputEstimator.incomingPacketFeedbackVector(delayedFeedback)
            throughput = throughputEstimator.bitrate()
            throughput shouldBe sendRate

            // It should then remain stable (as if the feedbacks weren't reordered.)
            repeat(10) {
                packetFeedback = feedbackGenerator.createFeedbackVector(
                    15,
                    1000.bytes,
                    sendRate,
                    recvRate
                )
                throughputEstimator.incomingPacketFeedbackVector(packetFeedback)
                throughput = throughputEstimator.bitrate()
                throughput shouldBe sendRate
            }
        }

        "DeepReordering" {
            val feedbackGenerator = FeedbackGenerator()
            // This test uses a 500ms window to amplify the
            // effect of reordering.
            val throughputEstimator = RobustThroughputEstimator(
                RobustThroughputEstimatorSettings(enabled = true, minWindowDuration = 500.ms)
            )
            val sendRate = 100000.bytesPerSec
            val recvRate = 100000.bytesPerSec

            val delayedPackets = feedbackGenerator.createFeedbackVector(
                1,
                1000.bytes,
                sendRate,
                recvRate
            )

            repeat(10) {
                val packetFeedback = feedbackGenerator.createFeedbackVector(
                    10,
                    1000.bytes,
                    sendRate,
                    recvRate
                )
                throughputEstimator.incomingPacketFeedbackVector(packetFeedback)
                val throughput = throughputEstimator.bitrate()
                throughput shouldBe sendRate
            }

            // Delayed packet arrives ~1 second after it should have.
            // Since the window is 500 ms, the delayed packet was sent ~500
            // ms before the second oldest packet. However, the send rate
            // should not drop.
            run {
                delayedPackets.first().receiveTime = feedbackGenerator.currentReceiveClock()
                throughputEstimator.incomingPacketFeedbackVector(delayedPackets)
                val throughput = throughputEstimator.bitrate()
                throughput shouldNotBe null
                throughput!!.bytesPerSec shouldBe sendRate.bytesPerSec plusOrMinus
                    0.05 * sendRate.bytesPerSec // Allow 5% error
            }

            // Thoughput should stay stable.
            repeat(10) {
                val packetFeedback = feedbackGenerator.createFeedbackVector(
                    10,
                    1000.bytes,
                    sendRate,
                    recvRate
                )
                throughputEstimator.incomingPacketFeedbackVector(packetFeedback)
                val throughput = throughputEstimator.bitrate()
                throughput!!.bytesPerSec shouldBe sendRate.bytesPerSec plusOrMinus
                    0.05 * sendRate.bytesPerSec // Allow 5% error
            }
        }

        "ResetsIfClockChangeBackwards" {
            val feedbackGenerator = FeedbackGenerator()
            val throughputEstimator = RobustThroughputEstimator(RobustThroughputEstimatorSettings(enabled = true))
            var sendRate = 100000.bytesPerSec
            var recvRate = 100000.bytesPerSec

            var packetFeedback = feedbackGenerator.createFeedbackVector(
                20,
                1000.bytes,
                sendRate,
                recvRate
            )
            throughputEstimator.incomingPacketFeedbackVector(packetFeedback)
            throughputEstimator.bitrate() shouldBe sendRate

            feedbackGenerator.advanceReceiveClock((-2).secs)
            sendRate = 200000.bytesPerSec
            recvRate = 200000.bytesPerSec
            packetFeedback = feedbackGenerator.createFeedbackVector(
                20,
                1000.bytes,
                sendRate,
                recvRate
            )
            throughputEstimator.incomingPacketFeedbackVector(packetFeedback)
            throughputEstimator.bitrate() shouldBe sendRate
        }

        "StreamPausedAndResumed" {
            val feedbackGenerator = FeedbackGenerator()
            val throughputEstimator = RobustThroughputEstimator(RobustThroughputEstimatorSettings(enabled = true))
            val sendRate = 100000.bytesPerSec
            val recvRate = 100000.bytesPerSec

            var packetFeedback = feedbackGenerator.createFeedbackVector(
                20,
                1000.bytes,
                sendRate,
                recvRate
            )
            throughputEstimator.incomingPacketFeedbackVector(packetFeedback)
            var throughput = throughputEstimator.bitrate()
            val expectedBytesPerSec = 100 * 1000.0
            throughput!!.bytesPerSec shouldBe expectedBytesPerSec plusOrMinus
                0.05 * expectedBytesPerSec // Allow 5% error

            // No packets sent or feedback received for 60s.
            feedbackGenerator.advanceSendClock(60.secs)
            feedbackGenerator.advanceReceiveClock(60.secs)

            // Resume sending packets at the same rate as before. The estimate
            // will initially be invalid, due to lack of recent data.
            packetFeedback = feedbackGenerator.createFeedbackVector(
                5,
                1000.bytes,
                sendRate,
                recvRate
            )
            throughputEstimator.incomingPacketFeedbackVector(packetFeedback)
            throughputEstimator.bitrate() shouldBe null

            // But be back to the normal level once we have enough data.
            repeat(4) {
                packetFeedback = feedbackGenerator.createFeedbackVector(
                    5,
                    1000.bytes,
                    sendRate,
                    recvRate
                )
                throughputEstimator.incomingPacketFeedbackVector(packetFeedback)
                throughput = throughputEstimator.bitrate()
                throughput shouldBe sendRate
            }
        }
    }
}
