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

import io.ktor.application.ApplicationCall
import io.ktor.application.ApplicationCallPipeline
import io.ktor.application.ApplicationFeature
import io.ktor.application.call
import io.ktor.response.respond
import io.ktor.routing.Route
import io.ktor.util.AttributeKey
import io.ktor.util.pipeline.PipelineContext
import org.jitsi.videobridge.Videobridge
import org.jxmpp.jid.impl.JidCreate

/**
 * Finds a [Conference] object and inserts it into the attributes, or invokes
 * [errorFunc] if the conference couldn't be found
 */
fun Route.validateConference(videobridge: Videobridge, errorFunc: (String) -> Any) = intercept(ApplicationCallPipeline.Setup) {
    videobridge.getConference(getConfId(), JidCreate.bareFrom("focus"))?.let {
        call.attributes.put(CONF_KEY, it)
    } ?: run {
        call.respond(errorFunc("Conf with id ${getConfId()} not found"))
        return@intercept finish()
    }
}

class LookupConferenceFeature(config: Configuration) {
    private val videobridge = config.videobridge!!
    private val errorFunc = config.errorFunc

    class Configuration {
        var videobridge: Videobridge? = null
        var errorFunc: (String) -> Any = {}
    }

    private suspend fun intercept(context: PipelineContext<Unit, ApplicationCall>) {
        videobridge.getConference(context.getConfId(), JidCreate.bareFrom("focus"))?.let {

        } ?: run {
            context.call.respond(errorFunc("No conference found for ID ${context.getConfId()}"))
            context.finish()
        }
    }

    companion object Feature : ApplicationFeature<ApplicationCallPipeline, LookupConferenceFeature.Configuration, LookupConferenceFeature> {
        override val key: AttributeKey<LookupConferenceFeature>
            get() = AttributeKey("LookupConferenceFeature")

        override fun install(pipeline: ApplicationCallPipeline, configure: LookupConferenceFeature.Configuration.() -> Unit): LookupConferenceFeature {
            val configuration = LookupConferenceFeature.Configuration().apply(configure)
            val feature = LookupConferenceFeature(configuration)

            pipeline.intercept(ApplicationCallPipeline.Call) {
                feature.intercept(this)
            }

            return feature
        }

    }
}