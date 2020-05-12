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

package org.jitsi.videobridge.signaling.api

import com.fasterxml.jackson.databind.SerializationFeature
import io.ktor.application.Application
import io.ktor.application.ApplicationCall
import io.ktor.application.ApplicationCallPipeline
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.features.CallLogging
import io.ktor.features.ContentNegotiation
import io.ktor.jackson.jackson
import io.ktor.routing.Routing
import io.ktor.routing.route
import io.ktor.routing.routing
import io.ktor.util.AttributeKey
import org.jitsi.videobridge.Videobridge
import org.jitsi.videobridge.signaling.api.v1.apiVersionApi
import org.jitsi.videobridge.signaling.api.v1.conferencesApi
import org.slf4j.event.Level

@kotlin.jvm.JvmOverloads
fun Application.module(videobridge: Videobridge, testing: Boolean = false) {
    install(ContentNegotiation) {
        jackson {
            enable(SerializationFeature.INDENT_OUTPUT)
        }
    }
    install(CallLogging) {
        level = Level.TRACE
    }

    routing {
        trace { println(it.buildText()) }
        // We always inject the videobridge instance
        injectVideobridge(videobridge)
        route("/v1") {
            conferencesApi()
            apiVersionApi()
        }
    }
}

fun Routing.injectVideobridge(videobridge: Videobridge) = intercept(ApplicationCallPipeline.Features) {
    call.attributes.put(VIDEOBRIDGE_ATTR_KEY, videobridge)
}

val VIDEOBRIDGE_ATTR_KEY = AttributeKey<Videobridge>("__videobridge")

val ApplicationCall.videobridge: Videobridge
    get() = this.attributes.get(VIDEOBRIDGE_ATTR_KEY)
