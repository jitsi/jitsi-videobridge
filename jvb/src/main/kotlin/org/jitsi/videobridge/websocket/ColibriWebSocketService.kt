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
import org.jitsi.utils.logging2.createLogger
import org.jitsi.videobridge.Videobridge
import org.jitsi.videobridge.websocket.config.WebsocketServiceConfig

class ColibriWebSocketService(
    webserverIsTls: Boolean
) {
    private val config = WebsocketServiceConfig()

    private val baseUrl: String?
    private val relayUrl: String? /* TODO: only enable this if secure octo is enabled? */

    init {
        // We default to matching the protocol used by the local jetty
        // instance, but we allow for the configuration via properties
        // to override it since certain use-cases require it.
        if (config.enabled) {
            val useTls = config.useTls ?: webserverIsTls
            val protocol = if (useTls) "wss" else "ws"
            baseUrl = "$protocol://${config.domain}/$COLIBRI_WS_ENDPOINT/${config.serverId}"
            relayUrl = "$protocol://${config.domain}/$COLIBRI_RELAY_WS_ENDPOINT/${config.serverId}"
            logger.info("Base URL: $baseUrl Relay URL: $relayUrl")
        } else {
            logger.info("WebSockets are not enabled")
            baseUrl = null
            relayUrl = null
        }
    }

    /**
     * Return a String representing the URL for an endpoint with ID [endpointId] in a conference with
     * ID [conferenceId] to use to connect to the websocket with password [pwd] or null if the
     * [ColibriWebSocketService] is not enabled.
     */
    fun getColibriWebSocketUrl(conferenceId: String, endpointId: String, pwd: String): String? {
        if (!config.enabled) {
            return null
        }
        // "wss://example.com/colibri-ws/server-id/conf-id/endpoint-id?pwd=123
        return "$baseUrl/$conferenceId/$endpointId?pwd=$pwd"
    }

    /**
     * Return a String representing the URL for a relay with ID [relayId] in a conference with
     * ID [conferenceId] to use to connect to the websocket with password [pwd] or null if the
     * [ColibriWebSocketService] is not enabled.
     */
    fun getColibriRelayWebSocketUrl(conferenceId: String, relayId: String, pwd: String): String? {
        if (!config.enabled) {
            return null
        }
        // "wss://example.com/colibri-ws/server-id/conf-id/endpoint-id?pwd=123
        return "$relayUrl/$conferenceId/$relayId?pwd=$pwd"
    }

    fun registerServlet(
        servletContextHandler: ServletContextHandler,
        videobridge: Videobridge
    ) {
        if (config.enabled) {
            logger.info("Registering servlet at /$COLIBRI_WS_ENDPOINT/*, baseUrl = $baseUrl")
            val holder = ServletHolder().apply {
                servlet = ColibriWebSocketServlet(config.serverId, videobridge)
            }
            servletContextHandler.addServlet(holder, "/$COLIBRI_WS_ENDPOINT/*")
            logger.info("Registering servlet at /$COLIBRI_RELAY_WS_ENDPOINT/*, relayUrl = $relayUrl")
            servletContextHandler.addServlet(holder, "/$COLIBRI_RELAY_WS_ENDPOINT/*")
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
