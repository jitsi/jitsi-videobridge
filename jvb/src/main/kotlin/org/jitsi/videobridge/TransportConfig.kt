/*
 * Copyright @ 2018 - Present, 8x8 Inc
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
import org.jitsi.metaconfig.from

class TransportConfig {
    companion object {
        @JvmStatic
        val queueSize: Int by config("videobridge.transport.send.queue-size".from(JitsiConfig.newConfig))
    }
}

class LoudestConfig {
    companion object {
        @JvmStatic
        val numLoudest: Int by config(
            "videobridge.loudest.numLoudest".from(JitsiConfig.newConfig)
        )
        @JvmStatic
        val alwaysRouteDominant: Boolean by config(
            "videobridge.loudest.alwaysRouteDominant".from(JitsiConfig.newConfig)
        )
        @JvmStatic
        val energyExpireTimeMs: Int by config(
            "videobridge.loudest.energyExpireTimeMs".from(JitsiConfig.newConfig)
        )
        @JvmStatic
        val energyAlphaPct: Int by config(
            "videobridge.loudest.energyAlphaPct".from(JitsiConfig.newConfig)
        )
        @JvmStatic
        val energyDiscardAlarmThreshold: Int by config(
            "videobridge.loudest.energyDiscardAlarmThreshold".from(JitsiConfig.newConfig)
        )
    }
}
