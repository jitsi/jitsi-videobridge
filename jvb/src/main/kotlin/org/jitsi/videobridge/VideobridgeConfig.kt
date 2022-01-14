/*
 * Copyright @ 2015 - Present, 8x8 Inc
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

package org.jitsi.videobridge

import org.jitsi.config.JitsiConfig
import org.jitsi.metaconfig.config
import java.time.Duration

class VideobridgeConfig {
    companion object {
        val gracefulShutdownDelay: Duration by config {
            "videobridge.graceful-shutdown-delay".from(JitsiConfig.newConfig)
        }
        val initialDrainMode: Boolean by config {
            "videobridge.initial-drain-mode".from(JitsiConfig.newConfig)
        }
    }
}
