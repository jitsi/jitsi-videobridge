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
package org.jitsi.nlj.rtcp

import org.jitsi.config.JitsiConfig
import org.jitsi.metaconfig.config
import java.time.Duration

/**
 * Configuration for bounding the source-wide keyframe request rate by the measured cost of a keyframe to the source.
 * Shared by [KeyframeRequester], which applies the bound, and the receive side, which measures the cost, so that
 * no measurement is done when the bound is not in use.
 */
object KeyframeBudgetConfig {
    /** Whether the source-wide keyframe request interval is bounded by the measured cost of a keyframe. */
    val enabled: Boolean by config {
        "jmt.keyframe.budget.enabled".from(JitsiConfig.newConfig)
    }

    /** The fraction of a source's bitrate that keyframes requested at the source-wide rate may cost. */
    val maxBitrateFraction: Double by config {
        "jmt.keyframe.budget.max-bitrate-fraction".from(JitsiConfig.newConfig)
    }

    /** The longest source-wide interval the bound may produce, unless source-wide-min-interval itself is longer. */
    val maxInterval: Duration by config {
        "jmt.keyframe.budget.max-interval".from(JitsiConfig.newConfig)
    }
}
