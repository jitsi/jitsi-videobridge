/*
 * Copyright @ 2020-Present 8x8, Inc
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
package org.jitsi.videobridge.message

import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.jitsi.videobridge.VideoConstraints
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Represent a message sent over the "bridge channel" between an endpoint (or "client") and jitsi-videobridge, or
 * between two jitsi-videobridge instances over Octo.
 *
 * The messages are formatted in JSON with a required "colibriClass" field, which indicates the message type. Different
 * message types have different (if any) additional fields.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "colibriClass")
@JsonSubTypes(
    JsonSubTypes.Type(value = SelectedEndpointsMessage::class, name = SelectedEndpointsMessage.TYPE),
    JsonSubTypes.Type(value = SelectedEndpointMessage::class, name = SelectedEndpointMessage.TYPE),
    JsonSubTypes.Type(value = PinnedEndpointsMessage::class, name = PinnedEndpointsMessage.TYPE),
    JsonSubTypes.Type(value = PinnedEndpointMessage::class, name = PinnedEndpointMessage.TYPE),
    JsonSubTypes.Type(value = ClientHelloMessage::class, name = ClientHelloMessage.TYPE),
    JsonSubTypes.Type(value = ServerHelloMessage::class, name = ServerHelloMessage.TYPE),
    JsonSubTypes.Type(value = EndpointMessage::class, name = EndpointMessage.TYPE),
    JsonSubTypes.Type(value = LastNMessage::class, name = LastNMessage.TYPE),
    JsonSubTypes.Type(value = ReceiverVideoConstraintMessage::class, name = ReceiverVideoConstraintMessage.TYPE),
    JsonSubTypes.Type(value = ReceiverVideoConstraintsMessage::class, name = ReceiverVideoConstraintsMessage.TYPE),
    JsonSubTypes.Type(value = DominantSpeakerMessage::class, name = DominantSpeakerMessage.TYPE),
    JsonSubTypes.Type(value = EndpointConnectionStatusMessage::class, name = EndpointConnectionStatusMessage.TYPE),
    JsonSubTypes.Type(value = ForwardedEndpointsMessage::class, name = ForwardedEndpointsMessage.TYPE),
    JsonSubTypes.Type(value = SenderVideoConstraintsMessage::class, name = SenderVideoConstraintsMessage.TYPE),
    JsonSubTypes.Type(value = AddReceiverMessage::class, name = AddReceiverMessage.TYPE),
    JsonSubTypes.Type(value = RemoveReceiverMessage::class, name = RemoveReceiverMessage.TYPE)
)
// The type is included as colibriClass (as we want) by the annotation above.
@JsonIgnoreProperties("type")
sealed class BridgeChannelMessage(
    val type: String
) {
    fun toJson(): String = ObjectMapper().writeValueAsString(this)

    companion object {
        @JvmStatic
        @Throws(JsonProcessingException::class, JsonMappingException::class)
        fun parse(string: String): BridgeChannelMessage {
            return jacksonObjectMapper().readValue(string)
        }
    }
}

open class MessageHandler {
    private val receivedCounts = ConcurrentHashMap<String, AtomicLong>()

    /**
     * Handles a [BridgeChannelMessage] that was received. Returns an optional response.
     */
    fun handleMessage(message: BridgeChannelMessage): BridgeChannelMessage? {
        receivedCounts.computeIfAbsent(message::class.java.simpleName) { AtomicLong() }.incrementAndGet()

        return when (message) {
            is SelectedEndpointsMessage -> selectedEndpoints(message)
            is SelectedEndpointMessage -> selectedEndpoint(message)
            is PinnedEndpointsMessage -> pinnedEndpoints(message)
            is PinnedEndpointMessage -> pinnedEndpoint(message)
            is ClientHelloMessage -> clientHello(message)
            is ServerHelloMessage -> serverHello(message)
            is EndpointMessage -> endpointMessage(message)
            is LastNMessage -> lastN(message)
            is ReceiverVideoConstraintMessage -> receiverVideoConstraint(message)
            is ReceiverVideoConstraintsMessage -> receiverVideoConstraints(message)
            is DominantSpeakerMessage -> dominantSpeaker(message)
            is EndpointConnectionStatusMessage -> endpointConnectionStatus(message)
            is ForwardedEndpointsMessage -> forwardedEndpoints(message)
            is SenderVideoConstraintsMessage -> senderVideoConstraints(message)
            is AddReceiverMessage -> addReceiver(message)
            is RemoveReceiverMessage -> removeReceiver(message)
        }
    }

    open fun unhandledMessage(message: BridgeChannelMessage) {}
    private fun unhandledMessageReturnNull(message: BridgeChannelMessage): BridgeChannelMessage? {
        unhandledMessage(message)
        return null
    }

    open fun selectedEndpoints(message: SelectedEndpointsMessage) = unhandledMessageReturnNull(message)
    open fun selectedEndpoint(message: SelectedEndpointMessage) = unhandledMessageReturnNull(message)
    open fun pinnedEndpoints(message: PinnedEndpointsMessage) = unhandledMessageReturnNull(message)
    open fun pinnedEndpoint(message: PinnedEndpointMessage) = unhandledMessageReturnNull(message)
    open fun clientHello(message: ClientHelloMessage) = unhandledMessageReturnNull(message)
    open fun serverHello(message: ServerHelloMessage) = unhandledMessageReturnNull(message)
    open fun endpointMessage(message: EndpointMessage) = unhandledMessageReturnNull(message)
    open fun lastN(message: LastNMessage) = unhandledMessageReturnNull(message)
    open fun receiverVideoConstraint(message: ReceiverVideoConstraintMessage) = unhandledMessageReturnNull(message)
    open fun receiverVideoConstraints(message: ReceiverVideoConstraintsMessage) = unhandledMessageReturnNull(message)
    open fun dominantSpeaker(message: DominantSpeakerMessage) = unhandledMessageReturnNull(message)
    open fun endpointConnectionStatus(message: EndpointConnectionStatusMessage) = unhandledMessageReturnNull(message)
    open fun forwardedEndpoints(message: ForwardedEndpointsMessage) = unhandledMessageReturnNull(message)
    open fun senderVideoConstraints(message: SenderVideoConstraintsMessage) = unhandledMessageReturnNull(message)
    open fun addReceiver(message: AddReceiverMessage) = unhandledMessageReturnNull(message)
    open fun removeReceiver(message: RemoveReceiverMessage) = unhandledMessageReturnNull(message)

    fun getReceivedCounts() = receivedCounts.mapValues { it.value.get() }
}

