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

package org.jitsi.videobridge.api.server

import io.ktor.application.Application
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.features.ContentNegotiation
import io.ktor.jackson.jackson
import io.ktor.response.respond
import io.ktor.routing.get
import io.ktor.routing.route
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.jetty.Jetty
import io.ktor.websocket.WebSockets
import org.jitsi.xmpp.extensions.colibri.ColibriConferenceIQ
import org.jitsi.xmpp.extensions.colibri.ColibriIQProvider
import org.jitsi.xmpp.extensions.health.HealthCheckIQ
import org.jitsi.xmpp.extensions.health.HealthCheckIQProvider
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.provider.ProviderManager
import org.jitsi.videobridge.api.server.v1.app as v1App
import org.jitsi.videobridge.api.types.v1.ConferenceManager as v1ConferenceManager

/**
 * The top level JVB API application.  It's responsible for inserting all
 * the correct versions of the application at the correct URLs and injecting
 * the [v1ConferenceManager] instance for calls.
 */
fun Application.module(conferenceManager: v1ConferenceManager) {
    install(ContentNegotiation) {
        jackson {}
    }
    install(WebSockets)
    routing {
//        trace { println(it.buildText()) }
        route("/v1") {
            v1App(conferenceManager)
        }
        get("/about/api_version") {
            // This line should be updated to reflect all versions
            // supported by this server
            call.respond(ApiVersion("v1"))
        }
    }
}

@Suppress("unused")
class ApiVersion(val supportedVersions: List<String>) {
    constructor(vararg versions: String) : this(versions.toList())
}

fun main() {
    ProviderManager.addIQProvider(
        ColibriConferenceIQ.ELEMENT_NAME,
        ColibriConferenceIQ.NAMESPACE,
        ColibriIQProvider())
    ProviderManager.addIQProvider(
        HealthCheckIQ.ELEMENT_NAME,
        HealthCheckIQ.NAMESPACE,
        HealthCheckIQProvider())
    embeddedServer(Jetty, port = 9099) {
        module(object : v1ConferenceManager {
            override fun handleColibriConferenceIQ(conferenceIQ: ColibriConferenceIQ): IQ {
                return ColibriConferenceIQ()
            }

            override fun handleHealthIq(healthCheckIQ: HealthCheckIQ): IQ {
                return IQ.createResultIQ(healthCheckIQ)
            }
        })
    }.start()
}
