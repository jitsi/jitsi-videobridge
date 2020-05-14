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
import io.ktor.application.install
import io.ktor.features.CallLogging
import io.ktor.routing.route
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.jetty.Jetty
import org.jitsi.xmpp.extensions.colibri.ColibriConferenceIQ
import org.jitsi.xmpp.extensions.colibri.ColibriIQProvider
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.provider.ProviderManager
import org.slf4j.event.Level
import org.jitsi.videobridge.api.server.v1.app as v1App
import org.jitsi.videobridge.api.types.v1.ConferenceManager as v1ConferenceManager

/**
 * The top level JVB API application.  It's responsible for inserting all
 * the correct versions of the application at the correct URLs and injecting
 * the [ConferenceManager] instance for calls.
 */
@kotlin.jvm.JvmOverloads
fun Application.module(conferenceManager: v1ConferenceManager, testing: Boolean = false) {
    install(CallLogging) {
        level = Level.TRACE
    }
    routing {
        trace { println(it.buildText()) }
        route("/v1") {
            v1App(conferenceManager)
        }
    }
}

fun main() {
    ProviderManager.addIQProvider(
        ColibriConferenceIQ.ELEMENT_NAME,
        ColibriConferenceIQ.NAMESPACE,
        ColibriIQProvider())
    embeddedServer(Jetty, port = 9090) {
        module(object : v1ConferenceManager {
            override fun handleColibriConferenceIQ(conferenceIQ: ColibriConferenceIQ): IQ {
                return ColibriConferenceIQ()
            }
        })
    }.start()
}
