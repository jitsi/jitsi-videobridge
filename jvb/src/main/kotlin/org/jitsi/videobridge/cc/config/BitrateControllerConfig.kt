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

class BitrateControllerConfig {
    companion object {
        /**
         * The bandwidth estimation threshold.
         *
         * In order to limit the resolution changes due to bandwidth changes we
         * react to bandwidth changes greater bweChangeThresholdPct / 100 of the
         * last bandwidth estimation.
         */
        private val bweChangeThresholdPct: Int by config {
            retrieve("org.jitsi.videobridge.BWE_CHANGE_THRESHOLD_PCT".from(JitsiConfig.legacyConfig))
            retrieve("videobridge.cc.bwe-change-threshold-pct".from(JitsiConfig.newConfig))
        }

        @JvmStatic
        fun bweChangeThresholdPct() = bweChangeThresholdPct

        /**
         * The max resolution to allocate for the thumbnails.
         */
        private val thumbnailMaxHeightPx: Int by config {
            retrieve("org.jitsi.videobridge.THUMBNAIL_MAX_HEIGHT".from(JitsiConfig.legacyConfig))
            retrieve("videobridge.cc.thumbnail-max-height-px".from(JitsiConfig.newConfig))
        }

        @JvmStatic
        fun thumbnailMaxHeightPx() = thumbnailMaxHeightPx

        /**
         * The default preferred resolution to allocate for the onstage participant,
         * before allocating bandwidth for the thumbnails.
         */
        private val onstagePreferredHeightPx: Int by config {
            retrieve("org.jitsi.videobridge.ONSTAGE_PREFERRED_HEIGHT".from(JitsiConfig.legacyConfig))
            retrieve("videobridge.cc.onstage-preferred-height-px".from(JitsiConfig.newConfig))
        }

        @JvmStatic
        fun onstagePreferredHeightPx() = onstagePreferredHeightPx

        /**
         * The preferred frame rate to allocate for the onstage participant.
         */
        private val onstagePreferredFramerate: Double by config {
            retrieve("org.jitsi.videobridge.ONSTAGE_PREFERRED_FRAME_RATE".from(JitsiConfig.legacyConfig))
            retrieve("videobridge.cc.onstage-preferred-framerate".from(JitsiConfig.newConfig))
        }

        @JvmStatic
        fun onstagePreferredFramerate() = onstagePreferredFramerate

        /**
         * Whether or not we're allowed to suspend the video of the
         * on-stage participant.
         */
        private val enableOnstageVideoSuspend: Boolean by config {
            retrieve("org.jitsi.videobridge.ENABLE_ONSTAGE_VIDEO_SUSPEND".from(JitsiConfig.legacyConfig))
            retrieve("videobridge.cc.enable-onstage-video-suspend".from(JitsiConfig.newConfig))
        }

        @JvmStatic
        fun enableOnstageVideoSuspend(): Boolean = enableOnstageVideoSuspend

        /**
         * Whether or not we should trust the bandwidth
         * estimations. If this is se to false, then we assume a bandwidth
         * estimation of Long.MAX_VALUE.
         */
        private val trustBwe: Boolean by config {
            retrieve("org.jitsi.videobridge.TRUST_BWE".from(JitsiConfig.legacyConfig))
            retrieve("videobridge.cc.trust-bwe".from(JitsiConfig.newConfig))
        }

        @JvmStatic
        fun trustBwe(): Boolean = trustBwe

        /**
         * The property for the max resolution to allocate for the onstage
         * participant.
         */
        private val onstageIdealHeightPx: Int by
            config("videobridge.cc.onstage-ideal-height-px".from(JitsiConfig.newConfig))

        @JvmStatic
        fun onstageIdealHeightPx() = onstageIdealHeightPx
    }
}
