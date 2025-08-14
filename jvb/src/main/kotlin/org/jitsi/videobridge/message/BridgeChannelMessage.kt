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
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.jitsi.nlj.VideoType
import org.jitsi.utils.ResettableLazy
import org.jitsi.videobridge.cc.allocation.VideoConstraints
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Represent a message sent over the "bridge channel" between an endpoint (or "client") and jitsi-videobridge, or
 * between two jitsi-videobridge instances over a relay connection.
 *
 * The messages are formatted in JSON with a required "colibriClass" field, which indicates the message type. Different
 * message types have different (if any) additional fields.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = BridgeChannelMessage.TYPE_PROPERTY_NAME)
@JsonSubTypes(
    JsonSubTypes.Type(value = ClientHelloMessage::class, name = ClientHelloMessage.TYPE),
    JsonSubTypes.Type(value = ServerHelloMessage::class, name = ServerHelloMessage.TYPE),
    JsonSubTypes.Type(value = EndpointMessage::class, name = EndpointMessage.TYPE),
    JsonSubTypes.Type(value = EndpointStats::class, name = EndpointStats.TYPE),
    JsonSubTypes.Type(value = LastNMessage::class, name = LastNMessage.TYPE),
    JsonSubTypes.Type(value = DominantSpeakerMessage::class, name = DominantSpeakerMessage.TYPE),
    JsonSubTypes.Type(value = EndpointConnectionStatusMessage::class, name = EndpointConnectionStatusMessage.TYPE),
    JsonSubTypes.Type(value = ForwardedSourcesMessage::class, name = ForwardedSourcesMessage.TYPE),
    JsonSubTypes.Type(value = VideoSourcesMap::class, name = VideoSourcesMap.TYPE),
    JsonSubTypes.Type(value = AudioSourcesMap::class, name = AudioSourcesMap.TYPE),
    JsonSubTypes.Type(value = SenderSourceConstraintsMessage::class, name = SenderSourceConstraintsMessage.TYPE),
    JsonSubTypes.Type(value = AddReceiverMessage::class, name = AddReceiverMessage.TYPE),
    JsonSubTypes.Type(value = RemoveReceiverMessage::class, name = RemoveReceiverMessage.TYPE),
    JsonSubTypes.Type(value = ReceiverVideoConstraintsMessage::class, name = ReceiverVideoConstraintsMessage.TYPE),
    JsonSubTypes.Type(value = SourceVideoTypeMessage::class, name = SourceVideoTypeMessage.TYPE),
    JsonSubTypes.Type(value = ConnectionStats::class, name = ConnectionStats.TYPE),
    JsonSubTypes.Type(value = VideoTypeMessage::class, name = VideoTypeMessage.TYPE),
    JsonSubTypes.Type(value = ReceiverAudioSubscriptionMessage::class, name = ReceiverAudioSubscriptionMessage.TYPE)
)
sealed class BridgeChannelMessage {
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
        private val mapper = jacksonObjectMapper().apply {
            enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        }

