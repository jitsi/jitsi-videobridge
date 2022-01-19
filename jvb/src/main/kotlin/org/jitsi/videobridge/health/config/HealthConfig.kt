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

package org.jitsi.videobridge.health.config

import org.jitsi.config.JitsiConfig
import org.jitsi.metaconfig.config
import org.jitsi.metaconfig.from
import java.time.Duration

class HealthConfig {
    val interval: Duration by config {
        "org.jitsi.videobridge.health.INTERVAL"
            .from(JitsiConfig.legacyConfig).convertFrom<Long>(Duration::ofMillis)
        "videobridge.health.interval".from(JitsiConfig.newConfig)
    }

    val timeout: Duration by config {
        "org.jitsi.videobridge.health.TIMEOUT"
            .from(JitsiConfig.legacyConfig).convertFrom<Long>(Duration::ofMillis)
        "videobridge.health.timeout".from(JitsiConfig.newConfig)
    }

    val maxCheckDuration: Duration by config("videobridge.health.max-check-duration".from(JitsiConfig.newConfig))

    val stickyFailures: Boolean by config {
        "org.jitsi.videobridge.health.STICKY_FAILURES".from(JitsiConfig.legacyConfig)
        "videobridge.health.sticky-failures".from(JitsiConfig.newConfig)
    }

    companion object {
        @JvmField
        val config = HealthConfig()
    }
}
