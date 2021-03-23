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

package org.jitsi.videobridge.websocket.config

import org.jitsi.config.JitsiConfig
import org.jitsi.metaconfig.config
import org.jitsi.metaconfig.optionalconfig

class WebsocketServiceConfig {
    /**
     * Whether [org.jitsi.videobridge.websocket.ColibriWebSocketService] is enabled
     */
    val enabled: Boolean by config {
        // The old property is named 'disable', while the new one
        // is 'enable', so invert the old value
        "org.jitsi.videobridge.rest.COLIBRI_WS_DISABLE"
            .from(JitsiConfig.legacyConfig).transformedBy { !it }
        "videobridge.websockets.enabled".from(JitsiConfig.newConfig)
    }

    /**
     * The domain name used in URLs advertised for COLIBRI WebSockets.
     */
    val domain: String by config {
        onlyIf("Websockets are enabled", ::enabled) {
            "org.jitsi.videobridge.rest.COLIBRI_WS_DOMAIN".from(JitsiConfig.legacyConfig)
            "videobridge.websockets.domain".from(JitsiConfig.newConfig)
        }
    }

    /**
     * Whether the "wss" or "ws" protocol should be used for websockets
     */
    val useTls: Boolean? by optionalconfig {
        onlyIf("Websockets are enabled", ::enabled) {
            "org.jitsi.videobridge.rest.COLIBRI_WS_TLS".from(JitsiConfig.legacyConfig)
            "videobridge.websockets.tls".from(JitsiConfig.newConfig)
        }
    }

    /**
     * Whether compression (permessage-deflate) should be used for websockets
     */
    private val enableCompression: Boolean by config {
        onlyIf("Websockets are enabled", ::enabled) {
            "videobridge.websockets.enable-compression".from(JitsiConfig.newConfig)
        }
    }

    fun shouldEnableCompression() = enableCompression

    /**
     * The server ID used in URLs advertised for COLIBRI WebSockets.
     */
    val serverId: String by config {
        onlyIf("Websockets are enabled", ::enabled) {
            "org.jitsi.videobridge.rest.COLIBRI_WS_SERVER_ID".from(JitsiConfig.legacyConfig)
            "videobridge.websockets.server-id".from(JitsiConfig.newConfig)
        }
    }
}