        @JvmStatic
        @Throws(JsonProcessingException::class, JsonMappingException::class)
        fun parse(string: String): BridgeChannelMessage {
            return mapper.readValue(string)
        }
        const val TYPE_PROPERTY_NAME = "colibriClass"
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
            is ClientHelloMessage -> clientHello(message)
            is ServerHelloMessage -> serverHello(message)
            is EndpointMessage -> endpointMessage(message)
            is EndpointStats -> endpointStats(message)
            is LastNMessage -> lastN(message)
            is DominantSpeakerMessage -> dominantSpeaker(message)
            is EndpointConnectionStatusMessage -> endpointConnectionStatus(message)
            is ForwardedSourcesMessage -> forwardedSources(message)
            is VideoSourcesMap -> videoSourcesMap(message)
            is AudioSourcesMap -> audioSourcesMap(message)
            is SenderSourceConstraintsMessage -> senderSourceConstraints(message)
            is AddReceiverMessage -> addReceiver(message)
            is RemoveReceiverMessage -> removeReceiver(message)
            is ReceiverVideoConstraintsMessage -> receiverVideoConstraints(message)
            is SourceVideoTypeMessage -> sourceVideoType(message)
            is ConnectionStats -> connectionStats(message)
            is VideoTypeMessage -> videoType(message)
            is ReceiverAudioSubscriptionMessage -> receiverAudioSubscription(message)
        }
    }

    open fun unhandledMessage(message: BridgeChannelMessage) {}
    private fun unhandledMessageReturnNull(message: BridgeChannelMessage): BridgeChannelMessage? {
        unhandledMessage(message)
        return null
    }

    open fun clientHello(message: ClientHelloMessage) = unhandledMessageReturnNull(message)
    open fun serverHello(message: ServerHelloMessage) = unhandledMessageReturnNull(message)
    open fun endpointMessage(message: EndpointMessage) = unhandledMessageReturnNull(message)
    open fun endpointStats(message: EndpointStats) = unhandledMessageReturnNull(message)
    open fun lastN(message: LastNMessage) = unhandledMessageReturnNull(message)
    open fun dominantSpeaker(message: DominantSpeakerMessage) = unhandledMessageReturnNull(message)
    open fun endpointConnectionStatus(message: EndpointConnectionStatusMessage) = unhandledMessageReturnNull(message)
    open fun forwardedSources(message: ForwardedSourcesMessage) = unhandledMessageReturnNull(message)
    open fun videoSourcesMap(message: VideoSourcesMap) = unhandledMessageReturnNull(message)
    open fun audioSourcesMap(message: AudioSourcesMap) = unhandledMessageReturnNull(message)
    open fun senderSourceConstraints(message: SenderSourceConstraintsMessage) = unhandledMessageReturnNull(message)
    open fun addReceiver(message: AddReceiverMessage) = unhandledMessageReturnNull(message)
    open fun removeReceiver(message: RemoveReceiverMessage) = unhandledMessageReturnNull(message)
    open fun receiverVideoConstraints(message: ReceiverVideoConstraintsMessage) = unhandledMessageReturnNull(message)
    open fun sourceVideoType(message: SourceVideoTypeMessage) = unhandledMessageReturnNull(message)
    open fun connectionStats(message: ConnectionStats) = unhandledMessageReturnNull(message)
    open fun videoType(message: VideoTypeMessage) = unhandledMessageReturnNull(message)
    open fun receiverAudioSubscription(message: ReceiverAudioSubscriptionMessage) = unhandledMessageReturnNull(message)

    fun getReceivedCounts() = receivedCounts.mapValues { it.value.get() }
}

/**
 * A message sent from a client to a bridge in the beginning of a session.
 */
class ClientHelloMessage : BridgeChannelMessage() {
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
) : BridgeChannelMessage() {

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
class EndpointMessage(val to: String) : BridgeChannelMessage() {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    var from: String? = null
        set(value) {
            field = value
            resetJsonCache()
        }

    @get:JsonAnyGetter
    val otherFields = mutableMapOf<String, Any?>()

    /**
     * Whether this message is to be broadcast or targeted to a specific endpoint.
     */
    @JsonIgnore
    fun isBroadcast(): Boolean = to.isBlank()

    @JsonAnySetter
    fun put(key: String, value: Any?) {
        if (key != TYPE_PROPERTY_NAME) {
            otherFields[key] = value
        }
    }

    companion object {
        const val TYPE = "EndpointMessage"
    }
}

/**
 * An endpoint statistics message, which originates from one endpoint and is routed by the bridge.
 * When received from a client it is filtered depending on which endpoints are "interesting" to other clients.
 *
 * The message contains custom fields, which are to be preserved (via [otherFields]).
 *
 * The same class is used regardless of whether the specific message is received from a client, received from a
 * bridge, sent to a client or sent to a bridge.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
class EndpointStats : BridgeChannelMessage() {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    var from: String? = null
        set(value) {
            field = value
            resetJsonCache()
        }

    @get:JsonAnyGetter
    val otherFields = mutableMapOf<String, Any?>()

    @JsonAnySetter
    fun put(key: String, value: Any?) {
        if (key != TYPE_PROPERTY_NAME) {
            otherFields[key] = value
        }
    }

    companion object {
        const val TYPE = "EndpointStats"
    }
}

/**
 * A message sent from a client, indicating that it wishes to change its "lastN" (i.e. the maximum number of video
 * streams to be received).
 */
class LastNMessage(val lastN: Int) : BridgeChannelMessage() {
    companion object {
        const val TYPE = "LastNChangedEvent"
    }
}

/**
 * A message sent from the bridge to a client, indicating that the dominant speaker in the conference changed.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
class DominantSpeakerMessage @JvmOverloads constructor(
    val dominantSpeakerEndpoint: String,
    val previousSpeakers: List<String>? = null,
    val silence: Boolean = false
) : BridgeChannelMessage() {
    /**
     * Construct a message from a list of speakers with the dominant speaker on top. The list must have at least one
     * element.
     */
    constructor(previousSpeakers: List<String>, silence: Boolean) : this(
        previousSpeakers[0],
        previousSpeakers.drop(1),
        silence
    )
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
) : BridgeChannelMessage() {

    // For whatever reason we encode the boolean in JSON as a string.
    val active: String = activeBoolean.toString()

    /**
     * Serialize manually because it's faster than Jackson.
     */
    override fun createJson(): String = """{"colibriClass":"$TYPE","endpoint":"$endpoint","active":"$active"}"""

    companion object {
        const val TYPE = "EndpointConnectivityStatusChangeEvent"
    }
}

