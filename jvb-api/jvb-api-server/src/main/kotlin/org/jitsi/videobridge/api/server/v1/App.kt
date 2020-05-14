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

package org.jitsi.videobridge.api.server.v1

import io.ktor.application.ApplicationCall
import io.ktor.application.ApplicationCallPipeline
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.features.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.routing.Route
import io.ktor.util.AttributeKey
import org.jitsi.videobridge.api.server.XmlConverter
import org.jitsi.videobridge.api.types.v1.ConferenceManager

/**
 * The top level app.  It provides a convenient wrapper around all the top-level
 * APIs for this API version.
 */
fun Route.app(conferenceManager: ConferenceManager) {
    install(ContentNegotiation) {
        register(ContentType.Application.Xml, XmlConverter)
    }
    injectConfManager(conferenceManager)
    apiVersionApi()
    colibriApi()
}

/**
 * Inject the instance of [ConferenceManager] into the call attributes so that it's
 * available when handling requests
 */
private fun Route.injectConfManager(conferenceManager: ConferenceManager) = intercept(ApplicationCallPipeline.Features) {
    call.attributes.put(CONF_MGR_ATTR_KEY, conferenceManager)
}

private val CONF_MGR_ATTR_KEY = AttributeKey<ConferenceManager>("__conf_mgr")

/**
 * Retrieve the [ConferenceManager] instance from the call attributes
 */
val ApplicationCall.confManager: ConferenceManager
    get() = this.attributes[CONF_MGR_ATTR_KEY]
