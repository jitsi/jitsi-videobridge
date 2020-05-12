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
import io.ktor.application.call
import io.ktor.util.AttributeKey
import io.ktor.util.pipeline.PipelineContext
import org.jitsi.videobridge.Conference

const val CONF_ID_URL_PARAM = "confId"
const val EP_ID_URL_PARAM = "epId"

val CONF_KEY = AttributeKey<Conference>("conf")

fun <T : Any> PipelineContext<T, ApplicationCall>.getParam(paramName: String): String? {
    return this.call.parameters[paramName]
}

fun <T : Any> PipelineContext<T, ApplicationCall>.getConfId(): String? =
    getParam(CONF_ID_URL_PARAM)


fun <T : Any> PipelineContext<T, ApplicationCall>.getEpId(): String? =
    getParam(EP_ID_URL_PARAM)

