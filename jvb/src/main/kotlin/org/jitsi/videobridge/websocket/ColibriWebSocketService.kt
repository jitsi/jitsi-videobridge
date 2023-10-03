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

package org.jitsi.videobridge.websocket

import org.eclipse.jetty.servlet.ServletContextHandler
import org.eclipse.jetty.servlet.ServletHolder
import org.eclipse.jetty.websocket.server.config.JettyWebSocketServletContainerInitializer
import org.jitsi.utils.logging2.createLogger
import org.jitsi.videobridge.Videobridge
import org.jitsi.videobridge.relay.RelayConfig
import org.jitsi.videobridge.websocket.config.WebsocketServiceConfig.Companion.config

class ColibriWebSocketService(
    webserverIsTls: Boolean
) {
    private val baseUrls: List<String>
    private val relayBaseUrls: List<String>

    init {
        // We default to matching the protocol used by the local jetty
        // instance, but we allow for the configuration via properties
        // to override it since certain use-cases require it.
        if (config.enabled) {
            val useTls = config.useTls ?: webserverIsTls
            val protocol = if (useTls) "wss" else "ws"
            baseUrls = config.domains.map {
                "$protocol://$it/$COLIBRI_WS_ENDPOINT/${config.serverId}"
            }
            relayBaseUrls = if (RelayConfig.config.enabled) {
                config.relayDomains.map {
                    "$protocol://$it/$COLIBRI_RELAY_WS_ENDPOINT/${config.serverId}"
                }
            } else {
                emptyList()
            }
            if (baseUrls.isEmpty() || relayBaseUrls.isEmpty()) {
                logger.warn("Websockets enabled, but no domains specified: URLs=$baseUrls, Relay URLs=$relayBaseUrls")
            } else {
                logger.info("Base URL: $baseUrls Relay URL: $relayBaseUrls")
            }
        } else {
            logger.info("WebSockets are not enabled")
            baseUrls = emptyList()
            relayBaseUrls = emptyList()
        }
    }

    /**
     * Return a String representing the URL for an endpoint with ID [endpointId] in a conference with
     * ID [conferenceId] to use to connect to the websocket with password [pwd] or null if the
     * [ColibriWebSocketService] is not enabled.
     */
    fun getColibriWebSocketUrls(conferenceId: String, endpointId: String, pwd: String): List<String> {
        // "wss://example.com/colibri-ws/server-id/conf-id/endpoint-id?pwd=123
        return baseUrls.map { "$it/$conferenceId/$endpointId?pwd=$pwd" }
    }

    /**
     * Return a String representing the URL for a relay with ID [relayId] in a conference with
     * ID [conferenceId] to use to connect to the websocket with password [pwd] or null if the
     * [ColibriWebSocketService] is not enabled.
     */
    fun getColibriRelayWebSocketUrls(conferenceId: String, relayId: String, pwd: String): List<String> {
        // "wss://example.com/colibri-relay-ws/server-id/conf-id/relay-id?pwd=123
        return relayBaseUrls.map { "$it/$conferenceId/$relayId?pwd=$pwd" }
    }

    fun registerServlet(servletContextHandler: ServletContextHandler, videobridge: Videobridge) {
        if (config.enabled) {
            logger.info("Registering servlet with baseUrls = $baseUrls, relayBaseUrls = $relayBaseUrls")
            val holder = ServletHolder().apply {
                servlet = ColibriWebSocketServlet(config.serverId, videobridge)
            }
            servletContextHandler.addServlet(holder, "/$COLIBRI_WS_ENDPOINT/*")
            servletContextHandler.addServlet(holder, "/$COLIBRI_RELAY_WS_ENDPOINT/*")
            /* TODO, if you want to register additional Websocket servlets elsewhere:
                factor this out, it should only be called once. */
            JettyWebSocketServletContainerInitializer.configure(servletContextHandler, null)
        } else {
            logger.info("Disabled, not registering servlet")
        }
    }

    companion object {
        private val logger = createLogger()

        /**
         * The root path of the HTTP endpoint for COLIBRI WebSockets.
         */
        private const val COLIBRI_WS_ENDPOINT = "colibri-ws"

        /**
         * The root path of the HTTP endpoint for COLIBRI Relay-to-Relay (Octo) WebSockets.
         */
        private const val COLIBRI_RELAY_WS_ENDPOINT = "colibri-relay-ws"

        /**
         * Code elsewhere needs the value with the leading and trailing slashes, but when
         * building URLs above, it's more readable to have the slashes be part of the String
         * being built, so the separation is obvious.
         */
        const val COLIBRI_WS_PATH = "/$COLIBRI_WS_ENDPOINT/"

        /**
         * Similarly for relay
         */
        const val COLIBRI_RELAY_WS_PATH = "/$COLIBRI_RELAY_WS_ENDPOINT/"
    }
}
