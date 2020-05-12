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
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.application.log
import io.ktor.features.CallLogging
import io.ktor.features.ContentNegotiation
import io.ktor.jackson.jackson
import io.ktor.locations.KtorExperimentalLocationsAPI
import io.ktor.locations.Locations
import io.ktor.response.respondText
import io.ktor.routing.get
import io.ktor.routing.route
import io.ktor.routing.routing
import org.jitsi.videobridge.Videobridge
import org.jitsi.videobridge.signaling.api.v1.conferencesApi
import org.slf4j.event.Level

@KtorExperimentalLocationsAPI
@Suppress("unused") // Referenced in reference.conf
@kotlin.jvm.JvmOverloads
fun Application.module(videobridge: Videobridge, testing: Boolean = false) {
    install(ContentNegotiation) {
        jackson {
            enable(SerializationFeature.INDENT_OUTPUT)
//            propertyNamingStrategy = PropertyNamingStrategy.SNAKE_CASE
        }
    }
    install(Locations)
    install(CallLogging) {
        level = Level.TRACE
    }

    routing {
//        trace { application.log.trace(it.buildText()) }
        trace { println(it.buildText()) }
        route("/v1") {
            conferencesApi(videobridge)
        }
//        route("conferences/{confGid}") {
//            /**
//             * Posting to this URL will create a conference
//             */
//            post {
//                val gid = call.parameters["confGid"] ?: error("no GID present")
//                val confInfo = call.receive<ConferenceCreateRequest>()
//                if (videobridge.getConference(gid, JidCreate.bareFrom("jid")) != null) {
//                    val conf = videobridge.createConference(JidCreate.bareFrom("jid"), Localpart.from(confInfo.name), gid)
//                    call.respond(ConferenceCreateResponse(conf.id, conf.name.toString()))
//                }
//            }
//            /**
//             * Modify this conference
//             */
//            patch {
//
//            }
//            get {
//                // return description of conferences
//            }
//            endpointsApi()
//        }
        get("/hello/world") {
            call.respondText { "Hello!" }
        }
    }
}


// TODO: could we write a feature that could be installed that would look
// up the conference automatically?  middleware, basically?
