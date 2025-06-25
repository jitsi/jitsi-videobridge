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
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.jitsi.nlj.util.Bandwidth
import org.jitsi.nlj.util.DataSize
import org.jitsi.nlj.util.bps
import org.jitsi.nlj.util.bytes
import org.jitsi.nlj.util.div
import org.jitsi.nlj.util.kbps
import org.jitsi.nlj.util.times
import org.jitsi.utils.NEVER
import org.jitsi.utils.div
import org.jitsi.utils.ms
import org.jitsi.utils.secs
import org.jitsi.utils.times
import java.time.Duration
import java.time.Instant

/**
 * Unit tests for Loss-Based BWE,
 * based on WebRTC modules/congestion_controller/goog_cc/loss_based_bwe_v2_test.cc in
 * WebRTC tag branch-heads/7204 (Chromium 138).
 */

val kObservationDurationLowerBound = 250.ms
val kDelayedIncreaseWindow = 300.ms
const val kMaxIncreaseFactor = 1.5
const val kPacketSize = 15_000

private var transportSequenceNumber = 0L

private fun config(enabled: Boolean, valid: Boolean): LossBasedBweV2.Config {
    return LossBasedBweV2.Config(
        enabled = enabled,
        bandwidthRampupUpperBoundFactor = if (valid) 1.2 else 0.0,
        candidateFactors = doubleArrayOf(1.1, 1.0, 0.95),
        higherBandwidthBiasFactor = 0.01,
        inherentLossLowerBound = 0.001,
        inherentLossUpperBoundBandwidthBalance = 14.kbps,
        inherentLossUpperBoundOffset = 0.9,
        initialInherentLossEstimate = 0.01,
        newtonIterations = 2,
        newtonStepSize = 0.4,
        observationWindowSize = 15,
        sendingRateSmoothingFactor = 0.01,
        instantUpperBoundTemporalWeightFactor = 0.97,
        instantUpperBoundBandwidthBalance = 90.kbps,
        instantUpperBoundLossOffset = 0.1,
        temporalWeightFactor = 0.98,
        minNumObservations = 1,
        observationDurationLowerBound = kObservationDurationLowerBound,
        maxIncreaseFactor = kMaxIncreaseFactor,
        delayedIncreaseWindow = kDelayedIncreaseWindow
    )
}

private fun shortObservationConfig(config: LossBasedBweV2.Config = LossBasedBweV2.Config()): LossBasedBweV2.Config {
    return config.copy(
        minNumObservations = 1,
        observationWindowSize = 2
    )
}

private fun createPacketResultsWithReceivedPackets(firstPacketTimestamp: Instant): List<PacketResult> {
    val enoughFeedback = List(2) { PacketResult() }
    enoughFeedback[0].sentPacket.sequenceNumber = transportSequenceNumber++
    enoughFeedback[1].sentPacket.sequenceNumber = transportSequenceNumber++
    enoughFeedback[0].sentPacket.size = kPacketSize.bytes
    enoughFeedback[1].sentPacket.size = kPacketSize.bytes
    enoughFeedback[0].sentPacket.sendTime = firstPacketTimestamp
    enoughFeedback[1].sentPacket.sendTime = firstPacketTimestamp + kObservationDurationLowerBound
    enoughFeedback[0].receiveTime = firstPacketTimestamp + kObservationDurationLowerBound
    enoughFeedback[1].receiveTime = firstPacketTimestamp + 2 * kObservationDurationLowerBound

    return enoughFeedback
}

private fun createPacketResultsWith10pPacketLossRate(
    firstPacketTimestamp: Instant,
    lostPacketSize: DataSize = kPacketSize.bytes
): List<PacketResult> {
    val enoughFeedback = List(10) { PacketResult() }

    for (i in enoughFeedback.indices) {
        enoughFeedback[i].sentPacket.sequenceNumber = transportSequenceNumber++
        enoughFeedback[i].sentPacket.size = kPacketSize.bytes
        enoughFeedback[i].sentPacket.sendTime = firstPacketTimestamp + i * kObservationDurationLowerBound
        enoughFeedback[i].receiveTime = firstPacketTimestamp + (i + 1) * kObservationDurationLowerBound
    }
    enoughFeedback[9].receiveTime = NEVER
    enoughFeedback[9].sentPacket.size = lostPacketSize
    return enoughFeedback
}

private fun createPacketResultsWith50pPacketLossRate(firstPacketTimestamp: Instant): List<PacketResult> {
    val enoughFeedback = List(2) { PacketResult() }
    enoughFeedback[0].sentPacket.sequenceNumber = transportSequenceNumber++
    enoughFeedback[1].sentPacket.sequenceNumber = transportSequenceNumber++
    enoughFeedback[0].sentPacket.size = kPacketSize.bytes
    enoughFeedback[1].sentPacket.size = kPacketSize.bytes
    enoughFeedback[0].sentPacket.sendTime = firstPacketTimestamp
    enoughFeedback[1].sentPacket.sendTime = firstPacketTimestamp + kObservationDurationLowerBound
    enoughFeedback[0].receiveTime = firstPacketTimestamp + kObservationDurationLowerBound
    enoughFeedback[1].receiveTime = NEVER
    return enoughFeedback
}

private fun createPacketResultsWith100pLossRate(
    firstPacketTimestamp: Instant,
    numPackets: Int = 2
): List<PacketResult> {
    val enoughFeedback = List(numPackets) { PacketResult() }
    for (i in 0 until numPackets - 1) {
        enoughFeedback[i].sentPacket.sequenceNumber = transportSequenceNumber++
        enoughFeedback[i].sentPacket.size = kPacketSize.bytes
        enoughFeedback[i].sentPacket.sendTime = firstPacketTimestamp + (i * 10).ms
        enoughFeedback[i].receiveTime = NEVER
    }
    enoughFeedback[numPackets - 1].sentPacket.sequenceNumber = transportSequenceNumber++
    enoughFeedback[numPackets - 1].sentPacket.size = kPacketSize.bytes
    enoughFeedback[numPackets - 1].sentPacket.sendTime = firstPacketTimestamp + kObservationDurationLowerBound
    enoughFeedback[numPackets - 1].receiveTime = NEVER
    return enoughFeedback
}

