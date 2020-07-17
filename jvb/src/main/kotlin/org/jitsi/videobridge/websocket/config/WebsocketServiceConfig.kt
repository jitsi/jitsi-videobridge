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

import org.jitsi.config.NewJitsiConfig
import org.jitsi.metaconfig.config
import org.jitsi.metaconfig.optionalconfig

class WebsocketServiceConfig {
    /**
     * Whether [org.jitsi.videobridge.websocket.ColibriWebSocketService] is enabled
     */
    val enabled: Boolean by config {
        // The old property is named 'disable', while the new one
        // is 'enable', so invert the old value
        retrieve("org.jitsi.videobridge.rest.COLIBRI_WS_DISABLE"
            .from(NewJitsiConfig.legacyConfig).andTransformBy { !it })
        retrieve("videobridge.websockets.enabled".from(NewJitsiConfig.newConfig))
    }

    /**
     * The domain name used in URLs advertised for COLIBRI WebSockets.
     */
    val domain: String by config {
        onlyIf("Websocket are enabled", ::enabled) {
            retrieve("org.jitsi.videobridge.rest.COLIBRI_WS_DOMAIN".from(NewJitsiConfig.legacyConfig))
            retrieve("videobridge.websockets.domain".from(NewJitsiConfig.newConfig))
        }
    }

    /**
     * Whether the "wss" or "ws" protocol should be used for websockets
     */
    val useTls: Boolean? by optionalconfig {
        onlyIf("Websocket are enabled", ::enabled) {
            retrieve("org.jitsi.videobridge.rest.COLIBRI_WS_TLS".from(NewJitsiConfig.legacyConfig))
            retrieve("videobridge.websockets.tls".from(NewJitsiConfig.newConfig))
        }
    }

    /**
     * The server ID used in URLs advertised for COLIBRI WebSockets.
     */
    val serverId: String by config {
        onlyIf("Websocket are enabled", ::enabled) {
            retrieve("org.jitsi.videobridge.rest.COLIBRI_WS_SERVER_ID".from(NewJitsiConfig.legacyConfig))
            retrieve("videobridge.websockets.server-id".from(NewJitsiConfig.newConfig))
        }
    }
}
