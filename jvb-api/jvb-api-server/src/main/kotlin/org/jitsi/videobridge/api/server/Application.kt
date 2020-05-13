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
import io.ktor.application.ApplicationCall
import io.ktor.application.ApplicationCallPipeline
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.features.CallLogging
import io.ktor.routing.Routing
import io.ktor.routing.route
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.jetty.Jetty
import io.ktor.util.AttributeKey
import org.jitsi.videobridge.api.types.v1.ConferenceManager
import org.jitsi.xmpp.extensions.colibri.ColibriConferenceIQ
import org.jitsi.xmpp.extensions.colibri.ColibriIQProvider
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.provider.ProviderManager
import org.slf4j.event.Level
import org.jitsi.videobridge.api.server.v1.app as v1App

/**
 * The top level JVB API application.  It's responsible for inserting all
 * the correct versions of the application at the correct URLs and injecting
 * the [ConferenceManager] instance for calls.
 */
@kotlin.jvm.JvmOverloads
fun Application.module(conferenceManager: ConferenceManager, testing: Boolean = false) {
    install(CallLogging) {
        level = Level.TRACE
    }

    routing {
        trace { println(it.buildText()) }
        // TODO: ConferenceManager needs to also be versioned, where to inject it? How to inject all the versions?
        injectConfManager(conferenceManager)
        route("/v1") {
            v1App()
        }
    }
}

/**
 * Inject the instance of [ConferenceManager] into the call attributes so that it's
 * available when handling requests
 */
fun Routing.injectConfManager(conferenceManager: ConferenceManager) = intercept(ApplicationCallPipeline.Features) {
    call.attributes.put(CONF_MGR_ATTR_KEY, conferenceManager)
}

private val CONF_MGR_ATTR_KEY = AttributeKey<ConferenceManager>("__conf_mgr")

/**
 * Retrieve the [ConferenceManager] instance from the call attributes
 */
val ApplicationCall.confManager: ConferenceManager
    get() = this.attributes[CONF_MGR_ATTR_KEY]

fun main() {
    ProviderManager.addIQProvider(
        ColibriConferenceIQ.ELEMENT_NAME,
        ColibriConferenceIQ.NAMESPACE,
        ColibriIQProvider())
    embeddedServer(Jetty, port = 9090) {
        module(object : ConferenceManager {
            override fun handleColibriConferenceIQ(conferenceIQ: ColibriConferenceIQ): IQ {
                return ColibriConferenceIQ()
            }
        })
    }.start()
}
