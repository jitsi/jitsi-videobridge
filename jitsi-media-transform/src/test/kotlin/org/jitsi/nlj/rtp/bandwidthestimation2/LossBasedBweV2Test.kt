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
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.jitsi.nlj.util.Bandwidth
import org.jitsi.nlj.util.NEVER
import org.jitsi.nlj.util.bytes
import org.jitsi.nlj.util.div
import org.jitsi.nlj.util.kbps
import org.jitsi.utils.ms
import org.jitsi.utils.times
import java.time.Instant

/**
 * Unit tests for Loss-Based BWE,
 * based on WebRTC modules/congestion_controller/goog_cc/loss_based_bwe_v2_test.cc in
 * WebRTC 8284f2b4e8670529d039a8b6c73ec5f1d760bd21.
 *
 */

val kObservationDurationLowerBound = 250.ms
val kDelayedIncreaseWindow = 300.ms
const val kMaxIncreaseFactor = 1.5

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

private fun createPacketResultsWithReceivedPackets(firstPacketTimestamp: Instant): List<PacketResult> {
    val enoughFeedback = List(2) { PacketResult() }
    enoughFeedback[0].sentPacket.size = 15_000.bytes
    enoughFeedback[1].sentPacket.size = 15_000.bytes
    enoughFeedback[0].sentPacket.sendTime = firstPacketTimestamp
    enoughFeedback[1].sentPacket.sendTime = firstPacketTimestamp + kObservationDurationLowerBound
    enoughFeedback[0].receiveTime = firstPacketTimestamp + kObservationDurationLowerBound
    enoughFeedback[1].receiveTime = firstPacketTimestamp + kObservationDurationLowerBound * 2

    return enoughFeedback
}

private fun createPacketResultsWith10pLossRate(firstPacketTimestamp: Instant): List<PacketResult> {
    val enoughFeedback = List(10) { PacketResult() }
    enoughFeedback[0].sentPacket.size = 15_000.bytes

    for (i in enoughFeedback.indices) {
        enoughFeedback[i].sentPacket.size = 15_000.bytes
        enoughFeedback[i].sentPacket.sendTime = firstPacketTimestamp + kObservationDurationLowerBound * i
        enoughFeedback[i].receiveTime = firstPacketTimestamp + kObservationDurationLowerBound * (i + 1)
    }
    enoughFeedback[9].receiveTime = NEVER
    return enoughFeedback
}

private fun createPacketResultsWith50pLossRate(firstPacketTimestamp: Instant): List<PacketResult> {
    val enoughFeedback = List(2) { PacketResult() }
    enoughFeedback[0].sentPacket.size = 15_000.bytes
    enoughFeedback[1].sentPacket.size = 15_000.bytes
    enoughFeedback[0].sentPacket.sendTime = firstPacketTimestamp
    enoughFeedback[1].sentPacket.sendTime = firstPacketTimestamp + kObservationDurationLowerBound
    enoughFeedback[0].receiveTime = firstPacketTimestamp + kObservationDurationLowerBound
    enoughFeedback[1].receiveTime = NEVER
    return enoughFeedback
}