/**
 * A message sent from the bridge to a client, indicating the set of media sources that are currently being forwarded.
 */
class ForwardedSourcesMessage(
    /**
     * The set of media sources for which the bridge is currently sending video.
     */
    val forwardedSources: Collection<String>
) : BridgeChannelMessage() {
    companion object {
        const val TYPE = "ForwardedSources"
    }
}

/**
 * A mapping of a video source to SSRCs.
 */
data class VideoSourceMapping(
    /** The name of the source being mapped. */
    val source: String,
    /** The endpoint that is the sender of the source. */
    val owner: String?,
    /** The primary SSRC of the source being mapped. */
    val ssrc: Long,
    /** The RTX SSRC of the source being mapped. */
    val rtx: Long,
    /** The video type of the source being mapped. */
    val videoType: VideoType
)

/**
 * A message sent from the bridge to a client to indicate video source mappings.
 */
class VideoSourcesMap(
    /* The current list of maps of sources to ssrcs. */
    val mappedSources: Collection<VideoSourceMapping>
) : BridgeChannelMessage() {
    companion object {
        const val TYPE = "VideoSourcesMap"
    }
}

/**
 * A mapping of an audio source to SSRCs.
 */
data class AudioSourceMapping(
    /** The name of the source being mapped. */
    val source: String,
    /** The endpoint that is the sender of the source. */
    val owner: String?,
    /** The SSRC of the source being mapped. */
    val ssrc: Long,
)

/**
 * A message sent from the bridge to a client to indicate audio source mappings.
 */
class AudioSourcesMap(
    /* The current list of maps of sources to ssrcs. */
    val mappedSources: Collection<AudioSourceMapping>
) : BridgeChannelMessage() {
    companion object {
        const val TYPE = "AudioSourcesMap"
    }
}

/**
 * A message sent from the bridge to a client (sender), indicating constraints for the sender's video stream.
 */
class SenderSourceConstraintsMessage(
    val sourceName: String,
    val maxHeight: Int
) : BridgeChannelMessage() {

    /**
     * Serialize manually because it's faster than Jackson.
     */
    override fun createJson(): String =
        """{"colibriClass":"$TYPE", "sourceName":"$sourceName", "maxHeight":$maxHeight}"""

    companion object {
        const val TYPE = "SenderSourceConstraints"
    }
}

/**
 * A message sent from one bridge to another (via a relay connection) indicating that the first bridge wishes to
 * receive video streams from the specified endpoint with the specified constraints.
 */
class AddReceiverMessage(
    val bridgeId: String,
    val sourceName: String,
    val videoConstraints: VideoConstraints
) : BridgeChannelMessage() {
    /**
     * Serialize manually because it's faster than Jackson.
     */
    override fun createJson(): String = "{\"colibriClass\":\"$TYPE\",\"bridgeId\":\"$bridgeId\"," +
        "\"sourceName\":\"$sourceName\"," +
        "\"videoConstraints\":$videoConstraints}"

    companion object {
        const val TYPE = "AddReceiver"
    }
}

