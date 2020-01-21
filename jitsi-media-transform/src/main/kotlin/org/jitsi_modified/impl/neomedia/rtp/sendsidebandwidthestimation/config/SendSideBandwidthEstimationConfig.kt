/*
 * Copyright @ 2018 - present 8x8, Inc.
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

package org.jitsi_modified.impl.neomedia.rtp.sendsidebandwidthestimation.config

import org.jitsi.config.LegacyFallbackConfigProperty
import org.jitsi.config.legacyConfigAttributes
import org.jitsi.config.newConfigAttributes
import org.jitsi.nlj.util.Bandwidth
import org.jitsi.utils.config.FallbackProperty
import org.jitsi.utils.config.SimpleProperty

class SendSideBandwidthEstimationConfig {
    class Config {
        companion object {
            private const val LEGACY_BASE_NAME =
                "org.jitsi.impl.neomedia.rtp.sendsidebandwidthestimation.SendSideBandwidthEstimation"

            /**
             * The property that specifies the low-loss threshold
             * (expressed as a proportion of lost packets) when the loss probability
             * experiment is *not* active
             */
            class DefaultLowLossThresholdProperty : SimpleProperty<Double>(
                newConfigAttributes {
                    readOnce()
                    name("jmt.bwe.send-side.low-loss-threshold")
                }
            )
            private val defaultLowLossThresholdProp =
                DefaultLowLossThresholdProperty()

            @JvmStatic
            fun defaultLowLossThreshold(): Double = defaultLowLossThresholdProp.value

            /*
             * The property that specifies the high-loss threshold
             * (expressed as a proportion of lost packets) when the loss probability
             * experiment is *not* active.
             */
            class DefaultHighLossThresholdProperty : SimpleProperty<Double>(
                newConfigAttributes {
                    readOnce()
                    name("jmt.bwe.send-side.high-loss-threshold")
                }
            )
            private val defaultHighLossThresholdProp =
                DefaultHighLossThresholdProperty()

            @JvmStatic
            fun defaultHighLossThreshold(): Double = defaultHighLossThresholdProp.value

            /**
             * The property that specifies the bitrate threshold when the
             * loss probability experiment is *not* active.
             */
            class DefaultBitrateThresholdProperty : SimpleProperty<Bandwidth>(
                newConfigAttributes {
                    name("jmt.bwe.send-side.bitrate-threshold")
                    readOnce()
                    retrievedAs<String>() convertedBy(Bandwidth.Companion::fromString)
                }
            )
            private val defaultBitrateThresholdProp =
                DefaultBitrateThresholdProperty()

            @JvmStatic
            fun defaultBitrateThresholdKbps(): Int = defaultBitrateThresholdProp.value.kbps.toInt()

            /**
             * The property that specifies the probability of enabling the
             * loss-based experiment.
             */
            class LossExperimentProbabilityProperty : LegacyFallbackConfigProperty<Double>(
                Double::class,
                legacyName = "$LEGACY_BASE_NAME.lossExperimentProbability",
                newName = "jmt.bwe.send-side.loss-experiment.probability",
                readOnce = true
            )
            private val lossExperimentProbabilityProp =
                LossExperimentProbabilityProperty()

            @JvmStatic
            fun lossExperimentProbability(): Double = lossExperimentProbabilityProp.value

            /**
             * The property that specifies the low-loss threshold
             * (expressed as a proportion of lost packets) when the loss probability
             * experiment is active.
             *
             * First, we'll check for an experimental value in the old config file,
             * then we'll check for an experimental value in the new config file,
             * then we'll fall back to the default value
             */
            class ExperimentalLowLossThresholdProperty : LegacyFallbackConfigProperty<Double>(
                Double::class,
                legacyName = "$LEGACY_BASE_NAME.lowLossThreshold",
                newName = "jmt.bwe.send-side.loss-experiment.low-loss-threshold",
                readOnce = true
            )
            private val experimentalLowLossThresholdProp =
                ExperimentalLowLossThresholdProperty()

            @JvmStatic
            fun experimentalLowLossThreshold(): Double = experimentalLowLossThresholdProp.value

            /*
             * The property that specifies the high-loss threshold
             * (expressed as a proportion of lost packets).
             *
             * First, we'll check for an experimental value in the old config file,
             * then we'll check for an experimental value in the new config file,
             * then we'll fall back to the default value
             */
            class ExperimentalHighLossThresholdProperty : LegacyFallbackConfigProperty<Double>(
                Double::class,
                legacyName = "$LEGACY_BASE_NAME.highLossThreshold",
                newName = "jmt.bwe.send-side.loss-experiment.high-loss-threshold",
                readOnce = true
            )
            private val experimentalHighLossThresholdProp =
                ExperimentalHighLossThresholdProperty()

            @JvmStatic
            fun experimentalHighLossThreshold(): Double = experimentalHighLossThresholdProp.value

            /**
             * The property that specifies the bitrate threshold when the
             * loss probability experiment is active.
             */
            class ExperimentalBitrateThresholdProperty : FallbackProperty<Bandwidth>(
                legacyConfigAttributes {
                    name("$LEGACY_BASE_NAME.bitrateThresholdKbps")
                    readOnce()
                },
                newConfigAttributes {
                    name("jmt.bwe.send-side.loss-experiment.bitrate-threshold")
                    readOnce()
                    retrievedAs<String>() convertedBy(Bandwidth.Companion::fromString)
                }
            )
            private val experimentalBitrateThresholdProp =
                ExperimentalBitrateThresholdProperty()

            @JvmStatic
            fun experimentalBitrateThresholdKbps(): Int = experimentalBitrateThresholdProp.value.kbps.toInt()

            /**
             * The property that specifies the probability of enabling the
             * timeout experiment.
             */
            class TimeoutExperimentProbabilityProperty : LegacyFallbackConfigProperty<Double>(
                Double::class,
                readOnce = true,
                legacyName = "$LEGACY_BASE_NAME.timeoutExperimentProbability",
                newName = "jmt.bwe.send-side.timeout-experiment.probability"
            )

            private val timeoutExperimentProbabilityProp =
                TimeoutExperimentProbabilityProperty()

            @JvmStatic
            fun timeoutExperimentProbability(): Double = timeoutExperimentProbabilityProp.value
        }
    }
}