/**
 * A message sent from a client to a bridge, indicating that the list of endpoints selected by the client has changed.
 */
class SelectedEndpointsMessage(val selectedEndpoints: List<String>) : BridgeChannelMessage(TYPE) {

    companion object {
        const val TYPE = "SelectedEndpointsChangedEvent"
    }
}

/**
 * A message sent from a client to a bridge, indicating that the client's selected endpoint has changed.
 *
 * This format is no longer used in jitsi-meet and is considered deprecated. The semantics are equivalent to
 * [SelectedEndpointsMessage] with a list of one endpoint.
 */
@Deprecated("Use SelectedEndpointsMessage")
class SelectedEndpointMessage(val selectedEndpoint: String?) : BridgeChannelMessage(TYPE) {
    companion object {
        const val TYPE = "SelectedEndpointChangedEvent"
    }
}

/**
 * A message sent from a client to a bridge, indicating that the list of endpoints pinned by the client has changed.
 */
class PinnedEndpointsMessage(val pinnedEndpoints: List<String>) : BridgeChannelMessage(TYPE) {

    companion object {
        const val TYPE = "PinnedEndpointsChangedEvent"
    }
}

/**
 * A message sent from a client to a bridge, indicating that the client's pinned endpoint has changed.
 *
 * This format is no longer used in jitsi-meet and is considered deprecated. The semantics are equivalent to
 * [PinnedEndpointsMessage] with a list of one endpoint.
 */
@Deprecated("Use SelectedEndpointsMessage")
class PinnedEndpointMessage(val pinnedEndpoint: String?) : BridgeChannelMessage(TYPE) {
    companion object {
        const val TYPE = "PinnedEndpointChangedEvent"
    }
}

/**
 * A message sent from a client to a bridge in the beginning of a session.
 */
class ClientHelloMessage : BridgeChannelMessage(TYPE) {
    companion object {
        const val TYPE = "ClientHello"
    }
}

/**
 * A message sent from a bridge to a client in response to a [ClientHelloMessage] or when a websocket is accepted.
 */
class ServerHelloMessage : BridgeChannelMessage(TYPE) {
    companion object {
        const val TYPE = "ServerHello"
    }
}

/**
 * An endpoint-to-endpoint message, which originates from one endpoint and is routed by the bridge. It can be targeted
 * to a specific endpoint (when [to] is not-blank) or broadcast (when [to] is blank).
 *
 * The message contains custom fields, which are to be preserved (via [otherFields]).
 *
 * The same class is used regardless of whether the specific message is received from a client, received from a
 * bridge, sent to a client or sent to a bridge.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
class EndpointMessage(val to: String) : BridgeChannelMessage(TYPE) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    var from: String? = null

    @get:JsonAnyGetter
    val otherFields = mutableMapOf<String, Any>()

    @JsonAnySetter
    fun put(key: String, value: Any) {
        otherFields[key] = value
    }

    companion object {
        const val TYPE = "EndpointMessage"
    }
}

/**
 * A message sent from a client, indicating that it wishes to change its "lastN" (i.e. the maximum number of video
 * streams to be received).
 */