class LossBasedBweV2Test : FreeSpec() {
    init {
        "EnabledWhenGivenValidConfigurationValues" {
            val config = config(enabled = true, valid = true)
            val lossBasedBandwidthEstimator = LossBasedBweV2(config)
            lossBasedBandwidthEstimator.isEnabled() shouldBe true
        }
        "DisabledWhenGivenDisabledConfiguration" {
            val config = config(enabled = false, valid = true)
            val lossBasedBandwidthEstimator = LossBasedBweV2(config)
            lossBasedBandwidthEstimator.isEnabled() shouldBe false
        }
        "DisabledWhenGivenNonValidConfigurationValues" {
            val config = config(enabled = true, valid = false)
            val lossBasedBandwidthEstimator = LossBasedBweV2(config)
            lossBasedBandwidthEstimator.isEnabled() shouldBe false
        }
        "DisabledWhenGivenNonPositiveCandidateFactor" {
            val configNegativeCandidateFactor =
                LossBasedBweV2.Config(candidateFactors = doubleArrayOf(-1.3, 1.1))
            val lossBasedBandwidthEstimator1 = LossBasedBweV2(configNegativeCandidateFactor)
            lossBasedBandwidthEstimator1.isEnabled() shouldBe false

            val configZeroCandidateFactor =
                LossBasedBweV2.Config(candidateFactors = doubleArrayOf(-0.0, 1.1))
            val lossBasedBandwidthEstimator2 = LossBasedBweV2(configZeroCandidateFactor)
            lossBasedBandwidthEstimator2.isEnabled() shouldBe false
        }
        "DisabledWhenGivenConfigurationThatDoesNotAllowGeneratingCandidates" {
            val config =
                LossBasedBweV2.Config(
                    candidateFactors = doubleArrayOf(1.0),
                    appendAcknowledgedRateCandidate = false,
                    appendDelayBasedEstimateCandidate = false
                )
            val lossBasedBandwidthEstimator = LossBasedBweV2(config)
            lossBasedBandwidthEstimator.isEnabled() shouldBe false
        }
        "ReturnsDelayBasedEstimateWhenDisabled" {
            val config = config(enabled = false, valid = true,)
            val lossBasedBandwidthEstimator = LossBasedBweV2(config)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                listOf(),
                100.kbps,
                false
            )
            lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBe 100.kbps
        }
        "ReturnsDelayBasedEstimateWhenWhenGivenNonValidConfigurationValues" {
            val config = config(enabled = true, valid = false)
            val lossBasedBandwidthEstimator = LossBasedBweV2(config)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                listOf(),
                100.kbps,
                false
            )
            lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBe 100.kbps
        }
        "BandwidthEstimateGivenInitializationAndThenFeedback" {
            val enoughFeedback = createPacketResultsWithReceivedPackets(Instant.ofEpochMilli(0))

            val config = config(enabled = true, valid = true)
            val lossBasedBandwidthEstimator = LossBasedBweV2(config)

            lossBasedBandwidthEstimator.setBandwidthEstimate(600.kbps)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback,
                Bandwidth.INFINITY,
                false
            )

            lossBasedBandwidthEstimator.isReady() shouldBe true
            lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate.isFinite() shouldBe true
        }
        "NoBandwidthEstimateGivenNoInitialization" {
            val enoughFeedback = createPacketResultsWithReceivedPackets(Instant.EPOCH)

            val config = config(enabled = true, valid = true)
            val lossBasedBandwidthEstimator = LossBasedBweV2(config)

            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback,
                Bandwidth.INFINITY,
                false
            )
            lossBasedBandwidthEstimator.isReady() shouldBe false
            lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBe Bandwidth.INFINITY
        }
        "NoBandwidthEstimateGivenNotEnoughFeedback" {
            val notEnoughFeedback = List(2) { PacketResult() }
            notEnoughFeedback[0].sentPacket.size = 15_000.bytes
            notEnoughFeedback[1].sentPacket.size = 15_000.bytes
            notEnoughFeedback[0].sentPacket.sendTime = Instant.EPOCH
            notEnoughFeedback[1].sentPacket.sendTime = Instant.EPOCH + kObservationDurationLowerBound / 2
            notEnoughFeedback[0].receiveTime = Instant.EPOCH + kObservationDurationLowerBound / 2
            notEnoughFeedback[1].receiveTime = Instant.EPOCH + kObservationDurationLowerBound

            val config = config(enabled = true, valid = true)
            val lossBasedBandwidthEstimator = LossBasedBweV2(config)

            lossBasedBandwidthEstimator.setBandwidthEstimate(600.kbps)

            lossBasedBandwidthEstimator.isReady() shouldBe false
            lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBe Bandwidth.INFINITY

            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                notEnoughFeedback,
                Bandwidth.INFINITY,
                false
            )

            lossBasedBandwidthEstimator.isReady() shouldBe false
            lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBe Bandwidth.INFINITY
        }

        "SetValueIsTheEstimateUntilAdditionalFeedbackHasBeenReceived" {
            val enoughFeedback1 = createPacketResultsWithReceivedPackets(Instant.EPOCH)
            val enoughFeedback2 = createPacketResultsWithReceivedPackets(
                Instant.EPOCH + 2 * kObservationDurationLowerBound
            )

            val config = config(enabled = true, valid = true)
            val lossBasedBandwidthEstimator = LossBasedBweV2(config)

            lossBasedBandwidthEstimator.setBandwidthEstimate(600.kbps)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback1,
                Bandwidth.INFINITY,
                false
            )

            lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldNotBe 600.kbps

            lossBasedBandwidthEstimator.setBandwidthEstimate(600.kbps)

            lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBe 600.kbps

            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback2,
                Bandwidth.INFINITY,
                false
            )

            lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldNotBe 600.kbps
        }
        "SetAcknowledgedBitrateOnlyAffectsTheBweWhenAdditionalFeedbackIsGiven" {
            val enoughFeedback1 = createPacketResultsWithReceivedPackets(Instant.EPOCH)
            val enoughFeedback2 = createPacketResultsWithReceivedPackets(
                Instant.EPOCH + 2 * kObservationDurationLowerBound
            )

            val config = config(enabled = true, valid = true)
            val lossBasedBandwidthEstimator1 = LossBasedBweV2(config)
            val lossBasedBandwidthEstimator2 = LossBasedBweV2(config)

            lossBasedBandwidthEstimator1.setBandwidthEstimate(600.kbps)
            lossBasedBandwidthEstimator2.setBandwidthEstimate(600.kbps)

            lossBasedBandwidthEstimator1.updateBandwidthEstimate(
                enoughFeedback1,
                Bandwidth.INFINITY,
                false
            )

            lossBasedBandwidthEstimator2.updateBandwidthEstimate(
                enoughFeedback1,
                Bandwidth.INFINITY,
                false
            )

            lossBasedBandwidthEstimator1.getLossBasedResult().bandwidthEstimate shouldBe 660.kbps

            lossBasedBandwidthEstimator1.setAcknowledgedBitrate(900.kbps)

            lossBasedBandwidthEstimator1.getLossBasedResult().bandwidthEstimate shouldBe 660.kbps

            lossBasedBandwidthEstimator1.updateBandwidthEstimate(
                enoughFeedback2,
                Bandwidth.INFINITY,
                false
            )

            lossBasedBandwidthEstimator2.updateBandwidthEstimate(
                enoughFeedback2,
                Bandwidth.INFINITY,
                false
            )

            lossBasedBandwidthEstimator1.getLossBasedResult().bandwidthEstimate shouldNotBe
                lossBasedBandwidthEstimator2.getLossBasedResult().bandwidthEstimate
        }
        "BandwidthEstimateIsCappedToBeTcpFairGivenTooHighLossRate" {
            val enoughFeedbackNoReceivedPackets = createPacketResultsWith100pLossRate(Instant.EPOCH)

            val config = config(enabled = true, valid = true)
            val lossBasedBandwidthEstimator = LossBasedBweV2(config)

            lossBasedBandwidthEstimator.setBandwidthEstimate(600.kbps)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedbackNoReceivedPackets,
                Bandwidth.INFINITY,
                false
            )

            lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBe 100.kbps
        }

        // When network is normal, estimate can increase but never be higher than
        // the delay based estimate.
        "BandwidthEstimateCappedByDelayBasedEstimateWhenNetworkNormal" {
            val enoughFeedback1 = createPacketResultsWithReceivedPackets(Instant.EPOCH)
            val enoughFeedback2 = createPacketResultsWithReceivedPackets(
                Instant.EPOCH + 2 * kObservationDurationLowerBound
            )

            val config = config(enabled = true, valid = true)
            val lossBasedBandwidthEstimator = LossBasedBweV2(config)

            lossBasedBandwidthEstimator.setBandwidthEstimate(600.kbps)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback1,
                Bandwidth.INFINITY,
                false
            )

            // If the delay based estimate is infinity, then loss based estimate increases
            // and not bounded by delay based estimate.
            lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBeGreaterThan 600.kbps
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback2,
                500.kbps,
                false
            )
            // If the delay based estimate is not infinity, then loss based estimate is
            // bounded by delay based estimate.
            lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBe 500.kbps
        }

        // When loss based bwe receives a strong signal of overusing and an increase in
        // loss rate, it should acked bitrate for emegency backoff.
        "UseAckedBitrateForEmegencyBackOff" {
            // Create two packet results, first packet has 50% loss rate, second packet
            // has 100% loss rate.
            val enoughFeedback1 = createPacketResultsWith50pPacketLossRate(Instant.EPOCH)
            val enoughFeedback2 = createPacketResultsWith100pLossRate(
                Instant.EPOCH + 2 * kObservationDurationLowerBound
            )

            val config = config(enabled = true, valid = true)
            val lossBasedBandwidthEstimator = LossBasedBweV2(config)

            lossBasedBandwidthEstimator.setBandwidthEstimate(600.kbps)
            val ackedBitrate = 300.kbps

            lossBasedBandwidthEstimator.setAcknowledgedBitrate(ackedBitrate)
            // Update estimate when network is overusing, and 50% loss rate.
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback1,
                Bandwidth.INFINITY,
                false
            )
            // Update estimate again when network is continuously overusing, and 100%
            // loss rate.
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback2,
                Bandwidth.INFINITY,
                false
            )
            // The estimate bitrate now is backed off based on acked bitrate.
            lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBeLessThanOrEqualTo
                ackedBitrate
        }

        // When receiving the same packet feedback, loss based bwe ignores the feedback
        // and returns the current estimate.
        "NoBweChangeIfObservationDurationUnchanged" {
            val enoughFeedback1 = createPacketResultsWithReceivedPackets(Instant.EPOCH)
            val config = config(enabled = true, valid = true)
            val lossBasedBandwidthEstimator = LossBasedBweV2(config)
            lossBasedBandwidthEstimator.setBandwidthEstimate(600.kbps)
            lossBasedBandwidthEstimator.setAcknowledgedBitrate(300.kbps)

            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback1,
                Bandwidth.INFINITY,
                false
            )

            val estimate1 = lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate

            // Use the same feedback and check if the estimate is unchanged.
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback1,
                Bandwidth.INFINITY,
                false
            )

            val estimate2 = lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate
            estimate1 shouldBe estimate2
        }

        // When receiving feedback of packets that were sent within an observation
        // duration, and network is in the normal state, loss based bwe returns the
        // current estimate.
        "NoBweChangeIfObservationDurationIsSmallAndNetworkNormal" {
            val enoughFeedback1 = createPacketResultsWithReceivedPackets(Instant.EPOCH)
            val enoughFeedback2 = createPacketResultsWithReceivedPackets(
                Instant.EPOCH + kObservationDurationLowerBound - 1.ms
            )
            val config = config(enabled = true, valid = true)
            val lossBasedBandwidthEstimator = LossBasedBweV2(config)
            lossBasedBandwidthEstimator.setBandwidthEstimate(600.kbps)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback1,
                Bandwidth.INFINITY,
                false
            )
            val estimate1 = lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback2,
                Bandwidth.INFINITY,
                false
            )
            val estimate2 = lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate
            estimate2 shouldBe estimate1
        }

        // When receiving feedback of packets that were sent within an observation
        // duration, and network is in the underusing state, loss based bwe returns the
        // current estimate.
        "NoBweIncreaseIfObservationDurationIsSmallAndNetworkUnderusing" {
            val enoughFeedback1 = createPacketResultsWithReceivedPackets(Instant.EPOCH)
            val enoughFeedback2 = createPacketResultsWithReceivedPackets(
                Instant.EPOCH + kObservationDurationLowerBound - 1.ms
            )
            val config = config(enabled = true, valid = true)
            val lossBasedBandwidthEstimator = LossBasedBweV2(config)
            lossBasedBandwidthEstimator.setBandwidthEstimate(600.kbps)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback1,
                Bandwidth.INFINITY,
                false
            )
            val estimate1 = lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback2,
                Bandwidth.INFINITY,
                false
            )
            val estimate2 = lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate
            estimate2 shouldBeLessThanOrEqualTo estimate1
        }

        "IncreaseToDelayBasedEstimateIfNoLossOrDelayIncrease" {
            val enoughFeedback1 = createPacketResultsWithReceivedPackets(Instant.EPOCH)
            val enoughFeedback2 = createPacketResultsWithReceivedPackets(
                Instant.EPOCH + 2 * kObservationDurationLowerBound
            )
            val config = config(enabled = true, valid = true)
            val lossBasedBandwidthEstimator = LossBasedBweV2(config)
            val delayBasedEstimate = 5000.kbps
            lossBasedBandwidthEstimator.setBandwidthEstimate(600.kbps)

            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback1,
                delayBasedEstimate,
                false
            )
            lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBe delayBasedEstimate
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback2,
                delayBasedEstimate,
                false
            )
            lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBe delayBasedEstimate
        }

        "IncreaseByHoldFactorAfterLossBasedBweBacksOff" {
            val config = shortObservationConfig()
            val lossBasedBandwidthEstimator = LossBasedBweV2(config)
            val delayBasedEstimate = 5000.kbps
            val ackedRate = 100.kbps
            lossBasedBandwidthEstimator.setBandwidthEstimate(600.kbps)
            lossBasedBandwidthEstimator.setAcknowledgedBitrate(ackedRate)

            // Create some loss to create the loss limited scenario.
            val enoughFeedback1 = createPacketResultsWith100pLossRate(Instant.EPOCH)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback1,
                delayBasedEstimate,
                inAlr = false
            )
            val resultAtLoss = lossBasedBandwidthEstimator.getLossBasedResult()

            // Network recovers after loss.
            var feedbackCount = 2
            while (lossBasedBandwidthEstimator.getLossBasedResult().state != LossBasedState.kIncreaseUsingPadding) {
                lossBasedBandwidthEstimator.updateBandwidthEstimate(
                    createPacketResultsWithReceivedPackets(
                        Instant.EPOCH + kObservationDurationLowerBound * feedbackCount
                    ),
                    delayBasedEstimate = delayBasedEstimate,
                    inAlr = false
                )
                feedbackCount++
            }

            val resultAfterRecovery = lossBasedBandwidthEstimator.getLossBasedResult()
            // BwRampupUpperBoundInHoldFactor is 1.2.
            resultAfterRecovery.bandwidthEstimate shouldBe resultAtLoss.bandwidthEstimate * 1.2
        }

        "LossBasedStateIsDelayBasedEstimateAfterNetworkRecovering" {
            val config = shortObservationConfig(
                LossBasedBweV2.Config(
                    candidateFactors = doubleArrayOf(100.0, 1.0, 0.5),
                    observationDurationLowerBound = 200.ms,
                    instantUpperBoundBandwidthBalance = 10000.kbps,
                    maxIncreaseFactor = 100.0,
                    notIncreaseIfInherentLossLessThanAverageLoss = false
                )
            )
            val lossBasedBandwidthEstimator = LossBasedBweV2(config)
            val delayBasedEstimate = 600.kbps
            val ackedRate = 300.kbps
            lossBasedBandwidthEstimator.setBandwidthEstimate(600.kbps)
            lossBasedBandwidthEstimator.setAcknowledgedBitrate(ackedRate)

            // Create some loss to create the loss limited scenario.
            val enoughFeedback1 = createPacketResultsWith100pLossRate(Instant.EPOCH)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback1,
                delayBasedEstimate,
                inAlr = false
            )
            lossBasedBandwidthEstimator.getLossBasedResult().state shouldBe LossBasedState.kDecreasing

            // Network recovers after loss.
            val enoughFeedback2 = createPacketResultsWithReceivedPackets(
                Instant.EPOCH + kObservationDurationLowerBound
            )
            lossBasedBandwidthEstimator.setAcknowledgedBitrate(600.kbps)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback2,
                delayBasedEstimate,
                inAlr = false
            )
            lossBasedBandwidthEstimator.getLossBasedResult().state shouldBe LossBasedState.kDelayBasedEstimate

            // Network recovers continuing.
            val enoughFeedback3 = createPacketResultsWithReceivedPackets(
                Instant.EPOCH + kObservationDurationLowerBound * 2
            )
            lossBasedBandwidthEstimator.setAcknowledgedBitrate(600.kbps)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback3,
                delayBasedEstimate,
                inAlr = false
            )
            lossBasedBandwidthEstimator.getLossBasedResult().state shouldBe LossBasedState.kDelayBasedEstimate
        }

        "LossBasedStateIsNotDelayBasedEstimateIfDelayBasedEstimateInfinite" {
            val config = shortObservationConfig(
                LossBasedBweV2.Config(
                    candidateFactors = doubleArrayOf(100.0, 1.0, 0.5),
                    instantUpperBoundBandwidthBalance = 10000.kbps,
                    maxIncreaseFactor = 100.0
                )
            )
            val lossBasedBandwidthEstimator = LossBasedBweV2(config)
            lossBasedBandwidthEstimator.setBandwidthEstimate(600.kbps)

            // Create some loss to create the loss limited scenario.
            val enoughFeedback1 = createPacketResultsWith100pLossRate(Instant.EPOCH)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback1,
                delayBasedEstimate = Bandwidth.INFINITY,
                inAlr = false
            )
            lossBasedBandwidthEstimator.getLossBasedResult().state shouldBe LossBasedState.kDecreasing

            // Network recovers after loss.
            val enoughFeedback2 = createPacketResultsWithReceivedPackets(
                Instant.EPOCH + kObservationDurationLowerBound
            )
            lossBasedBandwidthEstimator.setAcknowledgedBitrate(600.kbps)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback2,
                delayBasedEstimate = Bandwidth.INFINITY,
                inAlr = false
            )
            lossBasedBandwidthEstimator.getLossBasedResult().state shouldNotBe LossBasedState.kDelayBasedEstimate
        }

        // Ensure that the state can switch to kIncrease even when the bandwidth is
        // bounded by acked bitrate.
        "EnsureIncreaseEvenIfAckedBitrateBound" {
            val config = shortObservationConfig(
                LossBasedBweV2.Config(
                    lossThresholdOfHighBandwidthPreference = 0.99,
                    bandwidthRampupUpperBoundFactor = 1.2,
                    // Set InstantUpperBoundBwBalance high to disable InstantUpperBound cap.
                    instantUpperBoundBandwidthBalance = 10000.kbps
                )
            )
            val enoughFeedback1 =
                createPacketResultsWith100pLossRate(Instant.EPOCH)
            val lossBasedBandwidthEstimator = LossBasedBweV2(config)
            val delayBasedEstimate = 5000.kbps

            lossBasedBandwidthEstimator.setBandwidthEstimate(600.kbps)
            lossBasedBandwidthEstimator.setAcknowledgedBitrate(300.kbps)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(enoughFeedback1, delayBasedEstimate, inAlr = false)
            lossBasedBandwidthEstimator.getLossBasedResult().state shouldBe LossBasedState.kDecreasing
            var result = lossBasedBandwidthEstimator.getLossBasedResult()
            val estimate1 = result.bandwidthEstimate
            estimate1.kbps shouldBeLessThan 600.0

            // Set a low acked bitrate.
            lossBasedBandwidthEstimator.setAcknowledgedBitrate(estimate1 / 2)

            var feedbackCount = 1
            while (result.state != LossBasedState.kIncreaseUsingPadding) {
                lossBasedBandwidthEstimator.updateBandwidthEstimate(
                    createPacketResultsWithReceivedPackets(
                        Instant.EPOCH + feedbackCount++ * kObservationDurationLowerBound
                    ),
                    delayBasedEstimate,
                    inAlr = false
                )
                result = lossBasedBandwidthEstimator.getLossBasedResult()
            }

            result.state shouldBe LossBasedState.kIncreaseUsingPadding
            // The estimate increases by 1kbps.
            result.bandwidthEstimate shouldBe estimate1 + 1.bps
        }

        // After loss based bwe backs off, the next estimate is capped by
        // a factor of acked bitrate.
        "IncreaseByFactorOfAckedBitrateAfterLossBasedBweBacksOff" {
            val config = shortObservationConfig(
                LossBasedBweV2.Config(
                    lossThresholdOfHighBandwidthPreference = 0.99,
                    bandwidthRampupUpperBoundFactor = 1.2,
                    // Set InstantUpperBoundBwBalance high to disable InstantUpperBound cap.
                    instantUpperBoundBandwidthBalance = 10000.kbps
                )
            )
            val enoughFeedback1 = createPacketResultsWith100pLossRate(Instant.EPOCH)
            val lossBasedBandwidthEstimator = LossBasedBweV2(config)
            val delayBasedEstimate = 5000.kbps

            lossBasedBandwidthEstimator.setBandwidthEstimate(600.kbps)
            lossBasedBandwidthEstimator.setAcknowledgedBitrate(300.kbps)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback1,
                delayBasedEstimate,
                inAlr = false
            )
            lossBasedBandwidthEstimator.getLossBasedResult().state shouldBe LossBasedState.kDecreasing
            var result = lossBasedBandwidthEstimator.getLossBasedResult()
            val estimate1 = result.bandwidthEstimate
            estimate1.kbps shouldBeLessThan 600.0

            lossBasedBandwidthEstimator.setAcknowledgedBitrate(estimate1 * 0.9)
            var feedbackCount = 1
            while (result.state != LossBasedState.kIncreaseUsingPadding) {
                lossBasedBandwidthEstimator.updateBandwidthEstimate(
                    createPacketResultsWithReceivedPackets(
                        Instant.EPOCH + feedbackCount++ * kObservationDurationLowerBound
                    ),
                    delayBasedEstimate,
                    inAlr = false
                )
                result = lossBasedBandwidthEstimator.getLossBasedResult()
            }
            result.state shouldBe LossBasedState.kIncreaseUsingPadding

            // The estimate is capped by acked_bitrate * BwRampupUpperBoundFactor.
            result.bandwidthEstimate shouldBe estimate1 * 0.9 * 1.2

            // But if acked bitrate decreases, BWE does not decrease when there is no
            // loss.
            lossBasedBandwidthEstimator.setAcknowledgedBitrate(estimate1 * 0.9)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                createPacketResultsWithReceivedPackets(
                    Instant.EPOCH + feedbackCount++ * kObservationDurationLowerBound
                ),
                delayBasedEstimate,
                inAlr = false
            )
            lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBe result.bandwidthEstimate
        }

        "EstimateBitrateIsBoundedDuringDelayedWindowAfterLossBasedBweBacksOff" {
            val enoughFeedback1 = createPacketResultsWithReceivedPackets(Instant.EPOCH)
            val enoughFeedback2 = createPacketResultsWith50pPacketLossRate(
                Instant.EPOCH + kDelayedIncreaseWindow - 2.ms
            )
            val enoughFeedback3 = createPacketResultsWithReceivedPackets(
                Instant.EPOCH + kDelayedIncreaseWindow - 1.ms
            )
            val config = config(enabled = true, valid = true)
            val lossBasedBandwidthEstimator = LossBasedBweV2(config)
            val delayBasedEstimate = 5000.kbps

            lossBasedBandwidthEstimator.setBandwidthEstimate(600.kbps)
            lossBasedBandwidthEstimator.setAcknowledgedBitrate(300.kbps)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback1,
                delayBasedEstimate,
                inAlr = false
            )
            // Increase the acknowledged bitrate to make sure that the estimate is not
            // capped too low.
            lossBasedBandwidthEstimator.setAcknowledgedBitrate(5000.kbps)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback2,
                delayBasedEstimate,
                inAlr = false
            )

            // The estimate is capped by current_estimate * kMaxIncreaseFactor because
            // it recently backed off.
            val estimate2 = lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate

            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback3,
                delayBasedEstimate,
                inAlr = false
            )
            // The latest estimate is the same as the previous estimate since the sent
            // packets were sent within the DelayedIncreaseWindow.
            lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBe estimate2
        }

        // The estimate is not bounded after the delayed increase window.
        "KeepIncreasingEstimateAfterDelayedIncreaseWindow" {
            val enoughFeedback1 = createPacketResultsWithReceivedPackets(Instant.EPOCH)
            val enoughFeedback2 =
                createPacketResultsWithReceivedPackets(Instant.EPOCH + kDelayedIncreaseWindow - 1.ms)
            val enoughFeedback3 =
                createPacketResultsWithReceivedPackets(Instant.EPOCH + kDelayedIncreaseWindow + 1.ms)
            val config = config(enabled = true, valid = true)
            val lossBasedBandwidthEstimator = LossBasedBweV2(config)
            val delayBasedEstimate = 5000.kbps

            lossBasedBandwidthEstimator.setBandwidthEstimate(600.kbps)
            lossBasedBandwidthEstimator.setAcknowledgedBitrate(300.kbps)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback1,
                delayBasedEstimate,
                inAlr = false
            )
            // Increase the acknowledged bitrate to make sure that the estimate is not
            // capped too low.
            lossBasedBandwidthEstimator.setAcknowledgedBitrate(5000.kbps)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback2,
                delayBasedEstimate,
                inAlr = false
            )

            // The estimate is capped by current_estimate * kMaxIncreaseFactor because
            // it recently backed off.
            val estimate2 = lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate

            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback3,
                delayBasedEstimate,
                inAlr = false
            )
            lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBeGreaterThanOrEqualTo
                estimate2
        }

        "NotIncreaseIfInherentLossLessThanAverageLoss" {
            val config = shortObservationConfig(
                LossBasedBweV2.Config(
                    candidateFactors = doubleArrayOf(1.2),
                    notIncreaseIfInherentLossLessThanAverageLoss = true
                )
            )
            val lossBasedBandwidthEstimator = LossBasedBweV2(config)

            lossBasedBandwidthEstimator.setBandwidthEstimate(600.kbps)

            val enoughFeedback10pLoss1 = createPacketResultsWith10pPacketLossRate(Instant.EPOCH)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback10pLoss1,
                delayBasedEstimate = Bandwidth.INFINITY,
                inAlr = false
            )

            val enoughFeedback10pLoss2 =
                createPacketResultsWith10pPacketLossRate(Instant.EPOCH + kObservationDurationLowerBound)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback10pLoss2,
                delayBasedEstimate = Bandwidth.INFINITY,
                inAlr = false
            )

            // Do not increase the bitrate because inherent loss is less than average loss
            lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBe 600.kbps
        }

        "SelectHighBandwidthCandidateIfLossRateIsLessThanThreshold" {
            val config = shortObservationConfig(
                LossBasedBweV2.Config(
                    lossThresholdOfHighBandwidthPreference = 0.20,
                    notIncreaseIfInherentLossLessThanAverageLoss = false
                )
            )
            val lossBasedBandwidthEstimator = LossBasedBweV2(config)
            val delayBasedEstimate = 5000.kbps

            lossBasedBandwidthEstimator.setBandwidthEstimate(600.kbps)

            val enoughFeedback10pLoss1 = createPacketResultsWith10pPacketLossRate(Instant.EPOCH)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback10pLoss1,
                delayBasedEstimate,
                inAlr = false
            )

            val enoughFeedback10pLoss2 =
                createPacketResultsWith10pPacketLossRate(Instant.EPOCH + kObservationDurationLowerBound)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback10pLoss2,
                delayBasedEstimate,
                inAlr = false
            )

            // Because LossThresholdOfHighBandwidthPreference is 20%, the average loss is
            // 10%, bandwidth estimate should increase.
            lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBeGreaterThan 600.kbps
        }

        "SelectLowBandwidthCandidateIfLossRateIsIsHigherThanThreshold" {
            val config = shortObservationConfig(
                LossBasedBweV2.Config(
                    lossThresholdOfHighBandwidthPreference = 0.05
                )
            )
            val lossBasedBandwidthEstimator = LossBasedBweV2(config)
            val delayBasedEstimate = 5000.kbps

            lossBasedBandwidthEstimator.setBandwidthEstimate(600.kbps)

            val enoughFeedback10pLoss1 = createPacketResultsWith10pPacketLossRate(Instant.EPOCH)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback10pLoss1,
                delayBasedEstimate,
                inAlr = false
            )

            val enoughFeedback10pLoss2 =
                createPacketResultsWith10pPacketLossRate(Instant.EPOCH + kObservationDurationLowerBound)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback10pLoss2,
                delayBasedEstimate,
                inAlr = false
            )

            // Because LossThresholdOfHighBandwidthPreference is 5%, the average loss is
            // 10%, bandwidth estimate should decrease.
            lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBeLessThan 600.kbps
        }

        "EstimateIsNotHigherThanMaxBitrate" {
            val config = config(enabled = true, valid = true)
            val lossBasedBandwidthEstimator = LossBasedBweV2(config)
            lossBasedBandwidthEstimator.setMinMaxBitrate(10.kbps, 1000.kbps)
            lossBasedBandwidthEstimator.setBandwidthEstimate(1000.kbps)
            val enoughFeedback = createPacketResultsWithReceivedPackets(Instant.EPOCH)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback,
                delayBasedEstimate = Bandwidth.INFINITY,
                inAlr = false
            )

            lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBeLessThanOrEqualTo 1000.kbps
        }

        "NotBackOffToAckedRateInAlr" {
            val config = shortObservationConfig(
                LossBasedBweV2.Config(
                    instantUpperBoundBandwidthBalance = 100.kbps
                )
            )
            val lossBasedBandwidthEstimator = LossBasedBweV2(config)
            lossBasedBandwidthEstimator.setMinMaxBitrate(10.kbps, 1000000.kbps)
            val delayBasedEstimate = 5000.kbps
            lossBasedBandwidthEstimator.setBandwidthEstimate(600.kbps)

            val ackedRate = 100.kbps
            lossBasedBandwidthEstimator.setAcknowledgedBitrate(ackedRate)
            val enoughFeedback100pLoss1 = createPacketResultsWith100pLossRate(Instant.EPOCH)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback100pLoss1,
                delayBasedEstimate,
                inAlr = true
            )

            // Make sure that the estimate decreases but higher than acked rate.
            lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBeGreaterThan ackedRate

            lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBeLessThan 600.kbps
        }

        "BackOffToAckedRateIfNotInAlr" {
            val config = shortObservationConfig(
                LossBasedBweV2.Config(
                    instantUpperBoundBandwidthBalance = 100.kbps
                )
            )
            val lossBasedBandwidthEstimator = LossBasedBweV2(config)
            lossBasedBandwidthEstimator.setMinMaxBitrate(10.kbps, 1000000.kbps)
            val delayBasedEstimate = 5000.kbps
            lossBasedBandwidthEstimator.setBandwidthEstimate(600.kbps)

            val ackedRate = 100.kbps
            lossBasedBandwidthEstimator.setAcknowledgedBitrate(ackedRate)
            val enoughFeedback100pLoss1 = createPacketResultsWith100pLossRate(Instant.EPOCH)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback100pLoss1,
                delayBasedEstimate,
                inAlr = false
            )

            // Make sure that the estimate decreases but higher than acked rate.
            lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBe ackedRate
        }

        "NotReadyToUseInStartPhase" {
            val config = shortObservationConfig(
                LossBasedBweV2.Config(
                    useInStartPhase = true
                )
            )
            val lossBasedBandwidthEstimator = LossBasedBweV2(config)
            // Make sure that the estimator is not ready to use in start phase because of
            // lacking TWCC feedback.
            lossBasedBandwidthEstimator.readyToUseInStartPhase() shouldBe false
        }

        "ReadyToUseInStartPhase" {
            val config = shortObservationConfig(
                LossBasedBweV2.Config(
                    useInStartPhase = true
                )
            )
            val lossBasedBandwidthEstimator = LossBasedBweV2(config)
            val enoughFeedback = createPacketResultsWith100pLossRate(Instant.EPOCH)

            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback,
                delayBasedEstimate = 600.kbps,
                inAlr = false
            )
            lossBasedBandwidthEstimator.readyToUseInStartPhase() shouldBe true
        }

        "BoundEstimateByAckedRate" {
            val config = shortObservationConfig(
                LossBasedBweV2.Config(
                    lowerBoundByAckedRateFactor = 1.0
                )
            )
            val lossBasedBandwidthEstimator = LossBasedBweV2(config)
            lossBasedBandwidthEstimator.setMinMaxBitrate(10.kbps, 1000000.kbps)
            lossBasedBandwidthEstimator.setBandwidthEstimate(600.kbps)
            lossBasedBandwidthEstimator.setAcknowledgedBitrate(500.kbps)

            val enoughFeedback100pLossRate = createPacketResultsWith100pLossRate(Instant.EPOCH)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback100pLossRate,
                delayBasedEstimate = Bandwidth.INFINITY,
                inAlr = false
            )

            lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBe 500.kbps
        }

        "NotBoundEstimateByAckedRate" {
            val config = shortObservationConfig(
                LossBasedBweV2.Config(
                    lowerBoundByAckedRateFactor = 0.0
                )
            )
            val lossBasedBandwidthEstimator = LossBasedBweV2(config)
            lossBasedBandwidthEstimator.setMinMaxBitrate(10.kbps, 1000000.kbps)
            lossBasedBandwidthEstimator.setBandwidthEstimate(600.kbps)
            lossBasedBandwidthEstimator.setAcknowledgedBitrate(500.kbps)

            val enoughFeedback100pLossRate = createPacketResultsWith100pLossRate(Instant.EPOCH)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback100pLossRate,
                delayBasedEstimate = Bandwidth.INFINITY,
                inAlr = false
            )

            lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBeLessThan 500.kbps
        }

        "HasDecreaseStateBecauseOfUpperBound" {
            val config = shortObservationConfig(
                LossBasedBweV2.Config(
                    candidateFactors = doubleArrayOf(1.0),
                    instantUpperBoundBandwidthBalance = 10.kbps
                )
            )
            val lossBasedBandwidthEstimator = LossBasedBweV2(config)
            lossBasedBandwidthEstimator.setMinMaxBitrate(10.kbps, 1000000.kbps)
            lossBasedBandwidthEstimator.setBandwidthEstimate(500.kbps)
            lossBasedBandwidthEstimator.setAcknowledgedBitrate(100.kbps)

            val enoughFeedback10pLoss1 = createPacketResultsWith10pPacketLossRate(Instant.EPOCH)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback10pLoss1,
                delayBasedEstimate = Bandwidth.INFINITY,
                inAlr = false
            )

            // Verify that the instant upper bound decreases the estimate, and state is
            // updated to kDecreasing.
            lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBe 200.kbps
            lossBasedBandwidthEstimator.getLossBasedResult().state shouldBe LossBasedState.kDecreasing
        }

        "HasIncreaseStateBecauseOfLowerBound" {
            val config = shortObservationConfig(
                LossBasedBweV2.Config(
                    candidateFactors = doubleArrayOf(1.0),
                    lowerBoundByAckedRateFactor = 10.0
                )
            )
            val lossBasedBandwidthEstimator = LossBasedBweV2(config)
            lossBasedBandwidthEstimator.setMinMaxBitrate(10.kbps, 1000000.kbps)
            lossBasedBandwidthEstimator.setBandwidthEstimate(100.kbps)
            lossBasedBandwidthEstimator.setAcknowledgedBitrate(1.kbps)

            // Network has a high loss to create a loss scenario.
            val enoughFeedback50pLoss1 = createPacketResultsWith50pPacketLossRate(Instant.EPOCH)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback50pLoss1,
                delayBasedEstimate = Bandwidth.INFINITY,
                inAlr = false
            )

            lossBasedBandwidthEstimator.getLossBasedResult().state shouldBe LossBasedState.kDecreasing

            // Network still has a high loss, but better acked rate.
            lossBasedBandwidthEstimator.setAcknowledgedBitrate(200.kbps)
            val enoughFeedback50pLoss2 =
                createPacketResultsWith50pPacketLossRate(Instant.EPOCH + kObservationDurationLowerBound * 2)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback50pLoss2,
                delayBasedEstimate = Bandwidth.INFINITY,
                inAlr = false
            )

            // Verify that the instant lower bound increases the estimate, and state is
            // updated to kIncreaseUsingPadding.
            lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBe 200.kbps * 10
            lossBasedBandwidthEstimator.getLossBasedResult().state shouldBe LossBasedState.kIncreaseUsingPadding
        }

        "EstimateIncreaseSlowlyFromInstantUpperBoundInAlrIfFieldTrial" {
            val config = shortObservationConfig(
                LossBasedBweV2.Config(
                    appendUpperBoundCandidateInAlr = true
                )
            )
            val lossBasedBandwidthEstimator = LossBasedBweV2(config)
            lossBasedBandwidthEstimator.setBandwidthEstimate(1000.kbps)
            lossBasedBandwidthEstimator.setAcknowledgedBitrate(150.kbps)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                createPacketResultsWith50pPacketLossRate(Instant.EPOCH),
                delayBasedEstimate = Bandwidth.INFINITY,
                inAlr = true
            )
            val resultAfterLoss = lossBasedBandwidthEstimator.getLossBasedResult()
            resultAfterLoss.state shouldBe LossBasedState.kDecreasing

            for (feedbackCount in 1..3) {
                lossBasedBandwidthEstimator.updateBandwidthEstimate(
                    createPacketResultsWithReceivedPackets(
                        Instant.EPOCH + feedbackCount * kObservationDurationLowerBound
                    ),
                    delayBasedEstimate = Bandwidth.INFINITY,
                    inAlr = true
                )
            }

            // Expect less than 100% increase
            lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBeLessThan
                2 * resultAfterLoss.bandwidthEstimate
        }

        "HasDelayBasedStateIfLossBasedBweIsMax" {
            val config = shortObservationConfig()
            val lossBasedBandwidthEstimator = LossBasedBweV2(config)
            lossBasedBandwidthEstimator.setMinMaxBitrate(10.kbps, 1000.kbps)

            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                packetResults = createPacketResultsWithReceivedPackets(
                    Instant.EPOCH
                ),
                delayBasedEstimate = 2000.kbps,
                inAlr = false
            )
            lossBasedBandwidthEstimator.getLossBasedResult().state shouldBe LossBasedState.kDelayBasedEstimate
            lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBe 1000.kbps

            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                packetResults = createPacketResultsWith50pPacketLossRate(
                    Instant.EPOCH + kObservationDurationLowerBound
                ),
                delayBasedEstimate = 2000.kbps,
                inAlr = false
            )
            var result = lossBasedBandwidthEstimator.getLossBasedResult()
            result.state shouldBe LossBasedState.kDecreasing
            result.bandwidthEstimate shouldBeLessThan 1000.kbps

            // Eventually  the estimator recovers to delay based state.
            var feedbackCount = 2
            while (result.state != LossBasedState.kDelayBasedEstimate) {
                lossBasedBandwidthEstimator.updateBandwidthEstimate(
                    packetResults = createPacketResultsWithReceivedPackets(
                        Instant.EPOCH + feedbackCount++ * kObservationDurationLowerBound
                    ),
                    delayBasedEstimate = 2000.kbps,
                    inAlr = false
                )
                result = lossBasedBandwidthEstimator.getLossBasedResult()
            }
            result.state shouldBe LossBasedState.kDelayBasedEstimate
            lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBe 1000.kbps
        }

        "IncreaseUsingPaddingStateIfFieldTrial" {
            val config = shortObservationConfig(
                LossBasedBweV2.Config(
                    paddingDuration = 1000.ms
                )
            )
            val lossBasedBandwidthEstimator = LossBasedBweV2(config)
            lossBasedBandwidthEstimator.setBandwidthEstimate(2500.kbps)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                createPacketResultsWith50pPacketLossRate(Instant.EPOCH),
                delayBasedEstimate = Bandwidth.INFINITY,
                inAlr = false
            )
            lossBasedBandwidthEstimator.getLossBasedResult().state shouldBe LossBasedState.kDecreasing

            var feedbackCount = 1
            while (lossBasedBandwidthEstimator.getLossBasedResult().state != LossBasedState.kIncreaseUsingPadding) {
                lossBasedBandwidthEstimator.updateBandwidthEstimate(
                    createPacketResultsWithReceivedPackets(
                        Instant.EPOCH + kObservationDurationLowerBound * feedbackCount
                    ),
                    delayBasedEstimate = Bandwidth.INFINITY,
                    inAlr = false
                )
                feedbackCount++
            }
            lossBasedBandwidthEstimator.getLossBasedResult().state shouldBe LossBasedState.kIncreaseUsingPadding
        }

        "BestCandidateResetsToUpperBoundInFieldTrial" {
            val config = shortObservationConfig(
                LossBasedBweV2.Config(
                    paddingDuration = 1000.ms,
                    boundBestCandidate = true
                )
            )
            val lossBasedBandwidthEstimator = LossBasedBweV2(config)
            lossBasedBandwidthEstimator.setBandwidthEstimate(2500.kbps)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                createPacketResultsWith50pPacketLossRate(Instant.EPOCH),
                delayBasedEstimate = Bandwidth.INFINITY,
                inAlr = true
            )
            val resultAfterLoss = lossBasedBandwidthEstimator.getLossBasedResult()
            resultAfterLoss.state shouldBe LossBasedState.kDecreasing

            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                createPacketResultsWithReceivedPackets(
                    Instant.EPOCH + kObservationDurationLowerBound
                ),
                delayBasedEstimate = Bandwidth.INFINITY,
                inAlr = true
            )
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                createPacketResultsWithReceivedPackets(
                    Instant.EPOCH + 2 * kObservationDurationLowerBound
                ),
                delayBasedEstimate = Bandwidth.INFINITY,
                inAlr = true
            )
            // After a BWE decrease due to large loss, BWE is expected to ramp up slowly
            // and follow the acked bitrate.
            lossBasedBandwidthEstimator.getLossBasedResult().state shouldBe LossBasedState.kIncreaseUsingPadding
            lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate.kbps shouldBe
                (resultAfterLoss.bandwidthEstimate.kbps plusOrMinus 100.0)
        }

        "DecreaseToAckedCandidateIfPaddingInAlr" {
            val config = shortObservationConfig(
                LossBasedBweV2.Config(
                    paddingDuration = 1000.ms,
                    // Set InstantUpperBoundBwBalance high to disable InstantUpperBound cap.
                    instantUpperBoundBandwidthBalance = 10000.kbps
                )
            )
            val lossBasedBandwidthEstimator = LossBasedBweV2(config)
            lossBasedBandwidthEstimator.setBandwidthEstimate(1000.kbps)
            var feedbackId = 0
            while (lossBasedBandwidthEstimator.getLossBasedResult().state !=
                LossBasedState.kDecreasing
            ) {
                lossBasedBandwidthEstimator.updateBandwidthEstimate(
                    createPacketResultsWith100pLossRate(
                        Instant.EPOCH + feedbackId * kObservationDurationLowerBound
                    ),
                    delayBasedEstimate = Bandwidth.INFINITY,
                    inAlr = true
                )
                feedbackId++
            }

            while (lossBasedBandwidthEstimator.getLossBasedResult().state !=
                LossBasedState.kIncreaseUsingPadding
            ) {
                lossBasedBandwidthEstimator.updateBandwidthEstimate(
                    createPacketResultsWithReceivedPackets(
                        Instant.EPOCH + feedbackId * kObservationDurationLowerBound
                    ),
                    delayBasedEstimate = Bandwidth.INFINITY,
                    inAlr = true
                )
                feedbackId++
            }

            lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBeGreaterThan 900.kbps

            lossBasedBandwidthEstimator.setAcknowledgedBitrate(100.kbps)
            // Padding is sent now, create some lost packets.
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                createPacketResultsWith100pLossRate(
                    Instant.EPOCH + feedbackId * kObservationDurationLowerBound
                ),
                delayBasedEstimate = Bandwidth.INFINITY,
                inAlr = true
            )
            lossBasedBandwidthEstimator.getLossBasedResult().state shouldBe LossBasedState.kDecreasing
            lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBe 100.kbps
        }

        "DecreaseAfterPadding" {
            val config = shortObservationConfig(
                LossBasedBweV2.Config(
                    paddingDuration = 1000.ms,
                    bandwidthRampupUpperBoundFactor = 2.0
                )
            )
            val lossBasedBandwidthEstimator = LossBasedBweV2(config)
            lossBasedBandwidthEstimator.setBandwidthEstimate(2500.kbps)
            var acknowledgedBitrate = 51.kbps
            lossBasedBandwidthEstimator.setAcknowledgedBitrate(acknowledgedBitrate)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                createPacketResultsWith50pPacketLossRate(Instant.EPOCH),
                delayBasedEstimate = Bandwidth.INFINITY,
                inAlr = false
            )
            lossBasedBandwidthEstimator.getLossBasedResult().state shouldBe LossBasedState.kDecreasing
            lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBe acknowledgedBitrate

            acknowledgedBitrate = 26.kbps
            lossBasedBandwidthEstimator.setAcknowledgedBitrate(acknowledgedBitrate)
            var feedbackId = 1
            while (lossBasedBandwidthEstimator.getLossBasedResult().state != LossBasedState.kIncreaseUsingPadding) {
                lossBasedBandwidthEstimator.updateBandwidthEstimate(
                    createPacketResultsWithReceivedPackets(
                        Instant.EPOCH + feedbackId * kObservationDurationLowerBound
                    ),
                    delayBasedEstimate = Bandwidth.INFINITY,
                    inAlr = false
                )
                feedbackId++
            }

            val estimateIncreased = Instant.EPOCH + feedbackId * kObservationDurationLowerBound
            // The state is kIncreaseUsingPadding for a while without changing the
            // estimate, which is limited by 2 * acked rate.
            while (lossBasedBandwidthEstimator.getLossBasedResult().state == LossBasedState.kIncreaseUsingPadding) {
                lossBasedBandwidthEstimator.updateBandwidthEstimate(
                    createPacketResultsWithReceivedPackets(
                        Instant.EPOCH + feedbackId * kObservationDurationLowerBound
                    ),
                    delayBasedEstimate = Bandwidth.INFINITY,
                    inAlr = false
                )
                feedbackId++
            }

            lossBasedBandwidthEstimator.getLossBasedResult().state shouldBe LossBasedState.kDecreasing
            val startDecreasing = Instant.EPOCH + (feedbackId - 1) * kObservationDurationLowerBound
            Duration.between(estimateIncreased, startDecreasing) shouldBe 1.secs
        }

        "HoldRateNotLowerThanAckedRate" {
            val config = shortObservationConfig(
                LossBasedBweV2.Config(
                    holdDurationFactor = 10.0,
                    lowerBoundByAckedRateFactor = 1.0
                )
            )
            val lossBasedBandwidthEstimator = LossBasedBweV2(config)
            lossBasedBandwidthEstimator.setBandwidthEstimate(2500.kbps)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                createPacketResultsWith50pPacketLossRate(Instant.EPOCH),
                delayBasedEstimate = Bandwidth.INFINITY,
                inAlr = false
            )
            lossBasedBandwidthEstimator.getLossBasedResult().state shouldBe LossBasedState.kDecreasing
            // During the hold duration, hold rate is not lower than the acked rate.
            lossBasedBandwidthEstimator.setAcknowledgedBitrate(1000.kbps)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                createPacketResultsWith50pPacketLossRate(
                    Instant.EPOCH + kObservationDurationLowerBound
                ),
                delayBasedEstimate = Bandwidth.INFINITY,
                inAlr = false
            )
            lossBasedBandwidthEstimator.getLossBasedResult().state shouldBe LossBasedState.kDecreasing
            lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBe 1000.kbps
        }

        "EstimateNotLowerThanAckedRate" {
            val config = shortObservationConfig(
                LossBasedBweV2.Config(
                    lowerBoundByAckedRateFactor = 1.0
                )
            )
            val lossBasedBandwidthEstimator = LossBasedBweV2(config)
            lossBasedBandwidthEstimator.setBandwidthEstimate(2500.kbps)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                createPacketResultsWith100pLossRate(Instant.EPOCH),
                delayBasedEstimate = Bandwidth.INFINITY,
                inAlr = false
            )
            lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBeLessThan 1000.kbps

            lossBasedBandwidthEstimator.setAcknowledgedBitrate(1000.kbps)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                createPacketResultsWith50pPacketLossRate(
                    Instant.EPOCH + kObservationDurationLowerBound
                ),
                delayBasedEstimate = Bandwidth.INFINITY,
                inAlr = false
            )
            lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBe 1000.kbps

            lossBasedBandwidthEstimator.setAcknowledgedBitrate(1000.kbps)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                createPacketResultsWithReceivedPackets(
                    Instant.EPOCH + 2 * kObservationDurationLowerBound
                ),
                delayBasedEstimate = Bandwidth.INFINITY,
                inAlr = false
            )
            lossBasedBandwidthEstimator.setAcknowledgedBitrate(1000.kbps)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                createPacketResultsWithReceivedPackets(
                    Instant.EPOCH + 3 * kObservationDurationLowerBound
                ),
                delayBasedEstimate = Bandwidth.INFINITY,
                inAlr = false
            )

            // Verify that the estimate recovers from the acked rate.
            lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBeGreaterThan 1000.kbps
        }

        "EndHoldDurationIfDelayBasedEstimateWorks" {
            val config = shortObservationConfig()
            val lossBasedBandwidthEstimator = LossBasedBweV2(config)
            lossBasedBandwidthEstimator.setBandwidthEstimate(2500.kbps)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                createPacketResultsWith50pPacketLossRate(Instant.EPOCH),
                delayBasedEstimate = Bandwidth.INFINITY,
                inAlr = false
            )
            lossBasedBandwidthEstimator.getLossBasedResult().state shouldBe LossBasedState.kDecreasing
            val estimate = lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate

            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                createPacketResultsWithReceivedPackets(Instant.EPOCH + kObservationDurationLowerBound),
                delayBasedEstimate = estimate + 10.kbps,
                inAlr = false
            )
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                createPacketResultsWithReceivedPackets(Instant.EPOCH + kObservationDurationLowerBound * 2),
                delayBasedEstimate = estimate + 10.kbps,
                inAlr = false
            )
            lossBasedBandwidthEstimator.getLossBasedResult().state shouldBe LossBasedState.kDelayBasedEstimate
            lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBe estimate + 10.kbps
        }

        "UseByteLossRate" {
            val config = shortObservationConfig(
                LossBasedBweV2.Config(
                    useByteLossRate = true
                )
            )
            val lossBasedBandwidthEstimator = LossBasedBweV2(config)
            lossBasedBandwidthEstimator.setBandwidthEstimate(500.kbps)
            // Create packet feedback having 10% packet loss but more than 50% byte loss.
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                createPacketResultsWith10pPacketLossRate(Instant.EPOCH, (kPacketSize * 20).bytes),
                delayBasedEstimate = Bandwidth.INFINITY,
                inAlr = false
            )
            lossBasedBandwidthEstimator.getLossBasedResult().state shouldBe LossBasedState.kDecreasing
            // The estimate is bounded by the instant upper bound due to high loss.
            lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBeLessThan 160.kbps
        }

        "UseByteLossRateIgnoreLossSpike" {
            val config = LossBasedBweV2.Config(
                useByteLossRate = true,
                observationWindowSize = 5
            )
            val lossBasedBandwidthEstimator = LossBasedBweV2(config)
            val kDelayBasedEstimate = 500.kbps
            lossBasedBandwidthEstimator.setBandwidthEstimate(kDelayBasedEstimate)

            // Fill the observation window
            for (i in 0 until 5) {
                lossBasedBandwidthEstimator.updateBandwidthEstimate(
                    createPacketResultsWithReceivedPackets(
                        firstPacketTimestamp = Instant.EPOCH + i * kObservationDurationLowerBound
                    ),
                    delayBasedEstimate = kDelayBasedEstimate,
                    inAlr = false
                )
            }
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                createPacketResultsWith100pLossRate(Instant.EPOCH + 5 * kObservationDurationLowerBound),
                delayBasedEstimate = kDelayBasedEstimate,
                inAlr = false
            )
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                createPacketResultsWithReceivedPackets(
                    firstPacketTimestamp = Instant.EPOCH + 6 * kObservationDurationLowerBound
                ),
                delayBasedEstimate = kDelayBasedEstimate,
                inAlr = false
            )
            lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBe kDelayBasedEstimate

            // But if more loss happen in a new observation, BWE back down.
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                createPacketResultsWith100pLossRate(
                    firstPacketTimestamp = Instant.EPOCH + 7 * kObservationDurationLowerBound
                ),
                delayBasedEstimate = kDelayBasedEstimate,
                inAlr = false
            )
            lossBasedBandwidthEstimator.getLossBasedResult().state shouldBe LossBasedState.kDecreasing
            lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBeLessThan kDelayBasedEstimate
        }

        "UseByteLossRateDoesNotIgnoreLossSpikeOnSendBurst" {
            val config = LossBasedBweV2.Config(
                useByteLossRate = true,
                observationWindowSize = 5
            )
            val lossBasedBandwidthEstimator = LossBasedBweV2(config)
            val kDelayBasedEstimate = 500.kbps
            lossBasedBandwidthEstimator.setBandwidthEstimate(kDelayBasedEstimate)

            // Fill the observation window
            for (i in 0 until 5) {
                lossBasedBandwidthEstimator.updateBandwidthEstimate(
                    createPacketResultsWithReceivedPackets(
                        firstPacketTimestamp = Instant.EPOCH + i * kObservationDurationLowerBound
                    ),
                    delayBasedEstimate = kDelayBasedEstimate,
                    inAlr = false
                )
            }

            // If the loss happens when increasing sending rate, then
            // the BWE should back down
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                createPacketResultsWith100pLossRate(
                    firstPacketTimestamp = Instant.EPOCH + 5 * kObservationDurationLowerBound,
                    numPackets = 5
                ),
                delayBasedEstimate = kDelayBasedEstimate,
                inAlr = false
            )
            lossBasedBandwidthEstimator.getLossBasedResult().state shouldBe LossBasedState.kDecreasing
            lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBeLessThan kDelayBasedEstimate
        }

        "EstimateDoesNotBackOffDueToPacketReorderingBetweenFeedback" {
            val config = shortObservationConfig()
            val lossBasedBandwidthEstimator = LossBasedBweV2(config)
            val kStartBitrate = 2500.kbps
            lossBasedBandwidthEstimator.setBandwidthEstimate(kStartBitrate)

            val feedback1 = List(3) { PacketResult() }
            feedback1[0].sentPacket.sequenceNumber = 1
            feedback1[0].sentPacket.size = kPacketSize.bytes
            feedback1[0].sentPacket.sendTime = Instant.EPOCH
            feedback1[0].receiveTime = feedback1[0].sentPacket.sendTime + 10.ms
            feedback1[1].sentPacket.sequenceNumber = 2
            feedback1[1].sentPacket.size = kPacketSize.bytes
            feedback1[1].sentPacket.sendTime = Instant.EPOCH
            // Lost or reordered
            feedback1[1].receiveTime = Instant.MAX

            feedback1[2].sentPacket.sequenceNumber = 3
            feedback1[2].sentPacket.size = kPacketSize.bytes
            feedback1[2].sentPacket.sendTime = Instant.EPOCH
            feedback1[2].receiveTime = feedback1[2].sentPacket.sendTime + 10.ms

            val feedback2 = List(3) { PacketResult() }
            feedback2[0].sentPacket.sequenceNumber = 2
            feedback2[0].sentPacket.size = kPacketSize.bytes
            feedback2[0].sentPacket.sendTime = Instant.EPOCH
            feedback2[0].receiveTime = feedback1[0].sentPacket.sendTime + 10.ms
            feedback2[1].sentPacket.sequenceNumber = 4
            feedback2[1].sentPacket.size = kPacketSize.bytes
            feedback2[1].sentPacket.sendTime = Instant.EPOCH
            feedback2[1].receiveTime = Instant.MAX
            feedback2[2].sentPacket.sequenceNumber = 5
            feedback2[2].sentPacket.size = kPacketSize.bytes
            feedback2[2].sentPacket.sendTime = Instant.EPOCH
            feedback2[2].receiveTime = feedback1[2].sentPacket.sendTime + 10.ms

            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                feedback1,
                delayBasedEstimate = kStartBitrate,
                inAlr = false
            )
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                feedback2,
                delayBasedEstimate = kStartBitrate,
                inAlr = false
            )
            lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBe kStartBitrate
        }
    }
}
