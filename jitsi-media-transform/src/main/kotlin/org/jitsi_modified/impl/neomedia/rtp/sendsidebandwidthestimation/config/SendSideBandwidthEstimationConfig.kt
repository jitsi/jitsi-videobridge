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

import org.jitsi.config.JitsiConfig
import org.jitsi.metaconfig.config
import org.jitsi.metaconfig.from
import org.jitsi.nlj.util.Bandwidth

class SendSideBandwidthEstimationConfig {
    companion object {
        private val defaultLowLossThreshold: Double by
            config("jmt.bwe.send-side.low-loss-threshold".from(JitsiConfig.newConfig))

        /**
         * The low-loss threshold (expressed as a proportion of lost packets) when the loss probability
         * experiment is *not* active
         */
        @JvmStatic
        fun defaultLowLossThreshold() = defaultLowLossThreshold

        private val defaultHighLossThreshold: Double by
            config("jmt.bwe.send-side.high-loss-threshold".from(JitsiConfig.newConfig))

        /**
         * The high-loss threshold (expressed as a proportion of lost packets) when the loss probability
         * experiment is *not* active.
         */
        @JvmStatic
        fun defaultHighLossThreshold() = defaultHighLossThreshold

        private val defaultBitrateThresholdKbps: Int by config {
            "jmt.bwe.send-side.bitrate-threshold".from(JitsiConfig.newConfig)
                .convertFrom<String> { Bandwidth.fromString(it).kbps.toInt() }
        }
        /**
         * The bitrate threshold when the loss probability experiment is *not* active.
         */
        @JvmStatic
        fun defaultBitrateThresholdKbps() = defaultBitrateThresholdKbps

        private const val LEGACY_BASE_NAME =
            "org.jitsi.impl.neomedia.rtp.sendsidebandwidthestimation.SendSideBandwidthEstimation"

        private val lossExperimentProbability: Double by config {
            "$LEGACY_BASE_NAME.lossExperimentProbability".from(JitsiConfig.legacyConfig)
            "jmt.bwe.send-side.loss-experiment.probability".from(JitsiConfig.newConfig)
        }

        /**
         * The probability of enabling the loss-based experiment.
         */
        @JvmStatic
        fun lossExperimentProbability() = lossExperimentProbability

        private val experimentalLowLossThreshold: Double by config {
            "$LEGACY_BASE_NAME.lowLossThreshold".from(JitsiConfig.legacyConfig)
            "jmt.bwe.send-side.loss-experiment.low-loss-threshold".from(JitsiConfig.newConfig)
        }

        /**
         * The low-loss threshold (expressed as a proportion of lost packets) when the loss probability
         * experiment is active.
         */
        @JvmStatic
        fun experimentalLowLossThreshold() = experimentalLowLossThreshold

        private val experimentalHighLossThreshold: Double by config {
            "$LEGACY_BASE_NAME.highLossThreshold".from(JitsiConfig.legacyConfig)
            "jmt.bwe.send-side.loss-experiment.high-loss-threshold".from(JitsiConfig.newConfig)
        }

        /**
         * The high-loss threshold (expressed as a proportion of lost packets).
         */
        @JvmStatic
        fun experimentalHighLossThreshold() = experimentalHighLossThreshold

        private val experimentalBitrateThresholdKbps: Int by config {
            "$LEGACY_BASE_NAME.bitrateThresholdKbps".from(JitsiConfig.legacyConfig)
            "jmt.bwe.send-side.loss-experiment.bitrate-threshold".from(JitsiConfig.newConfig)
                .convertFrom<String> { Bandwidth.fromString(it).kbps.toInt() }
        }

        /**
         * The bitrate threshold when the loss probability experiment is active.
         */
        @JvmStatic
        fun experimentalBitrateThresholdKbps() = experimentalBitrateThresholdKbps

        private val timeoutExperimentProbability: Double by config {
            "$LEGACY_BASE_NAME.timeoutExperimentProbability".from(JitsiConfig.legacyConfig)
            "jmt.bwe.send-side.timeout-experiment.probability".from(JitsiConfig.newConfig)
        }

        /**
         * The probability of enabling the timeout experiment.
         */
        @JvmStatic
        fun timeoutExperimentProbability() = timeoutExperimentProbability
    }
}
