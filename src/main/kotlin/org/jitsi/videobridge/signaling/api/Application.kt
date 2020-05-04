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
import io.ktor.http.HttpStatusCode
import io.ktor.jackson.jackson
import io.ktor.request.receive
import io.ktor.response.respond
import io.ktor.response.respondText
import io.ktor.routing.get
import io.ktor.routing.patch
import io.ktor.routing.post
import io.ktor.routing.route
import io.ktor.routing.routing
import io.ktor.server.engine.commandLineEnvironment
import io.ktor.server.engine.embeddedServer
import io.ktor.server.jetty.Jetty
import io.ktor.server.servlet.ServletApplicationEngine
import org.jitsi.videobridge.Videobridge
import org.jxmpp.jid.impl.JidCreate
import org.jxmpp.jid.parts.Localpart
import java.util.Enumeration
import javax.servlet.ServletConfig
import javax.servlet.ServletContext

@Suppress("unused") // Referenced in reference.conf
@kotlin.jvm.JvmOverloads
fun Application.module(videobridge: Videobridge, testing: Boolean = false) {
    install(ContentNegotiation) {
        jackson {
            enable(SerializationFeature.INDENT_OUTPUT)
//            propertyNamingStrategy = PropertyNamingStrategy.SNAKE_CASE
        }
    }
    install(CallLogging)

    routing {
        trace { application.log.trace(it.buildText()) }
        route("conferences/{confGid}") {
            /**
             * Posting to this URL will create a conference
             */
            post {
                val gid = call.parameters["confGid"] ?: error("no GID present")
                val confInfo = call.receive<Conference>()
                if (videobridge.getConference(gid, JidCreate.bareFrom("jid")) == null) {
                    confInfo.validateInitial()
                    val conf = videobridge.createConference(JidCreate.bareFrom("jid"), Localpart.from(confInfo.name), gid)
                    call.respond(ConferenceResponse(conf.id, conf.name.toString()))
                }
            }
            /**
             * Modify this conference
             */
            patch {

            }
            get {
                // return description of conferences
            }
            route("endpoints/{epId}") {
                post {
                    // Create a new endpoint with any information give
                    val epInfo = call.receive<Endpoint>()
                    println("Conf ${call.parameters["confId"]}, ep ${call.parameters["epId"]}")
                    println(epInfo)
                    call.respond(HttpStatusCode.OK)
                }
                route("rtp_extensions") {
                    post {
                        // Modify the set of rtp extensions for this endpoint.
                        val epId = call.parameters["epId"]
                        println("epId: $epId")
                        val post = call.receive<List<RtpExtension>>()
                        println(post)
                        call.respond(HttpStatusCode.OK)
                    }
                }
                route("payload_types") {
                    post {
                        // Modify the set of payload types for this endpoint
                        val post = call.receive<List<RtpExtension>>()
                        call.respond(HttpStatusCode.OK)
                    }
                }
                route("source_information") {
                    post {
                        // Modify the source information for this endpoint
                        val post = call.receive<SourceInformation>()
                        call.respond(HttpStatusCode.OK)
                    }
                }
            }
        }
        get("/hello/world") {
            call.respondText { "Hello!" }
        }
    }
}

fun foo() {
    embeddedServer(Jetty, port = 9090) { myApp() }.start()
}

fun Application.myApp() = myApp(42)

fun Application.myApp(someArg: Int) {

}
