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
import io.kotest.property.Exhaustive
import io.kotest.property.checkAll
import io.kotest.property.exhaustive.boolean
import org.jitsi.nlj.util.Bandwidth
import org.jitsi.nlj.util.NEVER
import org.jitsi.nlj.util.bytes
import org.jitsi.nlj.util.div
import org.jitsi.nlj.util.kbps
import org.jitsi.utils.ms
import org.jitsi.utils.secs
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
                probeBitrate = null,
                upperLinkCapacity = Bandwidth.INFINITY,
                inAlr = false
            )
            lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBeLessThanOrEqualTo 600.kbps
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback2,
                Bandwidth.INFINITY,
                BandwidthUsage.kBwNormal,
                probeBitrate = null,
                upperLinkCapacity = Bandwidth.INFINITY,
                inAlr = false
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
                probeBitrate = null,
                upperLinkCapacity = Bandwidth.INFINITY,
                inAlr = false
            )
            val estimate1 = lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate

            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback2,
                Bandwidth.INFINITY,
                BandwidthUsage.kBwOverusing,
                probeBitrate = null,
                upperLinkCapacity = Bandwidth.INFINITY,
                inAlr = false
            )
            val estimate2 = lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate
            estimate2 shouldBeLessThan estimate1
        }

        "IncreaseToDelayBasedEstimateIfNoLossOrDelayIncrease" {
            Exhaustive.boolean().checkAll {
                val enoughFeedback1 = createPacketResultsWithReceivedPackets(Instant.EPOCH)
                val enoughFeedback2 = createPacketResultsWithReceivedPackets(
                    Instant.EPOCH + kObservationDurationLowerBound * 2
                )
                val config = config(enabled = true, valid = true, trendlineIntegrationEnabled = it)
                val lossBasedBandwidthEstimator = LossBasedBweV2(config)
                val delayBasedEstimate = 5000.kbps
                lossBasedBandwidthEstimator.setBandwidthEstimate(600.kbps)

                lossBasedBandwidthEstimator.updateBandwidthEstimate(
                    enoughFeedback1,
                    delayBasedEstimate,
                    BandwidthUsage.kBwNormal,
                    null,
                    Bandwidth.INFINITY,
                    false
                )
                lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBe delayBasedEstimate
                lossBasedBandwidthEstimator.updateBandwidthEstimate(
                    enoughFeedback2,
                    delayBasedEstimate,
                    BandwidthUsage.kBwNormal,
                    null,
                    Bandwidth.INFINITY,
                    false
                )
                lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBe delayBasedEstimate
            }
        }

        "IncreaseByMaxIncreaseFactorAfterLossBasedBweBacksOff" {
            val config = LossBasedBweV2.Config(
                enabled = true,
                candidateFactors = doubleArrayOf(1.2, 1.0, 0.5),
                appendAcknowledgedRateCandidate = true,
                observationWindowSize = 2,
                observationDurationLowerBound = 200.ms,
                instantUpperBoundBandwidthBalance = 10000.kbps,
                appendDelayBasedEstimateCandidate = true,
                maxIncreaseFactor = 1.5,
                bandwidthRampupUpperBoundFactor = 2.0,
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
                BandwidthUsage.kBwNormal,
                probeBitrate = null,
                upperLinkCapacity = Bandwidth.INFINITY,
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
                BandwidthUsage.kBwNormal,
                probeBitrate = null,
                upperLinkCapacity = Bandwidth.INFINITY,
                inAlr = false
            )

            val resultAfterRecovery = lossBasedBandwidthEstimator.getLossBasedResult()

            resultAfterRecovery.bandwidthEstimate shouldBe resultAtLoss.bandwidthEstimate * 1.5
        }

        "LossBasedStateIsDelayBasedEstimateAfterNetworkRecovering" {
            val config = LossBasedBweV2.Config(
                enabled = true,
                candidateFactors = doubleArrayOf(100.0, 1.0, 0.5),
                appendAcknowledgedRateCandidate = true,
                observationWindowSize = 2,
                observationDurationLowerBound = 200.ms,
                instantUpperBoundBandwidthBalance = 10000.kbps,
                appendDelayBasedEstimateCandidate = true,
                maxIncreaseFactor = 100.0,
                bandwidthRampupUpperBoundFactor = 2.0,
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
                BandwidthUsage.kBwNormal,
                probeBitrate = null,
                upperLinkCapacity = Bandwidth.INFINITY,
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
                BandwidthUsage.kBwNormal,
                probeBitrate = null,
                upperLinkCapacity = Bandwidth.INFINITY,
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
                BandwidthUsage.kBwNormal,
                probeBitrate = null,
                upperLinkCapacity = Bandwidth.INFINITY,
                inAlr = false
            )
            lossBasedBandwidthEstimator.getLossBasedResult().state shouldBe LossBasedState.kDelayBasedEstimate
        }

        "LossBasedStateIsNotDelayBasedEstimateIfDelayBasedEsimtateInfinite" {
            val config = LossBasedBweV2.Config(
                enabled = true,
                candidateFactors = doubleArrayOf(100.0, 1.0, 0.5),
                appendAcknowledgedRateCandidate = true,
                observationWindowSize = 2,
                observationDurationLowerBound = 200.ms,
                instantUpperBoundBandwidthBalance = 10000.kbps,
                appendDelayBasedEstimateCandidate = true,
                maxIncreaseFactor = 100.0,
                bandwidthRampupUpperBoundFactor = 2.0
            )
            val lossBasedBandwidthEstimator = LossBasedBweV2(config)
            val delayBasedEstimate = Bandwidth.INFINITY
            val ackedRate = 300.kbps
            lossBasedBandwidthEstimator.setBandwidthEstimate(600.kbps)
            lossBasedBandwidthEstimator.setAcknowledgedBitrate(ackedRate)

            // Create some loss to create the loss limited scenario.
            val enoughFeedback1 = createPacketResultsWith100pLossRate(Instant.EPOCH)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback1,
                delayBasedEstimate,
                BandwidthUsage.kBwNormal,
                probeBitrate = null,
                upperLinkCapacity = Bandwidth.INFINITY,
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
                BandwidthUsage.kBwNormal,
                probeBitrate = null,
                upperLinkCapacity = Bandwidth.INFINITY,
                inAlr = false
            )
            lossBasedBandwidthEstimator.getLossBasedResult().state shouldNotBe LossBasedState.kDelayBasedEstimate
        }

        // After loss based bwe backs off, the next estimate is capped by
        // a factor of acked bitrate.
        "IncreaseByFactorOfAckedBitrateAfterLossBasedBweBacksOff" {
            val config = LossBasedBweV2.Config(
                enabled = true,
                lossThresholdOfHighBandwidthPreference = 0.99,
                bandwidthRampupUpperBoundFactor = 1.2,
                inherentLossUpperBoundOffset = 0.9,
                observationDurationLowerBound = 200.ms
            )
            val enoughFeedback1 = createPacketResultsWith100pLossRate(Instant.EPOCH)
            val enoughFeedback2 = createPacketResultsWith10pLossRate(
                Instant.EPOCH + kObservationDurationLowerBound
            )
            val lossBasedBandwidthEstimator = LossBasedBweV2(config)
            val delayBasedEstimate = 5000.kbps

            lossBasedBandwidthEstimator.setBandwidthEstimate(600.kbps)
            lossBasedBandwidthEstimator.setAcknowledgedBitrate(300.kbps)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback1,
                delayBasedEstimate,
                BandwidthUsage.kBwNormal,
                probeBitrate = null,
                upperLinkCapacity = Bandwidth.INFINITY,
                inAlr = false
            )

            // Change the acked bitrate to make sure that the estimate is bounded by a
            // factor of acked bitrate.
            val ackedBitrate = 50.kbps
            lossBasedBandwidthEstimator.setAcknowledgedBitrate(ackedBitrate)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback2,
                delayBasedEstimate,
                BandwidthUsage.kBwNormal,
                probeBitrate = null,
                upperLinkCapacity = Bandwidth.INFINITY,
                inAlr = false
            )

            // The estimate is capped by acked_bitrate * BwRampupUpperBoundFactor.
            val estimate2 = lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate
            estimate2 shouldBe ackedBitrate * 1.2
        }

        "EstimateBitrateIsBoundedDuringDelayedWindowAfterLossBasedBweBacksOff" {
            Exhaustive.boolean().checkAll {

                val enoughFeedback1 = createPacketResultsWithReceivedPackets(Instant.EPOCH)
                val enoughFeedback2 = createPacketResultsWith50pLossRate(Instant.EPOCH + kDelayedIncreaseWindow - 2.ms)
                val enoughFeedback3 =
                    createPacketResultsWithReceivedPackets(Instant.EPOCH + kDelayedIncreaseWindow - 1.ms)
                val config = config(enabled = true, valid = true, trendlineIntegrationEnabled = it)
                val lossBasedBandwidthEstimator = LossBasedBweV2(config)
                val delayBasedEstimate = 5000.kbps

                lossBasedBandwidthEstimator.setBandwidthEstimate(600.kbps)
                lossBasedBandwidthEstimator.setAcknowledgedBitrate(300.kbps)
                lossBasedBandwidthEstimator.updateBandwidthEstimate(
                    enoughFeedback1,
                    delayBasedEstimate,
                    BandwidthUsage.kBwNormal,
                    probeBitrate = null,
                    upperLinkCapacity = Bandwidth.INFINITY,
                    inAlr = false
                )
                // Increase the acknowledged bitrate to make sure that the estimate is not
                // capped too low.
                lossBasedBandwidthEstimator.setAcknowledgedBitrate(5000.kbps)
                lossBasedBandwidthEstimator.updateBandwidthEstimate(
                    enoughFeedback2,
                    delayBasedEstimate,
                    BandwidthUsage.kBwNormal,
                    probeBitrate = null,
                    upperLinkCapacity = Bandwidth.INFINITY,
                    inAlr = false
                )

                // The estimate is capped by current_estimate * kMaxIncreaseFactor because
                // it recently backed off.
                val estimate2 = lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate

                lossBasedBandwidthEstimator.updateBandwidthEstimate(
                    enoughFeedback3,
                    delayBasedEstimate,
                    BandwidthUsage.kBwNormal,
                    probeBitrate = null,
                    upperLinkCapacity = Bandwidth.INFINITY,
                    inAlr = false
                )
                // The latest estimate is the same as the previous estimate since the sent
                // packets were sent within the DelayedIncreaseWindow.
                lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBe estimate2
            }
        }

        // The estimate is not bounded after the delayed increase window.
        "KeepIncreasingEstimateAfterDelayedIncreaseWindow" {
            Exhaustive.boolean().checkAll {
                val enoughFeedback1 = createPacketResultsWithReceivedPackets(Instant.EPOCH)
                val enoughFeedback2 =
                    createPacketResultsWithReceivedPackets(Instant.EPOCH + kDelayedIncreaseWindow - 1.ms)
                val enoughFeedback3 =
                    createPacketResultsWithReceivedPackets(Instant.EPOCH + kDelayedIncreaseWindow + 1.ms)
                val config = config(enabled = true, valid = true, trendlineIntegrationEnabled = it)
                val lossBasedBandwidthEstimator = LossBasedBweV2(config)
                val delayBasedEstimate = 5000.kbps

                lossBasedBandwidthEstimator.setBandwidthEstimate(600.kbps)
                lossBasedBandwidthEstimator.setAcknowledgedBitrate(300.kbps)
                lossBasedBandwidthEstimator.updateBandwidthEstimate(
                    enoughFeedback1,
                    delayBasedEstimate,
                    BandwidthUsage.kBwNormal,
                    probeBitrate = null,
                    upperLinkCapacity = Bandwidth.INFINITY,
                    inAlr = false
                )
                // Increase the acknowledged bitrate to make sure that the estimate is not
                // capped too low.
                lossBasedBandwidthEstimator.setAcknowledgedBitrate(5000.kbps)
                lossBasedBandwidthEstimator.updateBandwidthEstimate(
                    enoughFeedback2,
                    delayBasedEstimate,
                    BandwidthUsage.kBwNormal,
                    probeBitrate = null,
                    upperLinkCapacity = Bandwidth.INFINITY,
                    inAlr = false
                )

                // The estimate is capped by current_estimate * kMaxIncreaseFactor because
                // it recently backed off.
                val estimate2 = lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate

                lossBasedBandwidthEstimator.updateBandwidthEstimate(
                    enoughFeedback3,
                    delayBasedEstimate,
                    BandwidthUsage.kBwNormal,
                    probeBitrate = null,
                    upperLinkCapacity = Bandwidth.INFINITY,
                    inAlr = false
                )
                lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBeGreaterThanOrEqualTo
                    estimate2
            }
        }

        "NotIncreaseIfInherentLossLessThanAverageLoss" {
            val config = LossBasedBweV2.Config(
                enabled = true,
                candidateFactors = doubleArrayOf(1.2),
                appendAcknowledgedRateCandidate = false,
                observationWindowSize = 2,
                appendDelayBasedEstimateCandidate = true,
                instantUpperBoundBandwidthBalance = 100.kbps,
                observationDurationLowerBound = 200.ms,
                notIncreaseIfInherentLossLessThanAverageLoss = true
            )
            val lossBasedBandwidthEstimator = LossBasedBweV2(config)
            val delayBasedEstimate = 5000.kbps

            lossBasedBandwidthEstimator.setBandwidthEstimate(600.kbps)

            val enoughFeedback10pLoss1 = createPacketResultsWith10pLossRate(Instant.EPOCH)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback10pLoss1,
                delayBasedEstimate,
                BandwidthUsage.kBwNormal,
                probeBitrate = null,
                upperLinkCapacity = Bandwidth.INFINITY,
                inAlr = false
            )

            val enoughFeedback10pLoss2 =
                createPacketResultsWith10pLossRate(Instant.EPOCH + kObservationDurationLowerBound)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback10pLoss2,
                delayBasedEstimate,
                BandwidthUsage.kBwNormal,
                probeBitrate = null,
                upperLinkCapacity = Bandwidth.INFINITY,
                inAlr = false
            )

            // Do not increase the bitrate because inherent loss is less than average loss
            lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBe 600.kbps
        }

        "SelectHighBandwidthCandidateIfLossRateIsLessThanThreshold" {
            val config = LossBasedBweV2.Config(
                enabled = true,
                candidateFactors = doubleArrayOf(1.2, 0.8),
                appendAcknowledgedRateCandidate = false,
                observationWindowSize = 2,
                appendDelayBasedEstimateCandidate = true,
                instantUpperBoundBandwidthBalance = 100.kbps,
                observationDurationLowerBound = 200.ms,
                higherBandwidthBiasFactor = 1000.0,
                higherLogBandwidthBiasFactor = 1000.0,
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
                BandwidthUsage.kBwNormal,
                probeBitrate = null,
                upperLinkCapacity = Bandwidth.INFINITY,
                inAlr = false
            )

            val enoughFeedback10pLoss2 =
                createPacketResultsWith10pLossRate(Instant.EPOCH + kObservationDurationLowerBound)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback10pLoss2,
                delayBasedEstimate,
                BandwidthUsage.kBwNormal,
                probeBitrate = null,
                upperLinkCapacity = Bandwidth.INFINITY,
                inAlr = false
            )

            // Because LossThresholdOfHighBandwidthPreference is 20%, the average loss is
            // 10%, bandwidth estimate should increase.
            lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBeGreaterThan 600.kbps
        }

        "SelectLowBandwidthCandidateIfLossRateIsIsHigherThanThreshold" {
            val config = LossBasedBweV2.Config(
                enabled = true,
                candidateFactors = doubleArrayOf(1.2, 0.8),
                appendAcknowledgedRateCandidate = false,
                observationWindowSize = 2,
                appendDelayBasedEstimateCandidate = true,
                instantUpperBoundBandwidthBalance = 100.kbps,
                observationDurationLowerBound = 200.ms,
                higherBandwidthBiasFactor = 1000.0,
                higherLogBandwidthBiasFactor = 1000.0,
                lossThresholdOfHighBandwidthPreference = 0.05
            )
            val lossBasedBandwidthEstimator = LossBasedBweV2(config)
            val delayBasedEstimate = 5000.kbps

            lossBasedBandwidthEstimator.setBandwidthEstimate(600.kbps)

            val enoughFeedback10pLoss1 = createPacketResultsWith10pLossRate(Instant.EPOCH)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback10pLoss1,
                delayBasedEstimate,
                BandwidthUsage.kBwNormal,
                probeBitrate = null,
                upperLinkCapacity = Bandwidth.INFINITY,
                inAlr = false
            )

            val enoughFeedback10pLoss2 =
                createPacketResultsWith10pLossRate(Instant.EPOCH + kObservationDurationLowerBound)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback10pLoss2,
                delayBasedEstimate,
                BandwidthUsage.kBwNormal,
                probeBitrate = null,
                upperLinkCapacity = Bandwidth.INFINITY,
                inAlr = false
            )

            // Because LossThresholdOfHighBandwidthPreference is 5%, the average loss is
            // 10%, bandwidth estimate should decrease.
            lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBeLessThan 600.kbps
        }

        "LimitByProbeResultWhenRecoveringFromLoss" {
            val config = LossBasedBweV2.Config(
                enabled = true,
                candidateFactors = doubleArrayOf(1.2, 1.0, 0.5),
                appendAcknowledgedRateCandidate = true,
                observationWindowSize = 2,
                observationDurationLowerBound = 200.ms,
                instantUpperBoundBandwidthBalance = 10000.kbps,
                delayedIncreaseWindow = 100.secs,
                appendDelayBasedEstimateCandidate = true,
                maxIncreaseFactor = 1.3,
                bandwidthRampupUpperBoundFactor = 2.0,
                probeIntegrationEnabled = true
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
                BandwidthUsage.kBwNormal,
                probeBitrate = null,
                upperLinkCapacity = Bandwidth.INFINITY,
                inAlr = false
            )

            // Network recovers after loss
            val probeEstimate = 300.kbps
            var enoughFeedback2 =
                createPacketResultsWithReceivedPackets(Instant.EPOCH + kObservationDurationLowerBound)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback2,
                delayBasedEstimate,
                BandwidthUsage.kBwNormal,
                probeEstimate,
                upperLinkCapacity = Bandwidth.INFINITY,
                inAlr = false
            )

            for (i in 2 until 5) {
                enoughFeedback2 =
                    createPacketResultsWithReceivedPackets(Instant.EPOCH + kObservationDurationLowerBound * i)
                lossBasedBandwidthEstimator.updateBandwidthEstimate(
                    enoughFeedback2,
                    delayBasedEstimate,
                    BandwidthUsage.kBwNormal,
                    probeBitrate = null,
                    upperLinkCapacity = Bandwidth.INFINITY,
                    inAlr = false
                )
                val resultAfterRecovery = lossBasedBandwidthEstimator.getLossBasedResult()
                resultAfterRecovery.bandwidthEstimate shouldBeLessThanOrEqualTo probeEstimate
            }
        }

        "NotLimitByProbeResultWhenProbeResultIsExpired" {
            val config = LossBasedBweV2.Config(
                enabled = true,
                candidateFactors = doubleArrayOf(1.2, 1.0, 0.5),
                appendAcknowledgedRateCandidate = true,
                observationWindowSize = 2,
                observationDurationLowerBound = 200.ms,
                instantUpperBoundBandwidthBalance = 10000.kbps,
                delayedIncreaseWindow = 100.secs,
                appendDelayBasedEstimateCandidate = true,
                maxIncreaseFactor = 1.3,
                bandwidthRampupUpperBoundFactor = 2.0,
                probeIntegrationEnabled = true,
                probeExpiration = 10.secs
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
                BandwidthUsage.kBwNormal,
                probeBitrate = null,
                upperLinkCapacity = Bandwidth.INFINITY,
                inAlr = false
            )

            // Network recovers after loss
            val probeEstimate = 300.kbps
            var enoughFeedback2 =
                createPacketResultsWithReceivedPackets(Instant.EPOCH + kObservationDurationLowerBound)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback2,
                delayBasedEstimate,
                BandwidthUsage.kBwNormal,
                probeEstimate,
                upperLinkCapacity = Bandwidth.INFINITY,
                inAlr = false
            )

            for (i in 2 until 5) {
                enoughFeedback2 =
                    createPacketResultsWithReceivedPackets(Instant.EPOCH + kObservationDurationLowerBound * i)
                lossBasedBandwidthEstimator.updateBandwidthEstimate(
                    enoughFeedback2,
                    delayBasedEstimate,
                    BandwidthUsage.kBwNormal,
                    probeBitrate = null,
                    upperLinkCapacity = Bandwidth.INFINITY,
                    inAlr = false
                )
            }

            val enoughFeedback3 =
                createPacketResultsWithReceivedPackets(Instant.EPOCH + kObservationDurationLowerBound + 11.secs)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback3,
                delayBasedEstimate,
                BandwidthUsage.kBwNormal,
                probeBitrate = null,
                upperLinkCapacity = Bandwidth.INFINITY,
                inAlr = false
            )

            // Probe result is expired after 10s.
            val resultAfterRecovery = lossBasedBandwidthEstimator.getLossBasedResult()
            resultAfterRecovery.bandwidthEstimate shouldBeGreaterThan probeEstimate
        }

        // If BoundByUpperLinkCapacityWhenLossLimited is enabled, the estimate is
        // bounded by the upper link capacity when bandwidth is loss limited.
        "BoundEstimateByUpperLinkCapacityWhenLossLimited" {
            val config = LossBasedBweV2.Config(
                enabled = true,
                candidateFactors = doubleArrayOf(1.2, 1.0, 0.5),
                appendAcknowledgedRateCandidate = true,
                observationWindowSize = 2,
                observationDurationLowerBound = 200.ms,
                instantUpperBoundBandwidthBalance = 10000.kbps,
                appendDelayBasedEstimateCandidate = true,
                maxIncreaseFactor = 1000.0,
                bandwidthRampupUpperBoundFactor = 2.0,
                boundByUpperLinkCapacityWhenLossLimited = true
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
                BandwidthUsage.kBwNormal,
                probeBitrate = null,
                upperLinkCapacity = Bandwidth.INFINITY,
                inAlr = false
            )

            // Network recovers after loss
            val upperLinkCapacity = 10.kbps
            val enoughFeedback2 =
                createPacketResultsWithReceivedPackets(Instant.EPOCH + kObservationDurationLowerBound)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback2,
                delayBasedEstimate,
                BandwidthUsage.kBwNormal,
                probeBitrate = null,
                upperLinkCapacity,
                inAlr = false
            )

            val resultAfterRecovery = lossBasedBandwidthEstimator.getLossBasedResult()
            resultAfterRecovery.bandwidthEstimate shouldBe upperLinkCapacity
        }

        // If BoundByUpperLinkCapacityWhenLossLimited is enabled, the estimate is not
        // bounded by the upper link capacity when bandwidth is not loss limited.
        "NotBoundEstimateByUpperLinkCapacityWhenNotLossLimited" {
            val config = LossBasedBweV2.Config(
                enabled = true,
                candidateFactors = doubleArrayOf(1.2, 1.0, 0.5),
                appendAcknowledgedRateCandidate = true,
                observationWindowSize = 2,
                observationDurationLowerBound = 200.ms,
                instantUpperBoundBandwidthBalance = 10000.kbps,
                appendDelayBasedEstimateCandidate = true,
                maxIncreaseFactor = 1000.0,
                bandwidthRampupUpperBoundFactor = 2.0,
                boundByUpperLinkCapacityWhenLossLimited = true
            )
            val lossBasedBandwidthEstimator = LossBasedBweV2(config)
            val delayBasedEstimate = 5000.kbps
            val ackedRate = 300.kbps
            lossBasedBandwidthEstimator.setBandwidthEstimate(600.kbps)
            lossBasedBandwidthEstimator.setAcknowledgedBitrate(ackedRate)

            // Create a normal network without loss
            val enoughFeedback1 = createPacketResultsWithReceivedPackets(Instant.EPOCH)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback1,
                delayBasedEstimate,
                BandwidthUsage.kBwNormal,
                probeBitrate = null,
                upperLinkCapacity = Bandwidth.INFINITY,
                inAlr = false
            )

            val upperLinkCapacity = 10.kbps
            val enoughFeedback2 =
                createPacketResultsWithReceivedPackets(Instant.EPOCH + kObservationDurationLowerBound)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback2,
                delayBasedEstimate,
                BandwidthUsage.kBwNormal,
                probeBitrate = null,
                upperLinkCapacity,
                inAlr = false
            )

            val lossBasedResult = lossBasedBandwidthEstimator.getLossBasedResult()
            lossBasedResult.bandwidthEstimate shouldBeGreaterThan upperLinkCapacity
        }

        // If BoundByUpperLinkCapacityWhenLossLimited is disabled, the estimate is not
        // bounded by the upper link capacity.
        "NotBoundEstimateByUpperLinkCapacity" {
            val config = LossBasedBweV2.Config(
                enabled = true,
                candidateFactors = doubleArrayOf(1.2, 1.0, 0.5),
                appendAcknowledgedRateCandidate = true,
                observationWindowSize = 2,
                observationDurationLowerBound = 200.ms,
                instantUpperBoundBandwidthBalance = 10000.kbps,
                appendDelayBasedEstimateCandidate = true,
                maxIncreaseFactor = 1000.0,
                bandwidthRampupUpperBoundFactor = 2.0,
                boundByUpperLinkCapacityWhenLossLimited = false
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
                BandwidthUsage.kBwNormal,
                probeBitrate = null,
                upperLinkCapacity = Bandwidth.INFINITY,
                inAlr = false
            )

            // Network recovers after loss
            val upperLinkCapacity = 10.kbps
            val enoughFeedback2 =
                createPacketResultsWithReceivedPackets(Instant.EPOCH + kObservationDurationLowerBound)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback2,
                delayBasedEstimate,
                BandwidthUsage.kBwNormal,
                probeBitrate = null,
                upperLinkCapacity,
                inAlr = false
            )

            val resultAfterRecovery = lossBasedBandwidthEstimator.getLossBasedResult()
            resultAfterRecovery.bandwidthEstimate shouldBeGreaterThan upperLinkCapacity
        }

        "StricterBoundUsingHighLossRateThresholdAt10pLossRate" {
            val config = LossBasedBweV2.Config(
                enabled = true,
                candidateFactors = doubleArrayOf(1.0),
                appendAcknowledgedRateCandidate = false,
                observationWindowSize = 2,
                appendDelayBasedEstimateCandidate = true,
                instantUpperBoundBandwidthBalance = 100.kbps,
                observationDurationLowerBound = 200.ms,
                higherBandwidthBiasFactor = 1000.0,
                higherLogBandwidthBiasFactor = 1000.0,
                lossThresholdOfHighBandwidthPreference = 0.05,
                highLossRateThreshold = 0.09,
            )
            val lossBasedBandwidthEstimator = LossBasedBweV2(config)
            lossBasedBandwidthEstimator.setMinMaxBitrate(10.kbps, 1000000.kbps)
            val delayBasedEstimate = 5000.kbps
            lossBasedBandwidthEstimator.setBandwidthEstimate(600.kbps)

            val enoughFeedback10pLoss1 = createPacketResultsWith10pLossRate(Instant.EPOCH)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback10pLoss1,
                delayBasedEstimate,
                BandwidthUsage.kBwNormal,
                probeBitrate = null,
                upperLinkCapacity = Bandwidth.INFINITY,
                inAlr = false
            )

            val enoughFeedback10pLoss2 =
                createPacketResultsWith10pLossRate(Instant.EPOCH + kObservationDurationLowerBound)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback10pLoss2,
                delayBasedEstimate,
                BandwidthUsage.kBwNormal,
                probeBitrate = null,
                upperLinkCapacity = Bandwidth.INFINITY,
                inAlr = false
            )

            // At 10% loss rate and high loss rate threshold to be 10%, cap the estimate
            // to be 500 * 1000-0.1 = 400kbps.
            lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBe 400.kbps
        }

        "StricterBoundUsingHighLossRateThresholdAt50pLossRate" {
            val config = LossBasedBweV2.Config(
                enabled = true,
                candidateFactors = doubleArrayOf(1.0),
                appendAcknowledgedRateCandidate = false,
                observationWindowSize = 2,
                appendDelayBasedEstimateCandidate = true,
                instantUpperBoundBandwidthBalance = 100.kbps,
                observationDurationLowerBound = 200.ms,
                higherBandwidthBiasFactor = 1000.0,
                higherLogBandwidthBiasFactor = 1000.0,
                lossThresholdOfHighBandwidthPreference = 0.05,
                highLossRateThreshold = 0.3,
            )
            val lossBasedBandwidthEstimator = LossBasedBweV2(config)
            lossBasedBandwidthEstimator.setMinMaxBitrate(10.kbps, 1000000.kbps)
            val delayBasedEstimate = 5000.kbps
            lossBasedBandwidthEstimator.setBandwidthEstimate(600.kbps)

            val enoughFeedback50pLoss1 = createPacketResultsWith50pLossRate(Instant.EPOCH)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback50pLoss1,
                delayBasedEstimate,
                BandwidthUsage.kBwNormal,
                probeBitrate = null,
                upperLinkCapacity = Bandwidth.INFINITY,
                inAlr = false
            )

            val enoughFeedback50pLoss2 =
                createPacketResultsWith50pLossRate(Instant.EPOCH + kObservationDurationLowerBound)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback50pLoss2,
                delayBasedEstimate,
                BandwidthUsage.kBwNormal,
                probeBitrate = null,
                upperLinkCapacity = Bandwidth.INFINITY,
                inAlr = false
            )

            // At 50% loss rate and high loss rate threshold to be 30%, cap the estimate
            // to be the min bitrate.
            lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBe 10.kbps
        }

        "StricterBoundUsingHighLossRateThresholdAt100pLossRate" {
            val config = LossBasedBweV2.Config(
                enabled = true,
                candidateFactors = doubleArrayOf(1.0),
                appendAcknowledgedRateCandidate = false,
                observationWindowSize = 2,
                appendDelayBasedEstimateCandidate = true,
                instantUpperBoundBandwidthBalance = 100.kbps,
                observationDurationLowerBound = 200.ms,
                higherBandwidthBiasFactor = 1000.0,
                higherLogBandwidthBiasFactor = 1000.0,
                lossThresholdOfHighBandwidthPreference = 0.05,
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
                BandwidthUsage.kBwNormal,
                probeBitrate = null,
                upperLinkCapacity = Bandwidth.INFINITY,
                inAlr = false
            )

            val enoughFeedback100pLoss2 =
                createPacketResultsWith100pLossRate(Instant.EPOCH + kObservationDurationLowerBound)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback100pLoss2,
                delayBasedEstimate,
                BandwidthUsage.kBwNormal,
                probeBitrate = null,
                upperLinkCapacity = Bandwidth.INFINITY,
                inAlr = false
            )

            // At 100% loss rate and high loss rate threshold to be 30%, cap the estimate
            // to be the min bitrate.
            lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBe 10.kbps
        }

        "EstimateRecoversAfterHighLoss" {
            val config = LossBasedBweV2.Config(
                enabled = true,
                candidateFactors = doubleArrayOf(1.1, 1.0, 0.9),
                appendAcknowledgedRateCandidate = false,
                observationWindowSize = 2,
                appendDelayBasedEstimateCandidate = true,
                instantUpperBoundBandwidthBalance = 100.kbps,
                observationDurationLowerBound = 200.ms,
                higherBandwidthBiasFactor = 1000.0,
                higherLogBandwidthBiasFactor = 1000.0,
                lossThresholdOfHighBandwidthPreference = 0.05,
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
                BandwidthUsage.kBwNormal,
                probeBitrate = null,
                upperLinkCapacity = Bandwidth.INFINITY,
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
                BandwidthUsage.kBwNormal,
                probeBitrate = null,
                upperLinkCapacity = Bandwidth.INFINITY,
                inAlr = false
            )

            val enoughFeedback0pLoss2 =
                createPacketResultsWithReceivedPackets(Instant.EPOCH + kObservationDurationLowerBound * 2)
            lossBasedBandwidthEstimator.updateBandwidthEstimate(
                enoughFeedback0pLoss2,
                delayBasedEstimate,
                BandwidthUsage.kBwNormal,
                probeBitrate = null,
                upperLinkCapacity = Bandwidth.INFINITY,
                inAlr = false
            )

            // The estimate increases as network recovers.
            lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBeGreaterThan 10.kbps
        }

        "EstimateIsNotHigherThanMaxBitrate" {
            Exhaustive.boolean().checkAll {
                val config = config(enabled = true, valid = true, trendlineIntegrationEnabled = it)
                val lossBasedBandwidthEstimator = LossBasedBweV2(config)
                lossBasedBandwidthEstimator.setMinMaxBitrate(10.kbps, 1000.kbps)
                lossBasedBandwidthEstimator.setBandwidthEstimate(1000.kbps)
                val enoughFeedback = createPacketResultsWithReceivedPackets(Instant.EPOCH)
                lossBasedBandwidthEstimator.updateBandwidthEstimate(
                    enoughFeedback,
                    delayBasedEstimate = Bandwidth.INFINITY,
                    BandwidthUsage.kBwNormal,
                    probeBitrate = null,
                    upperLinkCapacity = Bandwidth.INFINITY,
                    inAlr = false
                )

                lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBeLessThanOrEqualTo 1000.kbps
            }
        }

        "NotBackOffToAckedRateInAlr" {
            val config = LossBasedBweV2.Config(
                enabled = true,
                candidateFactors = doubleArrayOf(1.1, 1.0, 0.9),
                appendAcknowledgedRateCandidate = true,
                observationWindowSize = 2,
                appendDelayBasedEstimateCandidate = true,
                instantUpperBoundBandwidthBalance = 100.kbps,
                observationDurationLowerBound = 200.ms,
                notUseAckedRateInAlr = true
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
                BandwidthUsage.kBwNormal,
                probeBitrate = null,
                upperLinkCapacity = Bandwidth.INFINITY,
                inAlr = true
            )

            // Make sure that the estimate decreases but higher than acked rate.
            lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBeGreaterThan ackedRate

            lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBeLessThan 600.kbps
        }

        "BackOffToAckedRateIfNotInAlr" {
            val config = LossBasedBweV2.Config(
                enabled = true,
                candidateFactors = doubleArrayOf(1.1, 1.0, 0.9),
                appendAcknowledgedRateCandidate = true,
                observationWindowSize = 2,
                appendDelayBasedEstimateCandidate = true,
                instantUpperBoundBandwidthBalance = 100.kbps,
                observationDurationLowerBound = 200.ms,
                notUseAckedRateInAlr = true
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
                BandwidthUsage.kBwNormal,
                probeBitrate = null,
                upperLinkCapacity = Bandwidth.INFINITY,
                inAlr = false
            )

            // Make sure that the estimate decreases but higher than acked rate.
            lossBasedBandwidthEstimator.getLossBasedResult().bandwidthEstimate shouldBe ackedRate
        }
    }
}
