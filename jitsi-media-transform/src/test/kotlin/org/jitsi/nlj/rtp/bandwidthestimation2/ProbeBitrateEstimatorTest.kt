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

package org.jitsi.nlj.rtp.bandwidthestimation2

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.longs.shouldBeInRange
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.jitsi.nlj.util.Bandwidth
import org.jitsi.nlj.util.bytes
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging2.createLogger
import org.jitsi.utils.ms
import java.time.Instant

/**
 * Unit tests of ProbeBitrateEstimator
 *
 * Based on WebRTC modules/congestion_controller/goog_cc/probe_bitrate_estimator_unittest.cc in
 * WebRTC tag branch-heads/7204 (Chromium 138).
 */
class TestProbeBitrateEstimator {
    var measuredDataRate: Bandwidth? = null
    val probeBitrateEstimator = ProbeBitrateEstimator(logger, diagnosticContext)

    // TODO(philipel): Use PacedPacketInfo when ProbeBitrateEstimator is rewritten
    //                 to use that information.
    fun addPacketFeedback(
        probeClusterId: Int,
        sizeBytes: Long,
        sendTimeMs: Long,
        arrivalTimeMs: Long,
        minProbes: Int = kDefaultMinProbes,
        minBytes: Int = kDefaultMinBytes
    ) {
        val kReferenceTime = Instant.ofEpochSecond(1000)
        val feedback = PacketResult()
        feedback.sentPacket.sendTime = kReferenceTime + sendTimeMs.ms
        feedback.sentPacket.size = sizeBytes.bytes
        feedback.sentPacket.pacingInfo = PacedPacketInfo(probeClusterId, minProbes, minBytes)
        feedback.receiveTime = kReferenceTime + arrivalTimeMs.ms
        measuredDataRate = probeBitrateEstimator.handleProbeAndEstimateBitrate(feedback)
    }

    companion object {
        val logger = createLogger()
        val diagnosticContext = DiagnosticContext()
    }
}

