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
import java.time.Duration

class WebsocketServiceConfig private constructor() {
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

    private val domainProp: String? by optionalconfig {
        "org.jitsi.videobridge.rest.COLIBRI_WS_DOMAIN".from(JitsiConfig.legacyConfig)
        "videobridge.websockets.domain".from(JitsiConfig.newConfig)
    }

    private val domainsProp: List<String> by config {
        "videobridge.websockets.domains".from(JitsiConfig.newConfig)
    }

    /**
     * The list of domains to advertise (advertise a separate URL for each domain).
     * Constructed at get() time to allow underlying config changes.
     */
    val domains: List<String>
        get() = domainsProp.toMutableList().apply {
            domainProp?.let {
                if (!contains(it)) {
                    add(0, it)
                }
            }
        }

    private val relayDomainProp: String? by optionalconfig {
        "videobridge.websockets.relay-domain".from(JitsiConfig.newConfig)
    }

    private val relayDomainsProp: List<String> by config {
        "videobridge.websockets.relay-domains".from(JitsiConfig.newConfig)
    }

    /**
     * The list of domains to advertise (advertise a separate URL for each domain) for relays.
     * Constructed at get() time to allow underlying config changes.
     */
    val relayDomains: List<String>
        get() {
            val relayDomainProp = relayDomainProp
            return if (relayDomainProp != null) {
                relayDomainsProp.toMutableList().apply {
                    if (!contains(relayDomainProp)) {
                        add(0, relayDomainProp)
                    }
                }
            } else if (relayDomainsProp.isNotEmpty()) {
                relayDomainsProp.toList()
            } else {
                domains
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

    /** Whether keepalive pings are enabled */
    val sendKeepalivePings: Boolean by config {
        "videobridge.websockets.send-keepalive-pings".from(JitsiConfig.newConfig)
    }

    /** The time interval for keepalive pings */
    val keepalivePingInterval: Duration by config {
        "videobridge.websockets.keepalive-ping-interval".from(JitsiConfig.newConfig)
    }

    /** The time interval for websocket timeouts */
    val idleTimeout: Duration by config {
        "videobridge.websockets.idle-timeout".from(JitsiConfig.newConfig)
    }

    companion object {
        @JvmField
        val config = WebsocketServiceConfig()
    }
}
