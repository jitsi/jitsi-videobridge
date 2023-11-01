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
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Exhaustive
import io.kotest.property.checkAll
import io.kotest.property.exhaustive.boolean
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
 * based on WebRTC modules/congestion_controller/goog_cc/delay_based_bwe_v2_test.cc in
 * WebRTC 8284f2b4e8670529d039a8b6c73ec5f1d760bd21.
 *
 */

val kObservationDurationLowerBound = 200.ms
val kDelayedIncreaseWindow = 300.ms
const val kMaxIncreaseFactor = 1.5

private fun config(enabled: Boolean, valid: Boolean, trendlineIntegrationEnabled: Boolean): LossBasedBweV2.Config {
    return LossBasedBweV2.Config(
        enabled = enabled,
        bandwidthRampupUpperBoundFactor = if (valid) 1.2 else 0.0,
        trendlineIntegrationEnabled = trendlineIntegrationEnabled,
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
        observationDurationLowerBound = kObservationDurationLowerBound,
        maxIncreaseFactor = kMaxIncreaseFactor,
        delayedIncreaseWindow = kDelayedIncreaseWindow
    )
}

private fun createPacketResultsWithReceivedPackets(firstPacketTimestamp: Instant): Array<PacketResult> {
    val enoughFeedback = Array(2) { PacketResult() }
    enoughFeedback[0].sentPacket.size = 15_000.bytes
    enoughFeedback[1].sentPacket.size = 15_000.bytes
    enoughFeedback[0].sentPacket.sendTime = firstPacketTimestamp
    enoughFeedback[1].sentPacket.sendTime = firstPacketTimestamp + kObservationDurationLowerBound
    enoughFeedback[0].receiveTime = firstPacketTimestamp + kObservationDurationLowerBound
    enoughFeedback[1].receiveTime = firstPacketTimestamp + kObservationDurationLowerBound * 2

    return enoughFeedback
}

private fun createPacketResultsWith10pLossRate(firstPacketTimestamp: Instant): Array<PacketResult> {
    val enoughFeedback = Array(10) { PacketResult() }
    enoughFeedback[0].sentPacket.size = 15_000.bytes

    for (i in enoughFeedback.indices) {
        enoughFeedback[i].sentPacket.size = 15_000.bytes
        enoughFeedback[i].sentPacket.sendTime = firstPacketTimestamp + kObservationDurationLowerBound * i
        enoughFeedback[i].receiveTime = firstPacketTimestamp + kObservationDurationLowerBound * (i + 1)
    }
    enoughFeedback[9].receiveTime = NEVER
    return enoughFeedback
}

private fun createPacketResultsWith50pLossRate(firstPacketTimestamp: Instant): Array<PacketResult> {
    val enoughFeedback = Array(2) { PacketResult() }
    enoughFeedback[0].sentPacket.size = 15_000.bytes
    enoughFeedback[1].sentPacket.size = 15_000.bytes
    enoughFeedback[0].sentPacket.sendTime = firstPacketTimestamp
    enoughFeedback[1].sentPacket.sendTime = firstPacketTimestamp + kObservationDurationLowerBound
    enoughFeedback[0].receiveTime = firstPacketTimestamp + kObservationDurationLowerBound
    enoughFeedback[1].receiveTime = NEVER
    return enoughFeedback
}

