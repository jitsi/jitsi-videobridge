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

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Exhaustive
import io.kotest.property.checkAll
import io.kotest.property.exhaustive.boolean
import org.jitsi.nlj.util.Bandwidth
import org.jitsi.nlj.util.bytes
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
    enoughFeedback[9].receiveTime = Instant.MAX
    return enoughFeedback
}

private fun createPacketResultsWith50pLossRate(firstPacketTimestamp: Instant): Array<PacketResult> {
    val enoughFeedback = Array(2) { PacketResult() }
    enoughFeedback[0].sentPacket.size = 15_000.bytes
    enoughFeedback[1].sentPacket.size = 15_000.bytes
    enoughFeedback[0].sentPacket.sendTime = firstPacketTimestamp
    enoughFeedback[1].sentPacket.sendTime = firstPacketTimestamp + kObservationDurationLowerBound
    enoughFeedback[0].receiveTime = firstPacketTimestamp + kObservationDurationLowerBound
    enoughFeedback[1].receiveTime = Instant.MAX
    return enoughFeedback
}

private fun createPacketResultsWith100pLossRate(firstPacketTimestamp: Instant): Array<PacketResult> {
    val enoughFeedback = Array(2) { PacketResult() }
    enoughFeedback[0].sentPacket.size = 15_000.bytes
    enoughFeedback[1].sentPacket.size = 15_000.bytes
    enoughFeedback[0].sentPacket.sendTime = firstPacketTimestamp
    enoughFeedback[1].sentPacket.sendTime = firstPacketTimestamp + kObservationDurationLowerBound
    enoughFeedback[0].receiveTime = Instant.MAX
    enoughFeedback[1].receiveTime = Instant.MAX
    return enoughFeedback
}

class LossBasedBweV2Test : ShouldSpec() {
    init {
        context("EnabledWhenGivenValidConfigurationValues") {
            should("work correctly") {
                Exhaustive.boolean().checkAll {
                    val config = config(enabled = true, valid = true, trendlineIntegrationEnabled = it)
                    val lossBasedBandwidthEstimator = LossBasedBweV2(config)
                    lossBasedBandwidthEstimator.isEnabled() shouldBe true
                }
            }
        }
        context("DisabledWhenGivenDisabledConfiguration") {
            should("work correctly") {
                Exhaustive.boolean().checkAll {
                    val config = config(enabled = false, valid = true, trendlineIntegrationEnabled = it)
                    val lossBasedBandwidthEstimator = LossBasedBweV2(config)
                    lossBasedBandwidthEstimator.isEnabled() shouldBe false
                }
            }
        }
        context("DisabledWhenGivenNonValidConfigurationValues") {
            should("work correctly") {
                Exhaustive.boolean().checkAll {
                    val config = config(enabled = true, valid = false, trendlineIntegrationEnabled = it)
                    val lossBasedBandwidthEstimator = LossBasedBweV2(config)
                    lossBasedBandwidthEstimator.isEnabled() shouldBe false
                }
            }
        }
        context("DisabledWhenGivenNonPositiveCandidateFactor") {
            should("work correctly") {
                val configNegativeCandidateFactor =
                    LossBasedBweV2.Config(enabled = true, candidateFactors = doubleArrayOf(-1.3, 1.1))
                val lossBasedBandwidthEstimator1 = LossBasedBweV2(configNegativeCandidateFactor)
                lossBasedBandwidthEstimator1.isEnabled() shouldBe false

                val configZeroCandidateFactor =
                    LossBasedBweV2.Config(enabled = true, candidateFactors = doubleArrayOf(-0.0, 1.1))
                val lossBasedBandwidthEstimator2 = LossBasedBweV2(configZeroCandidateFactor)
                lossBasedBandwidthEstimator2.isEnabled() shouldBe false
            }
        }
        context("DisabledWhenGivenConfigurationThatDoesNotAllowGeneratingCandidates") {
            should("work correctly") {
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
        }
        context("ReturnsDelayBasedEstimateWhenDisabled") {
            should("work correctly") {
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
        }
        context("ReturnsDelayBasedEstimateWhenWhenGivenNonValidConfigurationValues") {
            should("work correctly") {
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
        }
        context("BandwidthEstimateGivenInitializationAndThenFeedback") {
            should("work correctly") {
                Exhaustive.boolean().checkAll {
                    val enoughFeedback = createPacketResultsWithReceivedPackets(Instant.ofEpochMilli(0))

                    val config = config(enabled = true, valid = true, trendlineIntegrationEnabled = it)
                    val lossBasedBandwidthEstimator = LossBasedBweV2(config)

                    lossBasedBandwidthEstimator.setBandwidthEstiamte(600.kbps)
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
        }
    }
}