class LastNMessage(val lastN: Int) : BridgeChannelMessage(TYPE) {
    companion object {
        const val TYPE = "LastNChangedEvent"
    }
}

/**
 * A message sent from a client to a bridge indicating what the maximum resolution it wishes to receive (for any stream)
 * is.
 *
 * Note that this message is substantially different from its successor
 * [ReceiverVideoConstraintsMessage] and should not be confused with it.
 *
 * Example Json message:
 * {
 *     "colibriClass": "ReceiverVideoConstraint",
 *     "maxFrameHeight": 180
 *
 * }
 */
@Deprecated("Use ReceiverVideoConstraints")
class ReceiverVideoConstraintMessage(val maxFrameHeight: Int) : BridgeChannelMessage(TYPE) {
    companion object {
        const val TYPE = "ReceiverVideoConstraint"
    }
}

/**
 * A message sent from a client to a bridge, indicating constraints on the streams it wishes to receive. The constraints
 * are expressed as a list of [VideoConstraints], each of which specify the remote endpoint for which the constraint
 * applies and the `idealHeight`.
 *
 * NOTE that the intention is for this message to completely replace the following five messages:
 * [ReceiverVideoConstraintMessage], [PinnedEndpointMessage], [PinnedEndpointsMessage], [SelectedEndpointMessage], and
 * [SelectedEndpointsMessage].
 *
 * This isn't the case currently because that would require substantial changes in the client and instead it was
 * decided to provide a server side compatibility layer.
 *
 * Usage of the above old-world data messages should be avoided in future code.
 *
 * Example Json message:
 *
 * {
 *   "colibriClass": "ReceiverVideoConstraintsChangedEvent",
 *   "videoConstraints": [
 *     { "id": "abcdabcd", "idealHeight": 180 },
 *     { "id": "12341234", "idealHeight": 360 }
 *   ]
 * }
 */
class ReceiverVideoConstraintsMessage(val videoConstraints: List<VideoConstraints>) : BridgeChannelMessage(TYPE) {

    data class VideoConstraints(val id: String, val idealHeight: Int)

    companion object {
        const val TYPE = "ReceiverVideoConstraintsChangedEvent"
    }
}

/**
 * A message sent from the bridge to a client, indicating that the dominant speaker in the conference changed.
 */
class DominantSpeakerMessage(val dominantSpeakerEndpoint: String) : BridgeChannelMessage(TYPE) {
    companion object {
        const val TYPE = "DominantSpeakerEndpointChangeEvent"
    }
}

/**
 * A message sent from the bridge to a client, indicating that the bridge's connection to another endpoint changed
 * status.
 */
class EndpointConnectionStatusMessage(
    val endpoint: String,
    activeBoolean: Boolean
) : BridgeChannelMessage(TYPE) {

    // For whatever reason we encode the boolean in JSON as a string.
    val active: String = activeBoolean.toString()

    companion object {
        const val TYPE = "EndpointConnectivityStatusChangeEvent"
    }
}

/**
 * A message sent from the bridge to a client, indicating the set of endpoints that are currently being forwarded.
 *
 * TODO: document the semantics of the fields.
 */
class ForwardedEndpointsMessage(
    @get:JsonProperty("lastNEndpoints")
    val forwardedEndpoints: Collection<String>,
    val endpointsEnteringLastN: Collection<String>,
    val conferenceEndpoints: Collection<String>
) : BridgeChannelMessage(TYPE) {

    companion object {
        const val TYPE = "LastNEndpointsChangeEvent"
    }
}

/**
 * A message sent from the bridge to a client (sender), indicating constraints for the sender's video streams.
 *
 * TODO: consider and adjust the format of videoConstraints. Do we need all of the VideoConstraints fields? Document.
 */
class SenderVideoConstraintsMessage(val videoConstraints: VideoConstraints) : BridgeChannelMessage(TYPE) {
    companion object {
        const val TYPE = "SenderVideoConstraints"
    }
}

/**
 * A message sent from one bridge to another (via Octo) indicating that the first bridge wishes to receive video streams
 * from the specified endpoint with the specified constraints.
 */
class AddReceiverMessage(
    val bridgeId: String,
    val endpointId: String,
    val videoConstraints: VideoConstraints
) : BridgeChannelMessage(TYPE) {
    companion object {
        const val TYPE = "AddReceiver"
    }
}

/**
 * A message sent from one bridge to another (via Octo) indicating that it no longer wishes to receive video streams
 * from the specified endpoint.
 */
class RemoveReceiverMessage(
    val bridgeId: String,
    val endpointId: String
) : BridgeChannelMessage(TYPE) {
    companion object {
        const val TYPE = "RemoveReceiver"
    }
}