private fun createPacketResultsWith100pLossRate(firstPacketTimestamp: Instant): List<PacketResult> {
    val enoughFeedback = List(2) { PacketResult() }
    enoughFeedback[0].sentPacket.size = 15_000.bytes
    enoughFeedback[1].sentPacket.size = 15_000.bytes
    enoughFeedback[0].sentPacket.sendTime = firstPacketTimestamp
    enoughFeedback[1].sentPacket.sendTime = firstPacketTimestamp + kObservationDurationLowerBound
    enoughFeedback[0].receiveTime = NEVER
    enoughFeedback[1].receiveTime = NEVER
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
                Instant.EPOCH + kObservationDurationLowerBound * 2
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
                Instant.EPOCH + kObservationDurationLowerBound * 2
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
                Instant.EPOCH + kObservationDurationLowerBound * 2
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
            val enoughFeedback1 = createPacketResultsWith50pLossRate(Instant.EPOCH)
            val enoughFeedback2 = createPacketResultsWith100pLossRate(
                Instant.EPOCH + kObservationDurationLowerBound * 2
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
            estimate2 shouldBe estimate1
        }

        "IncreaseToDelayBasedEstimateIfNoLossOrDelayIncrease" {
            val enoughFeedback1 = createPacketResultsWithReceivedPackets(Instant.EPOCH)
            val enoughFeedback2 = createPacketResultsWithReceivedPackets(
                Instant.EPOCH + kObservationDurationLowerBound * 2
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

        "IncreaseByMaxIncreaseFactorAfterLossBasedBweBacksOff" {
            val config = LossBasedBweV2.Config(
                /* ShortObservationConfig */
                minNumObservations = 1,
                observationWindowSize = 2,

                candidateFactors = doubleArrayOf(1.2, 1.0, 0.5),
                instantUpperBoundBandwidthBalance = 10000.kbps,
                maxIncreaseFactor = 1.5,
                notIncreaseIfInherentLossLessThanAverageLoss = false
            )
            val lossBasedBandwidthEstimator = LossBasedBweV2(config)
            val delayBasedEstimate = 5000.kbps
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
            val resultAtLoss = lossBasedBandwidthEstimator.getLossBasedResult()

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

            val resultAfterRecovery = lossBasedBandwidthEstimator.getLossBasedResult()

            resultAfterRecovery.bandwidthEstimate shouldBe resultAtLoss.bandwidthEstimate * 1.5
        }

        "LossBasedStateIsDelayBasedEstimateAfterNetworkRecovering" {
            val config = LossBasedBweV2.Config(
                /* ShortObservationConfig */
                minNumObservations = 1,
                observationWindowSize = 2,

                candidateFactors = doubleArrayOf(100.0, 1.0, 0.5),
                observationDurationLowerBound = 200.ms,
                instantUpperBoundBandwidthBalance = 10000.kbps,
                maxIncreaseFactor = 100.0,
                notIncreaseIfInherentLossLessThanAverageLoss = false
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
            val config = LossBasedBweV2.Config(
                /* ShortObservationConfig */
                minNumObservations = 1,
                observationWindowSize = 2,

                candidateFactors = doubleArrayOf(100.0, 1.0, 0.5),
                instantUpperBoundBandwidthBalance = 10000.kbps,
                maxIncreaseFactor = 100.0
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

        // After loss based bwe backs off, the next estimate is capped by
        // a factor of acked bitrate.
        "IncreaseByFactorOfAckedBitrateAfterLossBasedBweBacksOff" {
            val config = LossBasedBweV2.Config(
                /* ShortObservationConfig */
                minNumObservations = 1,
                observationWindowSize = 2,

                lossThresholdOfHighBandwidthPreference = 0.99,
                bandwidthRampupUpperBoundFactor = 1.2,
                // Set InstantUpperBoundBwBalance high to disable InstantUpperBound cap.
                instantUpperBoundBandwidthBalance = 10000.kbps
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
            while (feedbackCount < 5 && result.state != LossBasedState.kIncreasing) {
                lossBasedBandwidthEstimator.updateBandwidthEstimate(
                    createPacketResultsWithReceivedPackets(
                        Instant.EPOCH + kObservationDurationLowerBound * feedbackCount++
                    ),
                    delayBasedEstimate,
                    inAlr = false
                )
                result = lossBasedBandwidthEstimator.getLossBasedResult()
            }
            result.state shouldBe LossBasedState.kIncreasing

            result.bandwidthEstimate shouldBe estimate1 * 0.9 * 1.2

            // But if acked bitrate decrease, BWE does not decrease when there is no
            // loss.
            lossBasedBandwidthEstimator.setAcknowledgedBitrate(estimate1 * 0.9)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                createPacketResultsWithReceivedPackets(
                    Instant.EPOCH + kObservationDurationLowerBound * feedbackCount++
                ),
                delayBasedEstimate,
                inAlr = false
            )
            lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBe result.bandwidthEstimate
        }

        "EstimateBitrateIsBoundedDuringDelayedWindowAfterLossBasedBweBacksOff" {
            val enoughFeedback1 = createPacketResultsWithReceivedPackets(Instant.EPOCH)
            val enoughFeedback2 = createPacketResultsWith50pLossRate(Instant.EPOCH + kDelayedIncreaseWindow - 2.ms)
            val enoughFeedback3 =
                createPacketResultsWithReceivedPackets(Instant.EPOCH + kDelayedIncreaseWindow - 1.ms)
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
            val config = LossBasedBweV2.Config(
                /* ShortObservationConfig */
                minNumObservations = 1,
                observationWindowSize = 2,

                candidateFactors = doubleArrayOf(1.2),
                notIncreaseIfInherentLossLessThanAverageLoss = true
            )
            val lossBasedBandwidthEstimator = LossBasedBweV2(config)

            lossBasedBandwidthEstimator.setBandwidthEstimate(600.kbps)

            val enoughFeedback10pLoss1 = createPacketResultsWith10pLossRate(Instant.EPOCH)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback10pLoss1,
                delayBasedEstimate = Bandwidth.INFINITY,
                inAlr = false
            )

            val enoughFeedback10pLoss2 =
                createPacketResultsWith10pLossRate(Instant.EPOCH + kObservationDurationLowerBound)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback10pLoss2,
                delayBasedEstimate = Bandwidth.INFINITY,
                inAlr = false
            )

            // Do not increase the bitrate because inherent loss is less than average loss
            lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBe 600.kbps
        }

        "SelectHighBandwidthCandidateIfLossRateIsLessThanThreshold" {
            val config = LossBasedBweV2.Config(
                /* ShortObservationConfig */
                minNumObservations = 1,
                observationWindowSize = 2,

                lossThresholdOfHighBandwidthPreference = 0.20,
                notIncreaseIfInherentLossLessThanAverageLoss = false
            )
            val lossBasedBandwidthEstimator = LossBasedBweV2(config)
            val delayBasedEstimate = 5000.kbps

            lossBasedBandwidthEstimator.setBandwidthEstimate(600.kbps)

            val enoughFeedback10pLoss1 = createPacketResultsWith10pLossRate(Instant.EPOCH)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback10pLoss1,
                delayBasedEstimate,
                inAlr = false
            )

            val enoughFeedback10pLoss2 =
                createPacketResultsWith10pLossRate(Instant.EPOCH + kObservationDurationLowerBound)
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
            val config = LossBasedBweV2.Config(
                /* ShortObservationConfig */
                minNumObservations = 1,
                observationWindowSize = 2,

                lossThresholdOfHighBandwidthPreference = 0.05
            )
            val lossBasedBandwidthEstimator = LossBasedBweV2(config)
            val delayBasedEstimate = 5000.kbps

            lossBasedBandwidthEstimator.setBandwidthEstimate(600.kbps)

            val enoughFeedback10pLoss1 = createPacketResultsWith10pLossRate(Instant.EPOCH)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback10pLoss1,
                delayBasedEstimate,
                inAlr = false
            )

            val enoughFeedback10pLoss2 =
                createPacketResultsWith10pLossRate(Instant.EPOCH + kObservationDurationLowerBound)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback10pLoss2,
                delayBasedEstimate,
                inAlr = false
            )

            // Because LossThresholdOfHighBandwidthPreference is 5%, the average loss is
            // 10%, bandwidth estimate should decrease.
            lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBeLessThan 600.kbps
        }

        "StricterBoundUsingHighLossRateThresholdAt10pLossRate" {
            val config = LossBasedBweV2.Config(
                /* ShortObservationConfig */
                minNumObservations = 1,
                observationWindowSize = 2,

                highLossRateThreshold = 0.09
            )
            val lossBasedBandwidthEstimator = LossBasedBweV2(config)
            lossBasedBandwidthEstimator.setMinMaxBitrate(10.kbps, 1000000.kbps)
            val delayBasedEstimate = 5000.kbps
            lossBasedBandwidthEstimator.setBandwidthEstimate(600.kbps)

            val enoughFeedback10pLoss1 = createPacketResultsWith10pLossRate(Instant.EPOCH)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback10pLoss1,
                delayBasedEstimate,
                inAlr = false
            )

            val enoughFeedback10pLoss2 =
                createPacketResultsWith10pLossRate(Instant.EPOCH + kObservationDurationLowerBound)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback10pLoss2,
                delayBasedEstimate,
                inAlr = false
            )

            // At 10% loss rate and high loss rate threshold to be 10%, cap the estimate
            // to be 500 * 1000-0.1 = 400kbps.
            lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBe 400.kbps
        }

        "StricterBoundUsingHighLossRateThresholdAt50pLossRate" {
            val config = LossBasedBweV2.Config(
                /* ShortObservationConfig */
                minNumObservations = 1,
                observationWindowSize = 2,

                highLossRateThreshold = 0.3
            )
            val lossBasedBandwidthEstimator = LossBasedBweV2(config)
            lossBasedBandwidthEstimator.setMinMaxBitrate(10.kbps, 1000000.kbps)
            val delayBasedEstimate = 5000.kbps
            lossBasedBandwidthEstimator.setBandwidthEstimate(600.kbps)

            val enoughFeedback50pLoss1 = createPacketResultsWith50pLossRate(Instant.EPOCH)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback50pLoss1,
                delayBasedEstimate,
                inAlr = false
            )

            val enoughFeedback50pLoss2 =
                createPacketResultsWith50pLossRate(Instant.EPOCH + kObservationDurationLowerBound)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback50pLoss2,
                delayBasedEstimate,
                inAlr = false
            )

            // At 50% loss rate and high loss rate threshold to be 30%, cap the estimate
            // to be the min bitrate.
            lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBe 10.kbps
        }

        "StricterBoundUsingHighLossRateThresholdAt100pLossRate" {
            val config = LossBasedBweV2.Config(
                /* ShortObservationConfig */
                minNumObservations = 1,
                observationWindowSize = 2,

                highLossRateThreshold = 0.3,
            )
            val lossBasedBandwidthEstimator = LossBasedBweV2(config)
            lossBasedBandwidthEstimator.setMinMaxBitrate(10.kbps, 1000000.kbps)
            val delayBasedEstimate = 5000.kbps
            lossBasedBandwidthEstimator.setBandwidthEstimate(600.kbps)

            val enoughFeedback100pLoss1 = createPacketResultsWith100pLossRate(Instant.EPOCH)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback100pLoss1,
                delayBasedEstimate,
                inAlr = false
            )

            val enoughFeedback100pLoss2 =
                createPacketResultsWith100pLossRate(Instant.EPOCH + kObservationDurationLowerBound)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback100pLoss2,
                delayBasedEstimate,
                inAlr = false
            )

            // At 100% loss rate and high loss rate threshold to be 30%, cap the estimate
            // to be the min bitrate.
            lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBe 10.kbps
        }

        "EstimateRecoversAfterHighLoss" {
            val config = LossBasedBweV2.Config(
                /* ShortObservationConfig */
                minNumObservations = 1,
                observationWindowSize = 2,

                highLossRateThreshold = 0.3
            )
            val lossBasedBandwidthEstimator = LossBasedBweV2(config)
            lossBasedBandwidthEstimator.setMinMaxBitrate(10.kbps, 1000000.kbps)
            val delayBasedEstimate = 5000.kbps
            lossBasedBandwidthEstimator.setBandwidthEstimate(600.kbps)

            val enoughFeedback100pLoss1 = createPacketResultsWith100pLossRate(Instant.EPOCH)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback100pLoss1,
                delayBasedEstimate,
                inAlr = false
            )

            // Make sure that the estimate is set to min bitrate because of 100% loss
            // rate.
            lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBe 10.kbps

            // Create some feedbacks with 0 loss rate to simulate network recovering.
            val enoughFeedback0pLoss1 =
                createPacketResultsWithReceivedPackets(Instant.EPOCH + kObservationDurationLowerBound)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback0pLoss1,
                delayBasedEstimate,
                inAlr = false
            )

            val enoughFeedback0pLoss2 =
                createPacketResultsWithReceivedPackets(Instant.EPOCH + kObservationDurationLowerBound * 2)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback0pLoss2,
                delayBasedEstimate,
                inAlr = false
            )

            // The estimate increases as network recovers.
            lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBeGreaterThan 10.kbps
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
            val config = LossBasedBweV2.Config(
                /* ShortObservationConfig */
                minNumObservations = 1,
                observationWindowSize = 2,

                instantUpperBoundBandwidthBalance = 100.kbps
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
            val config = LossBasedBweV2.Config(
                /* ShortObservationConfig */
                minNumObservations = 1,
                observationWindowSize = 2,

                instantUpperBoundBandwidthBalance = 100.kbps
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
            val config = LossBasedBweV2.Config(
                /* ShortObservationConfig */
                minNumObservations = 1,
                observationWindowSize = 2,

                useInStartPhase = true
            )
            val lossBasedBandwidthEstimator = LossBasedBweV2(config)
            // Make sure that the estimator is not ready to use in start phase because of
            // lacking TWCC feedback.
            lossBasedBandwidthEstimator.readyToUseInStartPhase() shouldBe false
        }

        "ReadyToUseInStartPhase" {
            val config = LossBasedBweV2.Config(
                /* ShortObservationConfig */
                minNumObservations = 1,
                observationWindowSize = 2,

                useInStartPhase = true
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
            val config = LossBasedBweV2.Config(
                /* ShortObservationConfig */
                minNumObservations = 1,
                observationWindowSize = 2,

                lowerBoundByAckedRateFactor = 1.0
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
            val config = LossBasedBweV2.Config(
                /* ShortObservationConfig */
                minNumObservations = 1,
                observationWindowSize = 2,

                lowerBoundByAckedRateFactor = 0.0
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
            val config = LossBasedBweV2.Config(
                /* ShortObservationConfig */
                minNumObservations = 1,
                observationWindowSize = 2,

                candidateFactors = doubleArrayOf(1.0),
                instantUpperBoundBandwidthBalance = 10.kbps
            )
            val lossBasedBandwidthEstimator = LossBasedBweV2(config)
            lossBasedBandwidthEstimator.setMinMaxBitrate(10.kbps, 1000000.kbps)
            lossBasedBandwidthEstimator.setBandwidthEstimate(500.kbps)
            lossBasedBandwidthEstimator.setAcknowledgedBitrate(500.kbps)

            val enoughFeedback10pLoss1 = createPacketResultsWith10pLossRate(Instant.EPOCH)
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
            val config = LossBasedBweV2.Config(
                /* ShortObservationConfig */
                minNumObservations = 1,
                observationWindowSize = 2,

                candidateFactors = doubleArrayOf(1.0),
                lowerBoundByAckedRateFactor = 10.0
            )
            val lossBasedBandwidthEstimator = LossBasedBweV2(config)
            lossBasedBandwidthEstimator.setMinMaxBitrate(10.kbps, 1000000.kbps)
            lossBasedBandwidthEstimator.setBandwidthEstimate(500.kbps)
            lossBasedBandwidthEstimator.setAcknowledgedBitrate(1.kbps)

            // Network has a high loss to create a loss scenario.
            val enoughFeedback50pLoss1 = createPacketResultsWith50pLossRate(Instant.EPOCH)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback50pLoss1,
                delayBasedEstimate = Bandwidth.INFINITY,
                inAlr = false
            )

            lossBasedBandwidthEstimator.getLossBasedResult().state shouldBe LossBasedState.kDecreasing

            // Network still has a high loss, but better acked rate.
            lossBasedBandwidthEstimator.setAcknowledgedBitrate(200.kbps)
            val enoughFeedback50pLoss2 =
                createPacketResultsWith50pLossRate(Instant.EPOCH + kObservationDurationLowerBound)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback50pLoss2,
                delayBasedEstimate = Bandwidth.INFINITY,
                inAlr = false
            )

            // Verify that the instant lower bound increases the estimate, and state is
            // updated to kIncreasing.
            lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBe 200.kbps * 10
            lossBasedBandwidthEstimator.getLossBasedResult().state shouldBe LossBasedState.kIncreasing
        }

        "HasDelayBasedStateIfLossBasedBweIsMax" {
            val config = LossBasedBweV2.Config(
                /* ShortObservationConfig */
                minNumObservations = 1,
                observationWindowSize = 2
            )
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
                packetResults = createPacketResultsWith50pLossRate(Instant.EPOCH + kObservationDurationLowerBound),
                delayBasedEstimate = 2000.kbps,
                inAlr = false
            )
            var result = lossBasedBandwidthEstimator.getLossBasedResult()
            result.state shouldBe LossBasedState.kDecreasing
            result.bandwidthEstimate shouldBeLessThan 1000.kbps

            // Eventually  the estimator recovers to delay based state.
            var feedbackCount = 2
            while (feedbackCount < 5 && result.state != LossBasedState.kDelayBasedEstimate) {
                lossBasedBandwidthEstimator.updateBandwidthEstimate(
                    packetResults = createPacketResultsWithReceivedPackets(
                        Instant.EPOCH + kObservationDurationLowerBound * feedbackCount++
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
            val config = LossBasedBweV2.Config(
                /* ShortObservationConfig */
                minNumObservations = 1,
                observationWindowSize = 2,

                usePaddingForIncrease = true
            )
            val lossBasedBandwidthEstimator = LossBasedBweV2(config)
            lossBasedBandwidthEstimator.setBandwidthEstimate(2500.kbps)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                createPacketResultsWith50pLossRate(Instant.EPOCH),
                delayBasedEstimate = Bandwidth.INFINITY,
                inAlr = false
            )
            lossBasedBandwidthEstimator.getLossBasedResult().state shouldBe LossBasedState.kDecreasing

            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                createPacketResultsWithReceivedPackets(Instant.EPOCH + kObservationDurationLowerBound),
                delayBasedEstimate = Bandwidth.INFINITY,
                inAlr = false
            )
            lossBasedBandwidthEstimator.getLossBasedResult().state shouldBe LossBasedState.kIncreaseUsingPadding
        }
    }
}