/**
 * A message sent from one bridge to another (via a relay connection) indicating that it no longer wishes to receive
 * video streams from the specified endpoint.
 */
class RemoveReceiverMessage(
    val bridgeId: String,
    val endpointId: String
) : BridgeChannelMessage() {
    /**
     * Serialize manually because it's faster than Jackson.
     */
    override fun createJson(): String = """{"colibriClass":"$TYPE","bridgeId":"$bridgeId","endpointId":"$endpointId"}"""

    companion object {
        const val TYPE = "RemoveReceiver"
    }
}

class ReceiverVideoConstraintsMessage(
    val lastN: Int? = null,
    val selectedSources: List<String>? = null,
    val onStageSources: List<String>? = null,
    val defaultConstraints: VideoConstraints? = null,
    val constraints: Map<String, VideoConstraints>? = null,
    val assumedBandwidthBps: Long? = null
) : BridgeChannelMessage() {
    companion object {
        const val TYPE = "ReceiverVideoConstraints"
    }
}

/**
 * A message signaling the video type of the media source.
 */
class SourceVideoTypeMessage(
    val videoType: VideoType,
    sourceName: String,
    /**
     * The endpoint ID that the message relates to, or null. When null, the ID is inferred from the channel the
     * message was received on (non-null values are needed only for Relays).
     */
    endpointId: String? = null
) : BridgeChannelMessage() {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    var endpointId: String? = endpointId
        set(value) {
            field = value
            resetJsonCache()
        }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    var sourceName: String = sourceName
        set(value) {
            field = value
            resetJsonCache()
        }

    companion object {
        const val TYPE = "SourceVideoTypeMessage"
    }
}

/*
 * A message sent from a client to the bridge to indicate which audio sources it wants to receive.
 */
sealed class ReceiverAudioSubscriptionMessage : BridgeChannelMessage() {
    object All : ReceiverAudioSubscriptionMessage() {
        override fun createJson(): String {
            return """{"colibriClass":"$TYPE","mode":"All"}"""
        }
    }
    object None : ReceiverAudioSubscriptionMessage() {
        override fun createJson(): String {
            return """{"colibriClass":"$TYPE","mode":"None"}"""
        }
    }
    data class Include(val list: List<String>) : ReceiverAudioSubscriptionMessage()
    data class Exclude(val list: List<String>) : ReceiverAudioSubscriptionMessage()
    companion object {
        const val TYPE = "ReceiverAudioSubscription"

        @JvmStatic
        @JsonCreator
        fun jsonCreator(mode: String, list: List<String>? = null): ReceiverAudioSubscriptionMessage {
            return when (mode) {
                "All" -> All
                "None" -> None
                "Include" -> Include(list ?: emptyList())
                "Exclude" -> Exclude(list ?: emptyList())
                else -> throw IllegalArgumentException("Unknown ReceiverAudioSubscription mode: $mode")
            }
        }
    }
}

/**
 * A message from the bridge to an endpoint giving information about the connection to the bridge
 * (that the client can't determine on its own)
 */
class ConnectionStats(
    val estimatedDownlinkBandwidth: Long,
) : BridgeChannelMessage() {
    companion object {
        const val TYPE = "ConnectionStats"
    }
}

/**
 * A signaling the type of video stream an endpoint has available.
 */
@Deprecated("", ReplaceWith("SourceVideoTypeMessage"), DeprecationLevel.WARNING)
class VideoTypeMessage(
    val videoType: VideoType,
    /**
     * The endpoint ID that the message relates to, or null. When null, the ID is inferred from the channel the
     * message was received on (non-null values are needed only for Relays).
     */
    endpointId: String? = null
) : BridgeChannelMessage() {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    var endpointId: String? = endpointId
        set(value) {
            field = value
            resetJsonCache()
        }

    companion object {
        const val TYPE = "VideoTypeMessage"
    }
}
