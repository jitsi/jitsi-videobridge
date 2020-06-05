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

import org.jitsi.config.LegacyFallbackConfigProperty
import org.jitsi.config.newConfigAttributes
import org.jitsi.utils.config.SimpleProperty

class BitrateControllerConfig {
    class Config {
        companion object {
            /**
             * The property that holds the bandwidth estimation threshold.
             *
             * In order to limit the resolution changes due to bandwidth changes we
             * react to bandwidth changes greater bweChangeThresholdPct / 100 of the
             * last bandwidth estimation.
             */
            class BweChangeThresholdPercentProperty : LegacyFallbackConfigProperty<Int>(
                Int::class,
                readOnce = true,
                legacyName = "org.jitsi.videobridge.BWE_CHANGE_THRESHOLD_PCT",
                newName = "videobridge.cc.bwe-change-threshold-pct"
            )
            private val bweChangeThresholdPctProp = BweChangeThresholdPercentProperty()

            @JvmStatic
            fun bweChangeThresholdPct() = bweChangeThresholdPctProp.value

            /**
             * The property for the max resolution to allocate for the thumbnails.
             */
            class ThumbnailMaxHeightPixelsProperty : LegacyFallbackConfigProperty<Int>(
                Int::class,
                readOnce = true,
                legacyName = "org.jitsi.videobridge.THUMBNAIL_MAX_HEIGHT",
                newName = "videobridge.cc.thumbnail-max-height-px"
            )

            private val thumbnailMaxHeightPxProp = ThumbnailMaxHeightPixelsProperty()

            @JvmStatic
            fun thumbnailMaxHeightPx() = thumbnailMaxHeightPxProp.value

            /**
             * The default preferred resolution to allocate for the onstage participant,
             * before allocating bandwidth for the thumbnails.
             */
            class OnstagePreferredHeightPixelsProperty : LegacyFallbackConfigProperty<Int>(
                Int::class,
                readOnce = true,
                legacyName = "org.jitsi.videobridge.ONSTAGE_PREFERRED_HEIGHT",
                newName = "videobridge.cc.onstage-preferred-height-px"
            )
            private val onstagePreferredHeightPxProp = OnstagePreferredHeightPixelsProperty()

            @JvmStatic
            fun onstagePreferredHeightPx() = onstagePreferredHeightPxProp.value

            /**
             * The preferred frame rate to allocate for the onstage participant.
             */
            class OnstagePreferredFramerateProperty : LegacyFallbackConfigProperty<Double>(
                Double::class,
                readOnce = true,
                legacyName = "org.jitsi.videobridge.ONSTAGE_PREFERRED_FRAME_RATE",
                newName = "videobridge.cc.onstage-preferred-framerate"
            )
            private val onstagePreferredFramerate = OnstagePreferredFramerateProperty()

            @JvmStatic
            fun onstagePreferredFramerate() = onstagePreferredFramerate.value

            /**
             * Whether or not we're allowed to suspend the video of the
             * on-stage participant.
             */
            class EnableOnstageVideoSuspendProperty : LegacyFallbackConfigProperty<Boolean>(
                Boolean::class,
                readOnce = true,
                legacyName = "org.jitsi.videobridge.ENABLE_ONSTAGE_VIDEO_SUSPEND",
                newName = "videobridge.cc.enable-onstage-video-suspend"
            )
            private val enableOnstageVideoSuspendProp = EnableOnstageVideoSuspendProperty()

            @JvmStatic
            fun enableOnstageVideoSuspend() = enableOnstageVideoSuspendProp.value

            /**
             * Whether or not we should trust the bandwidth
             * estimations. If this is se to false, then we assume a bandwidth
             * estimation of Long.MAX_VALUE.
             */
            class TrustBweProperty : LegacyFallbackConfigProperty<Boolean>(
                Boolean::class,
                readOnce = true,
                legacyName = "org.jitsi.videobridge.TRUST_BWE",
                newName = "videobridge.cc.trust-bwe"
            )

            private val trustBweProp = TrustBweProperty()

            @JvmStatic
            fun trustBwe() = trustBweProp.value

            /**
             * The property for the max resolution to allocate for the onstage
             * participant.
             */
            class OnstageIdealHeightPixelsProperty : SimpleProperty<Int>(
                    newConfigAttributes {
                        readOnce()
                        name("videobridge.cc.onstage-ideal-height-px")
                    }
            )

            private val onstageIdealHeightPxProp = OnstageIdealHeightPixelsProperty()

            @JvmStatic
            fun onstageIdealHeightPx(): Int {
                return onstageIdealHeightPxProp.value
            }
        }
    }
}
