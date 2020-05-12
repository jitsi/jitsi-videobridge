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

import io.ktor.application.call
import io.ktor.request.receive
import io.ktor.response.respond
import io.ktor.routing.Route
import io.ktor.routing.post
import io.ktor.routing.route
import org.jitsi.videobridge.Videobridge
import org.jitsi.videobridge.api.types.ConferenceCreateRequest
import org.jitsi.videobridge.api.types.ConferenceCreateResponse
import org.jitsi.videobridge.signaling.api.getConfId
import org.jxmpp.jid.impl.JidCreate
import org.jxmpp.jid.parts.Localpart

fun Route.conferencesApi(videobridge: Videobridge) {
    route("conferences/{confId}") {
        post {
            val confParams = call.receive<ConferenceCreateRequest>()
            val conf = videobridge.createConference(JidCreate.bareFrom("focus"), Localpart.from(confParams.name), getConfId())
            println("Responding to conf create")
            call.respond(ConferenceCreateResponse(conf.id))
        }
        endpointsApi(videobridge)
    }
}