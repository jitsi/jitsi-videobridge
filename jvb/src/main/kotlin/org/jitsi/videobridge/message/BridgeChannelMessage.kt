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
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.apache.logging.log4j.util.Strings.isEmpty
import org.jitsi.utils.ResettableLazy
import org.jitsi.utils.observableWhenChanged
import org.jitsi.videobridge.cc.allocation.VideoConstraints
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
    JsonSubTypes.Type(value = ClientHelloMessage::class, name = ClientHelloMessage.TYPE),
    JsonSubTypes.Type(value = ServerHelloMessage::class, name = ServerHelloMessage.TYPE),
    JsonSubTypes.Type(value = EndpointMessage::class, name = EndpointMessage.TYPE),
    JsonSubTypes.Type(value = LastNMessage::class, name = LastNMessage.TYPE),
    JsonSubTypes.Type(value = ReceiverVideoConstraintMessage::class, name = ReceiverVideoConstraintMessage.TYPE),
    JsonSubTypes.Type(value = DominantSpeakerMessage::class, name = DominantSpeakerMessage.TYPE),
    JsonSubTypes.Type(value = EndpointConnectionStatusMessage::class, name = EndpointConnectionStatusMessage.TYPE),
    JsonSubTypes.Type(value = ForwardedEndpointsMessage::class, name = ForwardedEndpointsMessage.TYPE),
    JsonSubTypes.Type(value = SenderVideoConstraintsMessage::class, name = SenderVideoConstraintsMessage.TYPE),
    JsonSubTypes.Type(value = AddReceiverMessage::class, name = AddReceiverMessage.TYPE),
    JsonSubTypes.Type(value = RemoveReceiverMessage::class, name = RemoveReceiverMessage.TYPE),
    JsonSubTypes.Type(value = ReceiverVideoConstraintsMessage::class, name = ReceiverVideoConstraintsMessage.TYPE)
)
// The type is included as colibriClass (as we want) by the annotation above.
@JsonIgnoreProperties("type")
sealed class BridgeChannelMessage(
    val type: String
) {
    private val jsonCacheDelegate = ResettableLazy { createJson() }
    /**
     * Caches the JSON string representation of this object. Note that after any changes to state (e.g. vars being set)
     * the cache needs to be invalidated via [resetJsonCache].
     */
    private val jsonCache: String by jsonCacheDelegate
    protected fun resetJsonCache() = jsonCacheDelegate.reset()
    /**
     * Get a JSON representation of this [BridgeChannelMessage].
     */
    fun toJson(): String = jsonCache

    /**
     * Serialize this [BridgeChannelMessage] to a string in JSON format. Note that this default implementation can be
     * slow, which is why some of the messages that we serialize often override it with a custom optimized version.
     */
    protected open fun createJson(): String = mapper.writeValueAsString(this)

    companion object {
        private val mapper = jacksonObjectMapper()
        @JvmStatic
        @Throws(JsonProcessingException::class, JsonMappingException::class)
        fun parse(string: String): BridgeChannelMessage {
            return mapper.readValue(string)
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
            is ClientHelloMessage -> clientHello(message)
            is ServerHelloMessage -> serverHello(message)
            is EndpointMessage -> endpointMessage(message)
            is LastNMessage -> lastN(message)
            is ReceiverVideoConstraintMessage -> receiverVideoConstraint(message)
            is DominantSpeakerMessage -> dominantSpeaker(message)
            is EndpointConnectionStatusMessage -> endpointConnectionStatus(message)
            is ForwardedEndpointsMessage -> forwardedEndpoints(message)
            is SenderVideoConstraintsMessage -> senderVideoConstraints(message)
            is AddReceiverMessage -> addReceiver(message)
            is RemoveReceiverMessage -> removeReceiver(message)
            is ReceiverVideoConstraintsMessage -> receiverVideoConstraints(message)
        }
    }

    open fun unhandledMessage(message: BridgeChannelMessage) {}
    private fun unhandledMessageReturnNull(message: BridgeChannelMessage): BridgeChannelMessage? {
        unhandledMessage(message)
        return null
    }

    open fun selectedEndpoints(message: SelectedEndpointsMessage) = unhandledMessageReturnNull(message)
    open fun selectedEndpoint(message: SelectedEndpointMessage) = unhandledMessageReturnNull(message)
    open fun clientHello(message: ClientHelloMessage) = unhandledMessageReturnNull(message)
    open fun serverHello(message: ServerHelloMessage) = unhandledMessageReturnNull(message)
    open fun endpointMessage(message: EndpointMessage) = unhandledMessageReturnNull(message)
    open fun lastN(message: LastNMessage) = unhandledMessageReturnNull(message)
    open fun receiverVideoConstraint(message: ReceiverVideoConstraintMessage) = unhandledMessageReturnNull(message)
    open fun dominantSpeaker(message: DominantSpeakerMessage) = unhandledMessageReturnNull(message)
    open fun endpointConnectionStatus(message: EndpointConnectionStatusMessage) = unhandledMessageReturnNull(message)
    open fun forwardedEndpoints(message: ForwardedEndpointsMessage) = unhandledMessageReturnNull(message)
    open fun senderVideoConstraints(message: SenderVideoConstraintsMessage) = unhandledMessageReturnNull(message)
    open fun addReceiver(message: AddReceiverMessage) = unhandledMessageReturnNull(message)
    open fun removeReceiver(message: RemoveReceiverMessage) = unhandledMessageReturnNull(message)
    open fun receiverVideoConstraints(message: ReceiverVideoConstraintsMessage) = unhandledMessageReturnNull(message)

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
class ServerHelloMessage @JvmOverloads constructor(
    @JsonInclude(JsonInclude.Include.NON_NULL)
    val version: String? = null
) : BridgeChannelMessage(TYPE) {

    override fun createJson(): String =
        if (version == null) JSON_STRING_NO_VERSION else """{"colibriClass":"$TYPE","version":"$version"}"""
    companion object {
        const val TYPE = "ServerHello"
        const val JSON_STRING_NO_VERSION: String = """{"colibriClass":"$TYPE"}"""
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
        set(value) {
            field = value
            resetJsonCache()
        }

    @get:JsonAnyGetter
    val otherFields = mutableMapOf<String, Any>()

    /**
     * Whether this message is to be broadcast or targeted to a specific endpoint.
     */
    val isBroadcast: Boolean = isEmpty(to)

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
 * A message sent from the bridge to a client, indicating that the dominant speaker in the conference changed.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
class DominantSpeakerMessage @JvmOverloads constructor(
    dominantSpeakerEndpoint: String,
    val previousSpeakers: List<String>? = null
) : BridgeChannelMessage(TYPE) {
    var dominantSpeakerEndpoint: String by observableWhenChanged(dominantSpeakerEndpoint) { -> resetJsonCache() }
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

    /**
     * Serialize manually because it's faster than Jackson.
     */
    override fun createJson(): String =
        """{"colibriClass":"$TYPE","endpoint":"$endpoint","active":"$active"}"""

    companion object {
        const val TYPE = "EndpointConnectivityStatusChangeEvent"
    }
}

/**
 * A message sent from the bridge to a client, indicating the set of endpoints that are currently being forwarded.
 */
class ForwardedEndpointsMessage(
    @get:JsonProperty("lastNEndpoints")
    /**
     * The set of endpoints for which the bridge is currently sending video.
     */
    val forwardedEndpoints: Collection<String>
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
    constructor(maxHeight: Int) : this(VideoConstraints(maxHeight))

    /**
     * Serialize manually because it's faster than Jackson.
     *
     * We use the "idealHeight" format that the jitsi-meet client expects.
     */
    override fun createJson(): String =
        """{"colibriClass":"$TYPE", "videoConstraints":{"idealHeight":${videoConstraints.idealHeight}}}"""

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VideoConstraints(val idealHeight: Int)

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
    /**
     * Serialize manually because it's faster than Jackson.
     */
    override fun createJson(): String =
        """{"colibriClass":"$TYPE","bridgeId":"$bridgeId","endpointId":"$endpointId",""" +
            "\"videoConstraints\":$videoConstraints}"

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
    /**
     * Serialize manually because it's faster than Jackson.
     */
    override fun createJson(): String =
        """{"colibriClass":"$TYPE","bridgeId":"$bridgeId","endpointId":"$endpointId"}"""

    companion object {
        const val TYPE = "RemoveReceiver"
    }
}

class ReceiverVideoConstraintsMessage(
    val lastN: Int? = null,
    val selectedEndpoints: List<String>? = null,
    val onStageEndpoints: List<String>? = null,
    val defaultConstraints: VideoConstraints? = null,
    val constraints: Map<String, VideoConstraints>? = null
) : BridgeChannelMessage(TYPE) {
    companion object {
        const val TYPE = "ReceiverVideoConstraints"
    }
}
