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

package org.jitsi.videobridge.relay

import org.jitsi.config.JitsiConfig
import org.jitsi.metaconfig.config
import org.jitsi.metaconfig.optionalconfig

class RelayConfig private constructor() {
    val enabled: Boolean by config {
        "videobridge.octo.enabled".from(JitsiConfig.newConfig)
        "videobridge.relay.enabled".from(JitsiConfig.newConfig)
    }

    val region: String? by optionalconfig {
        "org.jitsi.videobridge.REGION".from(JitsiConfig.legacyConfig)
        "videobridge.octo.region".from(JitsiConfig.newConfig)
        "videobridge.relay.region".from(JitsiConfig.newConfig)
    }

    val relayId: String by config {
        "videobridge.octo.relay-id".from(JitsiConfig.newConfig)
        "videobridge.relay.relay-id".from(JitsiConfig.newConfig)
        // Fallback to the legacy "bind-address" property.
        "videobridge.octo.bind-address".from(JitsiConfig.newConfig)
    }

    companion object {
        @JvmField
        val config = RelayConfig()
    }
}
