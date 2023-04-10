/*
 * Copyright @ 2022 - Present, 8x8 Inc
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
package org.jitsi.videobridge.colibri2

import org.jitsi.xmpp.extensions.colibri2.Colibri2Endpoint
import org.jitsi.xmpp.extensions.colibri2.Colibri2Error
import org.jitsi.xmpp.util.createError
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.StanzaError

fun createConferenceAlreadyExistsError(iq: IQ, conferenceId: String) = createError(
    iq,
    StanzaError.Condition.conflict,
    "Conference already exists for ID: $conferenceId",
    Colibri2Error(Colibri2Error.Reason.CONFERENCE_ALREADY_EXISTS)
)

fun createConferenceNotFoundError(iq: IQ, conferenceId: String) = createError(
    iq,
    StanzaError.Condition.item_not_found,
    "Conference not found for ID: $conferenceId",
    Colibri2Error(Colibri2Error.Reason.CONFERENCE_NOT_FOUND)
)

fun createGracefulShutdownErrorResponse(iq: IQ): IQ = createError(
    iq,
    StanzaError.Condition.service_unavailable,
    "In graceful shutdown",
    Colibri2Error(Colibri2Error.Reason.GRACEFUL_SHUTDOWN)
)

fun createEndpointNotFoundError(iq: IQ, endpointId: String) = createError(
    iq,
    StanzaError.Condition.item_not_found,
    "Endpoint not found for ID: $endpointId",
    listOf(
        Colibri2Error(Colibri2Error.Reason.UNKNOWN_ENDPOINT),
        Colibri2Endpoint.getBuilder().setId(endpointId).build()
    )
)

fun createFeatureNotImplementedError(iq: IQ, message: String?) = createError(
    iq,
    StanzaError.Condition.feature_not_implemented,
    message ?: "",
    Colibri2Error(Colibri2Error.Reason.FEATURE_NOT_IMPLEMENTED),
)