class ProbeBitrateEstimatorTest : FreeSpec() {
    init {
        "OneCluster" {
            val test = TestProbeBitrateEstimator()
            test.addPacketFeedback(0, 1000, 0, 10)
            test.addPacketFeedback(0, 1000, 10, 20)
            test.addPacketFeedback(0, 1000, 20, 30)
            test.addPacketFeedback(0, 1000, 30, 40)

            test.measuredDataRate!!.bps shouldBeInRange (800000L plusOrMinus 10L)
        }

        "OneClusterTooFewProbes" {
            val test = TestProbeBitrateEstimator()
            test.addPacketFeedback(0, 1000, 0, 10)
            test.addPacketFeedback(0, 1000, 10, 20)
            test.addPacketFeedback(0, 1000, 20, 30)

            test.measuredDataRate shouldBe null
        }

        "OneClusterTooFewBytes" {
            val test = TestProbeBitrateEstimator()
            val kMinBytes = 6000
            test.addPacketFeedback(0, 1000, 0, 10, kDefaultMinProbes, kMinBytes)
            test.addPacketFeedback(0, 1000, 10, 20, kDefaultMinProbes, kMinBytes)
            test.addPacketFeedback(0, 1000, 20, 30, kDefaultMinProbes, kMinBytes)
            test.addPacketFeedback(0, 1000, 30, 40, kDefaultMinProbes, kMinBytes)

            test.measuredDataRate shouldBe null
        }

        "SmallCluster" {
            val test = TestProbeBitrateEstimator()
            val kMinBytes = 1000
            test.addPacketFeedback(0, 150, 0, 10, kDefaultMinProbes, kMinBytes)
            test.addPacketFeedback(0, 150, 10, 20, kDefaultMinProbes, kMinBytes)
            test.addPacketFeedback(0, 150, 20, 30, kDefaultMinProbes, kMinBytes)
            test.addPacketFeedback(0, 150, 30, 40, kDefaultMinProbes, kMinBytes)
            test.addPacketFeedback(0, 150, 40, 50, kDefaultMinProbes, kMinBytes)
            test.addPacketFeedback(0, 150, 50, 60, kDefaultMinProbes, kMinBytes)

            test.measuredDataRate!!.bps shouldBeInRange (120000L plusOrMinus 10L)
        }

        "LargeCluster" {
            val test = TestProbeBitrateEstimator()
            val kMinProbes = 30
            val kMinBytes = 312500

            var sendTime = 0L
            var receiveTime = 5L
            repeat(25) {
                test.addPacketFeedback(0, 12500, sendTime, receiveTime, kMinProbes, kMinBytes)
                ++sendTime
                ++receiveTime
            }

            test.measuredDataRate!!.bps shouldBeInRange (100000000L plusOrMinus 10L)
        }

        "FastReceive" {
            val test = TestProbeBitrateEstimator()
            test.addPacketFeedback(0, 1000, 0, 15)
            test.addPacketFeedback(0, 1000, 10, 30)
            test.addPacketFeedback(0, 1000, 20, 35)
            test.addPacketFeedback(0, 1000, 30, 40)

            test.measuredDataRate!!.bps shouldBeInRange (800000L plusOrMinus 10L)
        }

        "TooFastReceive" {
            val test = TestProbeBitrateEstimator()
            test.addPacketFeedback(0, 1000, 0, 19)
            test.addPacketFeedback(0, 1000, 10, 22)
            test.addPacketFeedback(0, 1000, 20, 25)
            test.addPacketFeedback(0, 1000, 30, 27)

            test.measuredDataRate shouldBe null
        }

        "SlowReceive" {
            val test = TestProbeBitrateEstimator()
            test.addPacketFeedback(0, 1000, 0, 10)
            test.addPacketFeedback(0, 1000, 10, 40)
            test.addPacketFeedback(0, 1000, 20, 70)
            test.addPacketFeedback(0, 1000, 30, 85)
            // Expected send rate = 800 kbps, expected receive rate = 320 kbps.

            test.measuredDataRate!!.bps shouldBeInRange
                ((kTargetUtilizationFraction * 320000.0).toLong() plusOrMinus 10)
        }

        "BurstReceive" {
            val test = TestProbeBitrateEstimator()
            test.addPacketFeedback(0, 1000, 0, 50)
            test.addPacketFeedback(0, 1000, 10, 50)
            test.addPacketFeedback(0, 1000, 20, 50)
            test.addPacketFeedback(0, 1000, 30, 50)

            test.measuredDataRate shouldBe null
        }

        "MultipleClusters" {
            val test = TestProbeBitrateEstimator()
            test.addPacketFeedback(0, 1000, 0, 10)
            test.addPacketFeedback(0, 1000, 10, 20)
            test.addPacketFeedback(0, 1000, 20, 30)
            test.addPacketFeedback(0, 1000, 40, 60)
            // Expected send rate = 600 kbps, expected receive rate = 480 kbps.
            test.measuredDataRate!!.bps shouldBeInRange
                ((kTargetUtilizationFraction * 480000.0).toLong() plusOrMinus 10)

            test.addPacketFeedback(0, 1000, 50, 60)
            // Expected send rate = 640 kbps, expected receive rate = 640 kbps.
            test.measuredDataRate!!.bps shouldBeInRange (640000L plusOrMinus 10L)

            test.addPacketFeedback(1, 1000, 60, 70)
            test.addPacketFeedback(1, 1000, 65, 77)
            test.addPacketFeedback(1, 1000, 70, 84)
            test.addPacketFeedback(1, 1000, 75, 90)
            // Expected send rate = 1600 kbps, expected receive rate = 1200 kbps.

            test.measuredDataRate!!.bps shouldBeInRange
                ((kTargetUtilizationFraction * 1200000.0).toLong() plusOrMinus 10)
        }

        "IgnoreOldClusters" {
            val test = TestProbeBitrateEstimator()
            test.addPacketFeedback(0, 1000, 0, 10)
            test.addPacketFeedback(0, 1000, 10, 20)
            test.addPacketFeedback(0, 1000, 20, 30)

            test.addPacketFeedback(1, 1000, 60, 70)
            test.addPacketFeedback(1, 1000, 65, 77)
            test.addPacketFeedback(1, 1000, 70, 84)
            test.addPacketFeedback(1, 1000, 75, 90)
            // Expected send rate = 1600 kbps, expected receive rate = 1200 kbps.

            test.measuredDataRate!!.bps shouldBeInRange
                ((kTargetUtilizationFraction * 1200000.0).toLong() plusOrMinus 10)

            // Coming in 6s later
            test.addPacketFeedback(0, 1000, 40 + 6000, 60 + 6000)
            test.measuredDataRate shouldBe null
        }

        "IgnoreSizeLastSendPacket" {
            val test = TestProbeBitrateEstimator()
            test.addPacketFeedback(0, 1000, 0, 10)
            test.addPacketFeedback(0, 1000, 10, 20)
            test.addPacketFeedback(0, 1000, 20, 30)
            test.addPacketFeedback(0, 1000, 30, 40)
            test.addPacketFeedback(0, 1500, 40, 50)
            // Expected send rate = 800 kbps, expected receive rate = 900 kbps.

            test.measuredDataRate!!.bps shouldBeInRange (800000L plusOrMinus 10L)
        }

        "IgnoreSizeFirstReceivePacket" {
            val test = TestProbeBitrateEstimator()
            test.addPacketFeedback(0, 1500, 0, 10)
            test.addPacketFeedback(0, 1000, 10, 20)
            test.addPacketFeedback(0, 1000, 20, 30)
            test.addPacketFeedback(0, 1000, 30, 40)
            // Expected send rate = 933 kbps, expected receive rate = 800 kbps.

            test.measuredDataRate!!.bps shouldBeInRange
                ((kTargetUtilizationFraction * 800000.0).toLong() plusOrMinus 10)
        }

        "NoLastEstimatedBitrateBps" {
            val test = TestProbeBitrateEstimator()
            test.probeBitrateEstimator.fetchAndResetLastEstimatedBitrate() shouldBe null
        }

        "FetchLastEstimatedBitrateBps" {
            val test = TestProbeBitrateEstimator()
            test.addPacketFeedback(0, 1000, 0, 10)
            test.addPacketFeedback(0, 1000, 10, 20)
            test.addPacketFeedback(0, 1000, 20, 30)
            test.addPacketFeedback(0, 1000, 30, 40)

            val estimatedBitrate = test.probeBitrateEstimator.fetchAndResetLastEstimatedBitrate()

            estimatedBitrate shouldNotBe null
            estimatedBitrate!!.bps shouldBeInRange (800000L plusOrMinus 10L)
            test.probeBitrateEstimator.fetchAndResetLastEstimatedBitrate() shouldBe null
        }
    }
}

const val kDefaultMinProbes = 5
const val kDefaultMinBytes = 5000
const val kTargetUtilizationFraction = 0.95
