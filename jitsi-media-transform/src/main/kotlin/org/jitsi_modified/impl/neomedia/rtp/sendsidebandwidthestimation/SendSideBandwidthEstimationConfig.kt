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

package org.jitsi_modified.impl.neomedia.rtp.sendsidebandwidthestimation

import org.jitsi.config.JitsiConfig
import org.jitsi.config.legacyProperty
import org.jitsi.config.newProperty
import org.jitsi.config.simple
import org.jitsi.nlj.util.Bandwidth
import org.jitsi.utils.config.dsl.multiProperty
import org.jitsi.utils.config.dsl.property

class SendSideBandwidthEstimationConfig {
    companion object {
        private const val LEGACY_BASE_NAME =
            "org.jitsi.impl.neomedia.rtp.sendsidebandwidthestimation.SendSideBandwidthEstimation"

        /**
         * The property that specifies the low-loss threshold
         * (expressed as a proportion of lost packets) when the loss probability
         * experiment is *not* active
         */
        private val defaultLowLossThresholdProp = property<Double> {
            readOnce()
            name("jmt.bwe.send-side.low-loss-threshold")
            fromConfig(JitsiConfig.newConfig)
        }

        @JvmStatic
        fun defaultLowLossThreshold(): Double = defaultLowLossThresholdProp.value

        /*
         * The property that specifies the high-loss threshold
         * (expressed as a proportion of lost packets) when the loss probability
         * experiment is *not* active.
         */
        private val defaultHighLossThresholdProp = property<Double> {
            readOnce()
            name("jmt.bwe.send-side.high-loss-threshold")
            fromConfig(JitsiConfig.newConfig)
        }

        @JvmStatic
        fun defaultHighLossThreshold(): Double = defaultHighLossThresholdProp.value

        /**
         * The property that specifies the bitrate threshold when the
         * loss probability experiment is *not* active.
         */
        private val defaultBitrateThresholdProp = property<Bandwidth> {
            name("jmt.bwe.send-side.bitrate-threshold")
            readOnce()
            fromConfig(JitsiConfig.newConfig)
            retrievedAs<String>() convertedBy(Bandwidth.Companion::fromString)
        }

        @JvmStatic
        fun defaultBitrateThresholdKbps(): Int = defaultBitrateThresholdProp.value.kbps.toInt()

        /**
         * The property that specifies the probability of enabling the
         * loss-based experiment.
         */
        private val lossExperimentProbabilityProp = simple<Double>(
            readOnce = true,
            legacyName = "$LEGACY_BASE_NAME.lossExperimentProbability",
            newName = "jmt.bwe.send-side.loss-experiment.probability"
        )

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
        private val experimentalLowLossThresholdProp = multiProperty<Double> {
            legacyProperty {
                readOnce()
                name("$LEGACY_BASE_NAME.lowLossThreshold")
            }
            newProperty {
                readOnce()
                name("jmt.bwe.send-side.loss-experiment.low-loss-threshold")
            }
        }

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
        private val experimentalHighLossThresholdProp = multiProperty<Double> {
            legacyProperty {
                readOnce()
                name("$LEGACY_BASE_NAME.highLossThreshold")
            }
            newProperty {
                readOnce()
                name("jmt.bwe.send-side.loss-experiment.high-loss-threshold")
            }
        }

        @JvmStatic
        fun experimentalHighLossThreshold(): Double = experimentalHighLossThresholdProp.value

        /**
         * The property that specifies the bitrate threshold when the
         * loss probability experiment is active.
         */
        private val experimentalBitrateThresholdProp = multiProperty<Bandwidth> {
            legacyProperty {
                name("$LEGACY_BASE_NAME.bitrateThresholdKbps")
                readOnce()
            }
            newProperty {
                name("jmt.bwe.send-side.loss-experiment.bitrate-threshold")
                readOnce()
                retrievedAs<String>() convertedBy(Bandwidth.Companion::fromString)
            }
        }

        @JvmStatic
        fun experimentalBitrateThresholdKbps(): Int = experimentalBitrateThresholdProp.value.kbps.toInt()

        /**
         * The property that specifies the probability of enabling the
         * timeout experiment.
         */
        private val timeoutExperimentProbabilityProp = simple<Double>(
            readOnce = true,
            legacyName = "$LEGACY_BASE_NAME.timeoutExperimentProbability",
            newName = "jmt.bwe.send-side.timeout-experiment.probability"
        )

        @JvmStatic
        fun timeoutExperimentProbability(): Double = timeoutExperimentProbabilityProp.value
    }
}
