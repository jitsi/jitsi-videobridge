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

package org.jitsi.videobridge.rest.config

import org.jitsi.config.legacyProperty
import org.jitsi.config.newProperty
import org.jitsi.config.simple
import org.jitsi.utils.config.dsl.multiProperty

class WebsocketServiceConfig {
    companion object {
        /**
         * The name of the property which enables the
         * [org.jitsi.videobridge.rest.ColibriWebSocketService]
         */
        private val enabled = multiProperty<Boolean> {
            legacyProperty {
                name("org.jitsi.videobridge.rest.COLIBRI_WS_DISABLE")
                readOnce()
                // The old property is named 'disable', while the new one
                // is 'enable', so invert the old value
                transformedBy { !it }
            }
            newProperty {
                name("videobridge.websockets.enabled")
                readOnce()
            }
        }

        @JvmStatic
        fun isEnabled() = enabled.value

        /**
         * The name of the property which controls the domain name used in URLs
         * advertised for COLIBRI WebSockets.
         */
        private val domain = simple<String>(
            readOnce = true,
            legacyName = "org.jitsi.videobridge.rest.COLIBRI_WS_DOMAIN",
            newName = "videobridge.websockets.domain"
        )

        @JvmStatic
        fun getDomain() = domain.value

        /**
         * The name of the property which controls whether URLs advertised for
         * COLIBRI WebSockets should use the "ws" (if false) or "wss" (if true)
         * schema.
         */
        private val tls = simple<Boolean>(
            readOnce = true,
            legacyName = "org.jitsi.videobridge.rest.COLIBRI_WS_TLS",
            newName = "videobridge.websockets.tls"
        )

        @JvmStatic
        fun useTls() = tls.value

        /**
         * The name of the property which controls the server ID used in URLs
         * advertised for COLIBRI WebSockets.
         */
        private val serverId = simple<String>(
            readOnce = true,
            legacyName = "org.jitsi.videobridge.rest.COLIBRI_WS_SERVER_ID",
            newName = "videobridge.websockets.server-id"
        )

        @JvmStatic
        fun getServerId() = serverId.value
    }
}
