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

package org.jitsi.nlj.rtp.bandwidthestimation2

import org.jitsi.nlj.util.Bandwidth
import org.jitsi.nlj.util.bps
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging2.Logger

/** Delay-based bandwidth estimation,
 * based on WebRTC modules/congestion_controller/goog_cc/delay_based_bwe.{h,cc} in
 * WebRTC 8284f2b4e8670529d039a8b6c73ec5f1d760bd21.
 *
 * Field trial settings have been generally removed, set to their default settings, and APIs that aren't
 * used by Chrome have also been removed.
 */
class DelayBasedBwe(
    parentLogger: Logger,
    private val diagnosticContext: DiagnosticContext
) {
    data class Result(
        val updated: Boolean = false,
        val probe: Boolean = false,
        val target_bitrate: Bandwidth = 0.bps,
        val recovered_from_overuse: Boolean = false,
        val delay_detector_state: BandwidthUsage = BandwidthUsage.kBwNormal
    )
}
