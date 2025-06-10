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

package org.jitsi.videobridge.cc.config

import org.jitsi.config.JitsiConfig
import org.jitsi.metaconfig.config
import org.jitsi.metaconfig.from
import org.jitsi.metaconfig.optionalconfig
import org.jitsi.nlj.rtp.bandwidthestimation.BandwidthEstimatorConfig
import org.jitsi.nlj.rtp.bandwidthestimation.BandwidthEstimatorEngine
import org.jitsi.nlj.rtp.bandwidthestimation.GoogleCcEstimator
import org.jitsi.nlj.rtp.bandwidthestimation2.GoogCcTransportCcEngine
import org.jitsi.nlj.util.Bandwidth
import java.time.Duration

class BitrateControllerConfig private constructor() {
    /**
     * The bandwidth estimation threshold.
     *
     * In order to limit the resolution changes due to bandwidth changes we only react to bandwidth changes greater
     * than {@code bweChangeThreshold * last_bandwidth_estimation}.
     */
    val bweChangeThreshold: Double by config {
        "org.jitsi.videobridge.BWE_CHANGE_THRESHOLD_PCT".from(JitsiConfig.legacyConfig)
            .transformedBy { it / 100.0 }
        // This is an old version, include for backward compat.
        "videobridge.cc.bwe-change-threshold-pct".from(JitsiConfig.newConfig)
            .transformedBy { it / 100.0 }
        "videobridge.cc.bwe-change-threshold".from(JitsiConfig.newConfig)
    }

    val initialMaxHeightPx: Int by config {
        // Support the old property name if the user has overridden it.
        "videobridge.cc.thumbnail-max-height-px".from(JitsiConfig.newConfig)
            .softDeprecated("use videobridge.cc.initial-max-height-px")
        "videobridge.cc.initial-max-height-px".from(JitsiConfig.newConfig)
    }
    val defaultMaxHeightPx: Int by config {
        // Support the old property name if the user has overridden it.
        "videobridge.cc.thumbnail-max-height-px".from(JitsiConfig.newConfig)
            .softDeprecated("use videobridge.cc.default-max-height-px")
        "videobridge.cc.default-max-height-px".from(JitsiConfig.newConfig)
    }

    /**
     * The default preferred resolution to allocate for the onstage participant,
     * before allocating bandwidth for the thumbnails.
     */
    val onstagePreferredHeightPx: Int by config {
        "org.jitsi.videobridge.ONSTAGE_PREFERRED_HEIGHT".from(JitsiConfig.legacyConfig)
        "videobridge.cc.onstage-preferred-height-px".from(JitsiConfig.newConfig)
    }

    /**
     * The preferred frame rate to allocate for the onstage participant.
     */
    val onstagePreferredFramerate: Double by config {
        "org.jitsi.videobridge.ONSTAGE_PREFERRED_FRAME_RATE".from(JitsiConfig.legacyConfig)
        "videobridge.cc.onstage-preferred-framerate".from(JitsiConfig.newConfig)
    }

    /**
     * Whether or not we are allowed to oversend (exceed available bandwidth) for the video of the on-stage
     * participant.
     */
    val allowOversendOnStage: Boolean by config {
        "org.jitsi.videobridge.ENABLE_ONSTAGE_VIDEO_SUSPEND".from(JitsiConfig.legacyConfig).transformedBy { !it }
        "videobridge.cc.enable-onstage-video-suspend".from(JitsiConfig.newConfig).transformedBy { !it }
        "videobridge.cc.allow-oversend-onstage".from(JitsiConfig.newConfig)
    }

    /**
     * The maximum bitrate by which the bridge may exceed the estimated available bandwidth when oversending.
     */
    val maxOversendBitrate: Bandwidth by config {
        "videobridge.cc.max-oversend-bitrate".from(JitsiConfig.newConfig)
            .convertFrom<String> { Bandwidth.fromString(it) }
    }

    /**
     * Whether or not we should trust the bandwidth
     * estimations. If this is se to false, then we assume a bandwidth
     * estimation of Long.MAX_VALUE.
     */
    val trustBwe: Boolean by config {
        "org.jitsi.videobridge.TRUST_BWE".from(JitsiConfig.legacyConfig)
        "videobridge.cc.trust-bwe".from(JitsiConfig.newConfig)
    }

    /**
     * The maximum amount of time we'll run before recalculating which streams we'll
     * forward.
     */
    val maxTimeBetweenCalculations: Duration by config(
        "videobridge.cc.max-time-between-calculations".from(JitsiConfig.newConfig)
    )

    val assumedBandwidthLimit: Bandwidth? by optionalconfig {
        "videobridge.cc.assumed-bandwidth-limit".from(JitsiConfig.newConfig)
            .convertFrom<String> { Bandwidth.fromString(it) }
    }

    val useVlaTargetBitrate: Boolean by config {
        "videobridge.cc.use-vla-target-bitrate".from(JitsiConfig.newConfig)
    }

    private val _initialIgnoreBwePeriod: Duration? by optionalconfig(
        "videobridge.cc.initial-ignore-bwe-period".from(JitsiConfig.newConfig)
    )

    /** The default initial ignore BWE period if not set in jvb.conf, based on the BWE algorithm in use.*/
    private fun defaultInitialIgnoreBwePeriod(): Duration {
        return when (BandwidthEstimatorConfig.engine) {
            BandwidthEstimatorEngine.GoogleCc -> GoogleCcEstimator.defaultInitialIgnoreBwePeriod
            BandwidthEstimatorEngine.GoogleCc2 -> GoogCcTransportCcEngine.defaultInitialIgnoreBwePeriod
        }
    }

    /**
     * How long after first media to ignore bandwidth estimation.  Needed for BWE
     * algorithms that ramp up slowly; should be set to zero if this isn't a problem.
     */
    val initialIgnoreBwePeriod: Duration
        get() = _initialIgnoreBwePeriod ?: defaultInitialIgnoreBwePeriod()

    companion object {
        @JvmField
        val config = BitrateControllerConfig()
    }
}
