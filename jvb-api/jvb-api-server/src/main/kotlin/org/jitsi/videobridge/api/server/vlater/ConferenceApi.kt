// /*
//  * Copyright @ 2018 - present 8x8, Inc.
//  *
//  * Licensed under the Apache License, Version 2.0 (the "License");
//  * you may not use this file except in compliance with the License.
//  * You may obtain a copy of the License at
//  *
//  *     http://www.apache.org/licenses/LICENSE-2.0
//  *
//  * Unless required by applicable law or agreed to in writing, software
//  * distributed under the License is distributed on an "AS IS" BASIS,
//  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//  * See the License for the specific language governing permissions and
//  * limitations under the License.
//  */
//
// package org.jitsi.videobridge.api.server.vlater
//
// import io.ktor.application.ApplicationCall
// import io.ktor.application.ApplicationCallPipeline
// import io.ktor.application.call
// import io.ktor.request.receive
// import io.ktor.response.respond
// import io.ktor.routing.Route
// import io.ktor.routing.post
// import io.ktor.routing.route
// import io.ktor.util.AttributeKey
// import org.jitsi.videobridge.api.server.confManager
// import org.jitsi.videobridge.api.types.vlater.ConferenceCreateRequest
// import org.jitsi.videobridge.api.types.vlater.ConferenceCreateResponse
// import org.jitsi.videobridge.api.types.v1.Conference
//
// private const val CONF_ID_URL_PARAM = "confId"
//
// fun Route.conferencesApi() {
//     route("conferences/{$CONF_ID_URL_PARAM}") {
//         post {
//             println("In create conf")
//             val confParams = call.receive<ConferenceCreateRequest>()
//             val conf = call.confManager.createConference("focus", confParams.name)
//             println("Responding to conf create")
//             call.respond(ConferenceCreateResponse(conf.getId()))
//         }
//         // TODO: I think it should be possible to inject the conference only on this 'path' (that is, subroutes
//         // of conferences/confId), right now it does it all the time. (look at the 'authenticate' helper
//         // in ktor for auth, for example)
//         injectConference()
//         endpointsApi()
//     }
// }
//
// fun Route.injectConference() = intercept(ApplicationCallPipeline.Call) {
//     val confMgr = call.confManager
//     confMgr.getConference(call.confId, "focus")?.let {
//         call.attributes.put(EP_MGR_ATTR_KEY, it)
//     } ?: run {
//         println("no conf found")
//     }
// }
//
// /**
//  * Extract the value of the conf ID from the URL.
//  *
//  * NOTE: this should only be called from within the correct scope, as we
//  * enforce that the [CONF_ID_URL_PARAM] param is present
//  */
// val ApplicationCall.confId: String
//     get() = parameters[CONF_ID_URL_PARAM]!!
//
// val EP_MGR_ATTR_KEY = AttributeKey<Conference>("__ep_mgr")
//
// val ApplicationCall.epManager: Conference
//     get() = this.attributes[EP_MGR_ATTR_KEY]
