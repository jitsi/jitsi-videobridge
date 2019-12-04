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

import org.jitsi.config.simple

class BitrateControllerConfig {
    companion object {
        /**
         * The property that holds the bandwidth estimation threshold.
         *
         * In order to limit the resolution changes due to bandwidth changes we
         * react to bandwidth changes greater bweChangeThresholdPct / 100 of the
         * last bandwidth estimation.
         */
        private val bweChangeThresholdPctProp = simple<Int>(
            readOnce = true,
            legacyName = "org.jitsi.videobridge.BWE_CHANGE_THRESHOLD_PCT",
            newName = "videobridge.cc.bwe-change-threshold-pct"
        )

        @JvmStatic
        fun bweChangeThresholdPct() = bweChangeThresholdPctProp.value

        /**
         * The property for the max resolution to allocate for the thumbnails.
         */
        private val thumbnailMaxHeightPxProp = simple<Int>(
            readOnce = true,
            legacyName = "org.jitsi.videobridge.THUMBNAIL_MAX_HEIGHT",
            newName = "videobridge.cc.thumbnail-max-height-px"
        )

        @JvmStatic
        fun thumbnailMaxHeightPx() = thumbnailMaxHeightPxProp.value

        /**
         * The default preferred resolution to allocate for the onstage participant,
         * before allocating bandwidth for the thumbnails.
         */
        private val onstagePreferredHeightPxProp = simple<Int>(
            readOnce = true,
            legacyName= "org.jitsi.videobridge.ONSTAGE_PREFERRED_HEIGHT",
            newName = "videobridge.cc.onstage-preferred-height-px"
        )

        @JvmStatic
        fun onstagePreferredHeightPx() = onstagePreferredHeightPxProp.value

        /**
         * The preferred frame rate to allocate for the onstage participant.
         */
        private val onstagePreferredFramerate = simple<Double>(
            readOnce = true,
            legacyName= "org.jitsi.videobridge.ONSTAGE_PREFERRED_FRAME_RATE",
            newName = "videobridge.cc.onstage-preferred-framerate"
        )

        @JvmStatic
        fun onstagePreferredFramerate() = onstagePreferredFramerate.value

        /**
         * Whether or not we're allowed to suspend the video of the
         * on-stage participant.
         */
        private val enableOnstageVideoSuspendProp = simple<Boolean>(
            readOnce = true,
            legacyName = "org.jitsi.videobridge.ENABLE_ONSTAGE_VIDEO_SUSPEND",
            newName = "videobridge.cc.enable-onstage-video-suspend"
        )

        @JvmStatic
        fun enableOnstageVideoSuspend() = enableOnstageVideoSuspendProp.value

        /**
         * Whether or not we should trust the bandwidth
         * estimations. If this is se to false, then we assume a bandwidth
         * estimation of Long.MAX_VALUE.
         */
        private val trustBweProp = simple<Boolean>(
            readOnce = true,
            legacyName= "org.jitsi.videobridge.TRUST_BWE",
            newName = "videobridge.cc.trust-bwe"
        )

        @JvmStatic
        fun trustBwe() = trustBweProp.value
    }
}