/*
 * Copyright @ 2026 - present 8x8, Inc.
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

package org.jitsi.nlj

import org.jitsi.config.JitsiConfig
import org.jitsi.metaconfig.config
import java.time.Duration

/**
 * How long an encoding of a video source may go without a media packet before it is no longer considered to be
 * sent, see [org.jitsi.nlj.EncodingLivenessTracker.isLive].
 */
object EncodingLivenessConfig {
    /** The timeout for a camera source, whose encoder produces frames at a steady rate. */
    val cameraTimeout: Duration by config {
        "jmt.rtp.encoding-liveness.camera-timeout".from(JitsiConfig.newConfig)
    }

    /** The timeout for a screen sharing source, whose encoder may pause for seconds when the content is static. */
    val desktopTimeout: Duration by config {
        "jmt.rtp.encoding-liveness.desktop-timeout".from(JitsiConfig.newConfig)
    }
}
