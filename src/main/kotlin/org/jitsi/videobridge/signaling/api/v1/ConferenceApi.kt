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

package org.jitsi.videobridge.signaling.api.v1

import io.ktor.application.ApplicationCall
import io.ktor.application.ApplicationCallPipeline
import io.ktor.application.call
import io.ktor.request.receive
import io.ktor.response.respond
import io.ktor.routing.Route
import io.ktor.routing.Routing
import io.ktor.routing.post
import io.ktor.routing.route
import io.ktor.util.AttributeKey
import io.ktor.util.pipeline.PipelineContext
import org.jitsi.videobridge.Conference
import org.jitsi.videobridge.Videobridge
import org.jitsi.videobridge.api.types.ConferenceCreateRequest
import org.jitsi.videobridge.api.types.ConferenceCreateResponse
import org.jitsi.videobridge.signaling.api.getConfId
import org.jitsi.videobridge.signaling.api.videobridge
import org.jxmpp.jid.impl.JidCreate
import org.jxmpp.jid.parts.Localpart

fun Route.conferencesApi() {
    route("conferences/{confId}") {
        post {
            println("In create conf")
            val confParams = call.receive<ConferenceCreateRequest>()
            val conf = call.videobridge.createConference(JidCreate.bareFrom("focus"), Localpart.from(confParams.name), getConfId())
            println("Responding to conf create")
            call.respond(ConferenceCreateResponse(conf.id))
        }
        // TODO: I think it should be possible to inject the conference only on this 'path' (that is, subroutes
        // of conferences/confId), right now it does it all the time.
        injectConference()
        endpointsApi()
    }
}

fun Route.injectConference() = intercept(ApplicationCallPipeline.Call) {
    println("Injecting conference")
    val videobridge = call.videobridge
    val confId = getConfId() ?: TODO()
    videobridge.getConference(confId, JidCreate.bareFrom("focus"))?.let {
        call.attributes.put(CONF_ATTR_KEY, it)
    } ?: run {
        println("no conf found")
    }
}

val CONF_ATTR_KEY = AttributeKey<Conference>("__conference")

val ApplicationCall.conference: Conference
    get() = this.attributes.get(CONF_ATTR_KEY)