private fun createPacketResultsWith100pLossRate(firstPacketTimestamp: Instant): Array<PacketResult> {
    val enoughFeedback = Array(2) { PacketResult() }
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
            Exhaustive.boolean().checkAll {
                val config = config(enabled = true, valid = true, trendlineIntegrationEnabled = it)
                val lossBasedBandwidthEstimator = LossBasedBweV2(config)
                lossBasedBandwidthEstimator.isEnabled() shouldBe true
            }
        }
        "DisabledWhenGivenDisabledConfiguration" {
            Exhaustive.boolean().checkAll {
                val config = config(enabled = false, valid = true, trendlineIntegrationEnabled = it)
                val lossBasedBandwidthEstimator = LossBasedBweV2(config)
                lossBasedBandwidthEstimator.isEnabled() shouldBe false
            }
        }
        "DisabledWhenGivenNonValidConfigurationValues" {
            Exhaustive.boolean().checkAll {
                val config = config(enabled = true, valid = false, trendlineIntegrationEnabled = it)
                val lossBasedBandwidthEstimator = LossBasedBweV2(config)
                lossBasedBandwidthEstimator.isEnabled() shouldBe false
            }
        }
        "DisabledWhenGivenNonPositiveCandidateFactor" {
            val configNegativeCandidateFactor =
                LossBasedBweV2.Config(enabled = true, candidateFactors = doubleArrayOf(-1.3, 1.1))
            val lossBasedBandwidthEstimator1 = LossBasedBweV2(configNegativeCandidateFactor)
            lossBasedBandwidthEstimator1.isEnabled() shouldBe false

            val configZeroCandidateFactor =
                LossBasedBweV2.Config(enabled = true, candidateFactors = doubleArrayOf(-0.0, 1.1))
            val lossBasedBandwidthEstimator2 = LossBasedBweV2(configZeroCandidateFactor)
            lossBasedBandwidthEstimator2.isEnabled() shouldBe false
        }
        "DisabledWhenGivenConfigurationThatDoesNotAllowGeneratingCandidates" {
            val config =
                LossBasedBweV2.Config(
                    enabled = true,
                    candidateFactors = doubleArrayOf(1.0),
                    appendAcknowledgedRateCandidate = false,
                    appendDelayBasedEstimateCandidate = false
                )
            val lossBasedBandwidthEstimator = LossBasedBweV2(config)
            lossBasedBandwidthEstimator.isEnabled() shouldBe false
        }
        "ReturnsDelayBasedEstimateWhenDisabled" {
            Exhaustive.boolean().checkAll {
                val config = config(enabled = false, valid = true, trendlineIntegrationEnabled = false)
                val lossBasedBandwidthEstimator = LossBasedBweV2(config)
                lossBasedBandwidthEstimator.updateBandwidthEstimate(
                    arrayOf(),
                    100.kbps,
                    BandwidthUsage.kBwNormal,
                    null,
                    Bandwidth.INFINITY,
                    false
                )
                lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBe 100.kbps
            }
        }
        "ReturnsDelayBasedEstimateWhenWhenGivenNonValidConfigurationValues" {
            Exhaustive.boolean().checkAll {
                val config = config(enabled = true, valid = false, trendlineIntegrationEnabled = false)
                val lossBasedBandwidthEstimator = LossBasedBweV2(config)
                lossBasedBandwidthEstimator.updateBandwidthEstimate(
                    arrayOf(),
                    100.kbps,
                    BandwidthUsage.kBwNormal,
                    null,
                    Bandwidth.INFINITY,
                    false
                )
                lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBe 100.kbps
            }
        }
        "BandwidthEstimateGivenInitializationAndThenFeedback" {
            Exhaustive.boolean().checkAll {
                val enoughFeedback = createPacketResultsWithReceivedPackets(Instant.ofEpochMilli(0))

                val config = config(enabled = true, valid = true, trendlineIntegrationEnabled = it)
                val lossBasedBandwidthEstimator = LossBasedBweV2(config)

                lossBasedBandwidthEstimator.setBandwidthEstimate(600.kbps)
                lossBasedBandwidthEstimator.updateBandwidthEstimate(
                    enoughFeedback,
                    Bandwidth.INFINITY,
                    BandwidthUsage.kBwNormal,
                    null,
                    Bandwidth.INFINITY,
                    false
                )

                lossBasedBandwidthEstimator.isReady() shouldBe true
                lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate.isFinite() shouldBe true
            }
        }
        "NoBandwidthEstimateGivenNoInitialization" {
            Exhaustive.boolean().checkAll {
                val enoughFeedback = createPacketResultsWithReceivedPackets(Instant.EPOCH)

                val config = config(enabled = true, valid = true, trendlineIntegrationEnabled = it)
                val lossBasedBandwidthEstimator = LossBasedBweV2(config)

                lossBasedBandwidthEstimator.updateBandwidthEstimate(
                    enoughFeedback,
                    Bandwidth.INFINITY,
                    BandwidthUsage.kBwNormal,
                    null,
                    Bandwidth.INFINITY,
                    false
                )
                lossBasedBandwidthEstimator.isReady() shouldBe false
                lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBe Bandwidth.INFINITY
            }
        }
        "NoBandwidthEstimateGivenNotEnoughFeedback" {
            Exhaustive.boolean().checkAll {
                val notEnoughFeedback = Array(2) { PacketResult() }
                notEnoughFeedback[0].sentPacket.size = 15_000.bytes
                notEnoughFeedback[1].sentPacket.size = 15_000.bytes
                notEnoughFeedback[0].sentPacket.sendTime = Instant.EPOCH
                notEnoughFeedback[1].sentPacket.sendTime = Instant.EPOCH + kObservationDurationLowerBound / 2
                notEnoughFeedback[0].receiveTime = Instant.EPOCH + kObservationDurationLowerBound / 2
                notEnoughFeedback[1].receiveTime = Instant.EPOCH + kObservationDurationLowerBound

                val config = config(enabled = true, valid = true, trendlineIntegrationEnabled = it)
                val lossBasedBandwidthEstimator = LossBasedBweV2(config)

                lossBasedBandwidthEstimator.setBandwidthEstimate(600.kbps)

                lossBasedBandwidthEstimator.isReady() shouldBe false
                lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBe Bandwidth.INFINITY

                lossBasedBandwidthEstimator.updateBandwidthEstimate(
                    notEnoughFeedback,
                    Bandwidth.INFINITY,
                    BandwidthUsage.kBwNormal,
                    null,
                    Bandwidth.INFINITY,
                    false
                )

                lossBasedBandwidthEstimator.isReady() shouldBe false
                lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBe Bandwidth.INFINITY
            }
        }

        "SetValueIsTheEstimateUntilAdditionalFeedbackHasBeenReceived" {
            Exhaustive.boolean().checkAll {
                val enoughFeedback1 = createPacketResultsWithReceivedPackets(Instant.EPOCH)
                val enoughFeedback2 = createPacketResultsWithReceivedPackets(
                    Instant.EPOCH + kObservationDurationLowerBound * 2
                )

                val config = config(enabled = true, valid = true, trendlineIntegrationEnabled = it)
                val lossBasedBandwidthEstimator = LossBasedBweV2(config)

                lossBasedBandwidthEstimator.setBandwidthEstimate(600.kbps)
                lossBasedBandwidthEstimator.updateBandwidthEstimate(
                    enoughFeedback1,
                    Bandwidth.INFINITY,
                    BandwidthUsage.kBwNormal,
                    null,
                    Bandwidth.INFINITY,
                    false
                )

                lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldNotBe 600.kbps

                lossBasedBandwidthEstimator.setBandwidthEstimate(600.kbps)

                lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBe 600.kbps

                lossBasedBandwidthEstimator.updateBandwidthEstimate(
                    enoughFeedback2,
                    Bandwidth.INFINITY,
                    BandwidthUsage.kBwNormal,
                    null,
                    Bandwidth.INFINITY,
                    false
                )

                lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldNotBe 600.kbps
            }
        }
        "SetAcknowledgedBitrateOnlyAffectsTheBweWhenAdditionalFeedbackIsGiven" {
            Exhaustive.boolean().checkAll {
                val enoughFeedback1 = createPacketResultsWithReceivedPackets(Instant.EPOCH)
                val enoughFeedback2 = createPacketResultsWithReceivedPackets(
                    Instant.EPOCH + kObservationDurationLowerBound * 2
                )

                val config = config(enabled = true, valid = true, trendlineIntegrationEnabled = it)
                val lossBasedBandwidthEstimator1 = LossBasedBweV2(config)
                val lossBasedBandwidthEstimator2 = LossBasedBweV2(config)

                lossBasedBandwidthEstimator1.setBandwidthEstimate(600.kbps)
                lossBasedBandwidthEstimator2.setBandwidthEstimate(600.kbps)

                lossBasedBandwidthEstimator1.updateBandwidthEstimate(
                    enoughFeedback1,
                    Bandwidth.INFINITY,
                    BandwidthUsage.kBwNormal,
                    null,
                    Bandwidth.INFINITY,
                    false
                )

                lossBasedBandwidthEstimator2.updateBandwidthEstimate(
                    enoughFeedback1,
                    Bandwidth.INFINITY,
                    BandwidthUsage.kBwNormal,
                    null,
                    Bandwidth.INFINITY,
                    false
                )

                lossBasedBandwidthEstimator1.getLossBasedResult().bandwidthEstimate shouldBe 660.kbps

                lossBasedBandwidthEstimator1.setAcknowledgedBitrate(900.kbps)

                lossBasedBandwidthEstimator1.getLossBasedResult().bandwidthEstimate shouldBe 660.kbps

                lossBasedBandwidthEstimator1.updateBandwidthEstimate(
                    enoughFeedback2,
                    Bandwidth.INFINITY,
                    BandwidthUsage.kBwNormal,
                    null,
                    Bandwidth.INFINITY,
                    false
                )

                lossBasedBandwidthEstimator2.updateBandwidthEstimate(
                    enoughFeedback2,
                    Bandwidth.INFINITY,
                    BandwidthUsage.kBwNormal,
                    null,
                    Bandwidth.INFINITY,
                    false
                )

                lossBasedBandwidthEstimator1.getLossBasedResult().bandwidthEstimate shouldNotBe
                    lossBasedBandwidthEstimator2.getLossBasedResult().bandwidthEstimate
            }
        }
        "BandwidthEstimateIsCappedToBeTcpFairGivenTooHighLossRate" {
            Exhaustive.boolean().checkAll {
                val enoughFeedbackNoReceivedPackets = createPacketResultsWith100pLossRate(Instant.EPOCH)

                val config = config(enabled = true, valid = true, trendlineIntegrationEnabled = it)
                val lossBasedBandwidthEstimator = LossBasedBweV2(config)

                lossBasedBandwidthEstimator.setBandwidthEstimate(600.kbps)
                lossBasedBandwidthEstimator.updateBandwidthEstimate(
                    enoughFeedbackNoReceivedPackets,
                    Bandwidth.INFINITY,
                    BandwidthUsage.kBwNormal,
                    null,
                    Bandwidth.INFINITY,
                    false
                )

                lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBe 100.kbps
            }
        }

        "BandwidthEstimateNotIncreaseWhenNetworkUnderusing" {
            // This test should run only if trendline_integration_enabled is enabled
            val enoughFeedback1 = createPacketResultsWithReceivedPackets(Instant.EPOCH)
            val enoughFeedback2 = createPacketResultsWithReceivedPackets(
                Instant.EPOCH + kObservationDurationLowerBound * 2
            )

            val config = config(enabled = true, valid = true, trendlineIntegrationEnabled = true)
            val lossBasedBandwidthEstimator = LossBasedBweV2(config)

            lossBasedBandwidthEstimator.setBandwidthEstimate(600.kbps)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback1,
                Bandwidth.INFINITY,
                BandwidthUsage.kBwUnderusing,
                null,
                Bandwidth.INFINITY,
                false
            )
            lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBeLessThanOrEqualTo 600.kbps
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback2,
                Bandwidth.INFINITY,
                BandwidthUsage.kBwNormal,
                null,
                Bandwidth.INFINITY,
                false
            )
            lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBeLessThanOrEqualTo 600.kbps
        }

        // When network is normal, estimate can increase but never be higher than
        // the delay based estimate.
        "BandwidthEstimateCappedByDelayBasedEstimateWhenNetworkNormal" {
            Exhaustive.boolean().checkAll {
                val enoughFeedback1 = createPacketResultsWithReceivedPackets(Instant.EPOCH)
                val enoughFeedback2 = createPacketResultsWithReceivedPackets(
                    Instant.EPOCH + kObservationDurationLowerBound * 2
                )

                val config = config(enabled = true, valid = true, trendlineIntegrationEnabled = it)
                val lossBasedBandwidthEstimator = LossBasedBweV2(config)

                lossBasedBandwidthEstimator.setBandwidthEstimate(600.kbps)
                lossBasedBandwidthEstimator.updateBandwidthEstimate(
                    enoughFeedback1,
                    Bandwidth.INFINITY,
                    BandwidthUsage.kBwNormal,
                    null,
                    Bandwidth.INFINITY,
                    false
                )

                // If the delay based estimate is infinity, then loss based estimate increases
                // and not bounded by delay based estimate.
                lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBeGreaterThan 600.kbps
                lossBasedBandwidthEstimator.updateBandwidthEstimate(
                    enoughFeedback2,
                    500.kbps,
                    BandwidthUsage.kBwNormal,
                    null,
                    Bandwidth.INFINITY,
                    false
                )
                // If the delay based estimate is not infinity, then loss based estimate is
                // bounded by delay based estimate.
                lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBe 500.kbps
            }
        }

        // When loss based bwe receives a strong signal of overusing and an increase in
        // loss rate, it should acked bitrate for emegency backoff.
        "UseAckedBitrateForEmegencyBackOff" {
            Exhaustive.boolean().checkAll {
                // Create two packet results, first packet has 50% loss rate, second packet
                // has 100% loss rate.
                val enoughFeedback1 = createPacketResultsWith50pLossRate(Instant.EPOCH)
                val enoughFeedback2 = createPacketResultsWith100pLossRate(
                    Instant.EPOCH + kObservationDurationLowerBound * 2
                )

                val config = config(enabled = true, valid = true, trendlineIntegrationEnabled = it)
                val lossBasedBandwidthEstimator = LossBasedBweV2(config)

                lossBasedBandwidthEstimator.setBandwidthEstimate(600.kbps)
                val ackedBitrate = 300.kbps

                lossBasedBandwidthEstimator.setAcknowledgedBitrate(ackedBitrate)
                // Update estimate when network is overusing, and 50% loss rate.
                lossBasedBandwidthEstimator.updateBandwidthEstimate(
                    enoughFeedback1,
                    Bandwidth.INFINITY,
                    BandwidthUsage.kBwOverusing,
                    null,
                    Bandwidth.INFINITY,
                    false
                )
                // Update estimate again when network is continuously overusing, and 100%
                // loss rate.
                lossBasedBandwidthEstimator.updateBandwidthEstimate(
                    enoughFeedback2,
                    Bandwidth.INFINITY,
                    BandwidthUsage.kBwOverusing,
                    null,
                    Bandwidth.INFINITY,
                    false
                )
                // The estimate bitrate now is backed off based on acked bitrate.
                lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBeLessThanOrEqualTo
                    ackedBitrate
            }
        }

        // When receiving the same packet feedback, loss based bwe ignores the feedback
        // and returns the current estimate.
        "NoBweChangeIfObservationDurationUnchanged" {
            Exhaustive.boolean().checkAll {
                val enoughFeedback1 = createPacketResultsWithReceivedPackets(Instant.EPOCH)
                val config = config(enabled = true, valid = true, trendlineIntegrationEnabled = it)
                val lossBasedBandwidthEstimator = LossBasedBweV2(config)
                lossBasedBandwidthEstimator.setBandwidthEstimate(600.kbps)
                lossBasedBandwidthEstimator.setAcknowledgedBitrate(300.kbps)

                lossBasedBandwidthEstimator.updateBandwidthEstimate(
                    enoughFeedback1,
                    Bandwidth.INFINITY,
                    BandwidthUsage.kBwNormal,
                    null,
                    Bandwidth.INFINITY,
                    false
                )

                val estimate1 = lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate

                // Use the same feedback and check if the estimate is unchanged.
                lossBasedBandwidthEstimator.updateBandwidthEstimate(
                    enoughFeedback1,
                    Bandwidth.INFINITY,
                    BandwidthUsage.kBwNormal,
                    null,
                    Bandwidth.INFINITY,
                    false
                )

                val estimate2 = lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate
                estimate1 shouldBe estimate2
            }
        }

        // When receiving feedback of packets that were sent within an observation
        // duration, and network is in the normal state, loss based bwe returns the
        // current estimate.
        "NoBweChangeIfObservationDurationIsSmallAndNetworkNormal" {
            Exhaustive.boolean().checkAll {
                val enoughFeedback1 = createPacketResultsWithReceivedPackets(Instant.EPOCH)
                val enoughFeedback2 = createPacketResultsWithReceivedPackets(
                    Instant.EPOCH + kObservationDurationLowerBound - 1.ms
                )
                val config = config(enabled = true, valid = true, trendlineIntegrationEnabled = it)
                val lossBasedBandwidthEstimator = LossBasedBweV2(config)
                lossBasedBandwidthEstimator.setBandwidthEstimate(600.kbps)
                lossBasedBandwidthEstimator.updateBandwidthEstimate(
                    enoughFeedback1,
                    Bandwidth.INFINITY,
                    BandwidthUsage.kBwNormal,
                    null,
                    Bandwidth.INFINITY,
                    false
                )
                val estimate1 = lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate
                lossBasedBandwidthEstimator.updateBandwidthEstimate(
                    enoughFeedback2,
                    Bandwidth.INFINITY,
                    BandwidthUsage.kBwNormal,
                    null,
                    Bandwidth.INFINITY,
                    false
                )
                val estimate2 = lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate
                estimate2 shouldBe estimate1
            }
        }

        // When receiving feedback of packets that were sent within an observation
        // duration, and network is in the underusing state, loss based bwe returns the
        // current estimate.
        "NoBweIncreaseIfObservationDurationIsSmallAndNetworkUnderusing" {
            Exhaustive.boolean().checkAll {
                val enoughFeedback1 = createPacketResultsWithReceivedPackets(Instant.EPOCH)
                val enoughFeedback2 = createPacketResultsWithReceivedPackets(
                    Instant.EPOCH + kObservationDurationLowerBound - 1.ms
                )
                val config = config(enabled = true, valid = true, trendlineIntegrationEnabled = it)
                val lossBasedBandwidthEstimator = LossBasedBweV2(config)
                lossBasedBandwidthEstimator.setBandwidthEstimate(600.kbps)
                lossBasedBandwidthEstimator.updateBandwidthEstimate(
                    enoughFeedback1,
                    Bandwidth.INFINITY,
                    BandwidthUsage.kBwNormal,
                    null,
                    Bandwidth.INFINITY,
                    false
                )
                val estimate1 = lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate
                lossBasedBandwidthEstimator.updateBandwidthEstimate(
                    enoughFeedback2,
                    Bandwidth.INFINITY,
                    BandwidthUsage.kBwUnderusing,
                    null,
                    Bandwidth.INFINITY,
                    false
                )
                val estimate2 = lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate
                estimate2 shouldBe estimate1
            }
        }

        // When receiving feedback of packets that were sent within an observation
        // duration, network is overusing, and trendline integration is enabled, loss
        // based bwe updates its estimate.
        "UpdateEstimateIfObservationDurationIsSmallAndNetworkOverusing" {
            // This test should run only if trendline_integration_enabled is enabled
            val enoughFeedback1 = createPacketResultsWith50pLossRate(Instant.EPOCH)
            val enoughFeedback2 = createPacketResultsWith100pLossRate(
                Instant.EPOCH + kObservationDurationLowerBound - 1.ms
            )
            val config = config(enabled = true, valid = true, trendlineIntegrationEnabled = true)
            val lossBasedBandwidthEstimator = LossBasedBweV2(config)

            lossBasedBandwidthEstimator.setBandwidthEstimate(600.kbps)
            lossBasedBandwidthEstimator.setAcknowledgedBitrate(300.kbps)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback1,
                Bandwidth.INFINITY,
                BandwidthUsage.kBwNormal,
                null,
                Bandwidth.INFINITY,
                false
            )
            val estimate1 = lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate

            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback2,
                Bandwidth.INFINITY,
                BandwidthUsage.kBwOverusing,
                null,
                Bandwidth.INFINITY,
                false
            )
            val estimate2 = lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate
            estimate2 shouldBeLessThan estimate1
        }
    }
}
