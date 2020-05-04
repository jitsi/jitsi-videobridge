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

enum class MediaType {}

enum class PayloadTypeEncoding {
    VP8,
    H264
}

enum class RtpExtensionType(val uri: String) {
    CSRC_AUDIO_LEVEL("urn:ietf:params:rtp-hdrext:csrc-audio-level"),
}

typealias PayloadTypeParams = Map<String, String>

typealias RtcpFeedbackSet = Set<String>

data class PayloadType(
    val pt: Byte,
    val encoding: PayloadTypeEncoding,
    val params: PayloadTypeParams,
    val feedback: RtcpFeedbackSet
)

data class RtpExtension(
    val id: Byte,
    val type: RtpExtensionType
)

data class Source(
    val ssrc: Long,
    val mediaType: MediaType
)

data class SourceGroup(
    val sources: Set<Source>,
    val semantics: String
)

data class SourceInformation(
    val sources: Set<Source>,
    val sourceGroups: Set<SourceGroup>
)

enum class IceRole {
    CONTROLLING,
    CONTROLLED
}

data class Endpoint(
    /**
     * Required when creating an endpoint
     */
    val iceRole: IceRole? = null,
    /**
     * Optional.  A value of null means the payload types won't be affected
     * in any way
     */
    val payloadTypes: Set<PayloadType>? = null,
    /**
     * Optional.  A value of null means the extensions types won't be affected
     * in any way
     */
    val rtpExtensions: Set<RtpExtension>? = null,
    /**
     * Optional.  A value of null means the source information won't be affected
     * in any way
     */
    val sourceInformation: SourceInformation? = null
)

/**
 * Validates that the initial Endpoint request we receive has all the required
 * information
 */
fun Endpoint.validateInitial() {
    if (iceRole == null) {
        throw Exception("IceRole not present")
    }
}

data class Conference(
    /**
     * Required when creating a conference
     */
    val name: String? = null,
    /**
     * Optional.  If null then endpoints won't be affected in any way.  If
     * it contains new endpoints, they will be added.  If it contains existing
     * endpoints, they will be modified.
     * TODO: do we want these semantics? then what about remove?
     */
    val endpoints: Set<Endpoint>? = null
)

fun Conference.validateInitial() {
    if (name == null) {
        throw Exception("name required when creating a conference")
    }
}

data class ConferenceResponse(
    val id: String,
    val name: String
)
