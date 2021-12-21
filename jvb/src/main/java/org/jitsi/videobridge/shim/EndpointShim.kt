/*
 * Copyright @ 2021 - present 8x8, Inc.
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
package org.jitsi.videobridge.shim

import org.jitsi.nlj.util.NEVER
import org.jitsi.utils.MediaType
import org.jitsi.videobridge.Endpoint
import java.time.Duration
import java.time.Instant

/**
 * Represents an endpoint siglaned via colibri1. In colibri1 an endpoint is represented by a set
 * of channels with the same "endpoint-id" attribute.
 */
class EndpointShim(
    private val endpoint: Endpoint
) {
    /**
     * The set of [ChannelShim]s associated with this endpoint. This
     * allows us to expire the endpoint once all of its 'channels' have been
     * removed. The set of channels shims allows to determine if endpoint
     * can accept audio or video.
     */
    private val channelShims = mutableSetOf<ChannelShim>()

    val maxExpireTimeFromChannelShims: Duration
        get() = channelShims
            .map(ChannelShim::getExpire)
            .map { Duration.ofSeconds(it.toLong()) }
            .maxOrNull() ?: Duration.ZERO

    fun updateForceMute() {
        var audioForceMuted = false
        var videoForceMuted = false
        channelShims.forEach { channelShim ->
            if (!channelShim.allowIncomingMedia()) {
                when (channelShim.mediaType) {
                    MediaType.AUDIO -> audioForceMuted = true
                    MediaType.VIDEO -> videoForceMuted = true
                    else -> Unit
                }
            }
        }
        endpoint.updateForceMute(audioForceMuted, videoForceMuted)
    }

    /**
     * Adds [channelShim] channel to this endpoint.
     */
    fun addChannel(channelShim: ChannelShim) {
        if (channelShims.add(channelShim)) {
            updateAcceptedMediaTypes()
        }
    }

    /**
     * Removes a specific [ChannelShim] from this endpoint.
     */
    fun removeChannel(channelShim: ChannelShim) {
        if (channelShims.remove(channelShim)) {
            if (channelShims.isEmpty()) {
                endpoint.expire()
            } else {
                updateAcceptedMediaTypes()
            }
        }
    }

    /**
     * Update media direction of the [ChannelShim]s associated
     * with this Endpoint.
     *
     * When media direction is set to 'sendrecv' JVB will
     * accept incoming media from endpoint and forward it to
     * other endpoints in a conference. Other endpoint's media
     * will also be forwarded to current endpoint.
     * When media direction is set to 'sendonly' JVB will
     * NOT accept incoming media from this endpoint (not yet implemented), but
     * media from other endpoints will be forwarded to this endpoint.
     * When media direction is set to 'recvonly' JVB will
     * accept incoming media from this endpoint, but will not forward
     * other endpoint's media to this endpoint.
     * When media direction is set to 'inactive' JVB will
     * neither accept incoming media nor forward media from other endpoints.
     *
     * @param type media type.
     * @param direction desired media direction:
     *                       'sendrecv', 'sendonly', 'recvonly', 'inactive'
     */
    @Suppress("unused") // Used by plugins (Yuri)
    fun updateMediaDirection(type: MediaType, direction: String) {
        when (direction) {
            "sendrecv", "sendonly", "recvonly", "inactive" -> {
                channelShims.firstOrNull { it.mediaType == type }?.setDirection(direction)
            }
            else -> {
                throw IllegalArgumentException("Media direction unknown: $direction")
            }
        }
    }

    /**
     * Update accepted media types based on [ChannelShim] permission to receive media
     */
    fun updateAcceptedMediaTypes() {
        var acceptAudio = false
        var acceptVideo = false
        channelShims.forEach { channelShim ->
            // The endpoint accepts audio packets (in the sense of accepting
            // packets from other endpoints being forwarded to it) if it has
            // an audio channel whose direction allows sending packets.
            if (channelShim.allowsSendingMedia()) {
                when (channelShim.mediaType) {
                    MediaType.AUDIO -> acceptAudio = true
                    MediaType.VIDEO -> acceptVideo = true
                    else -> Unit
                }
            }
        }
        endpoint.updateAcceptedMediaTypes(acceptAudio, acceptVideo)
    }

    fun expire() {
        val channelShimsCopy = channelShims.toSet()
        channelShims.clear()
        channelShimsCopy.forEach { channelShim ->
            if (!channelShim.isExpired) {
                channelShim.expire = 0
            }
        }
    }

    /**
     * Return the timestamp of the most recently created [ChannelShim] on this endpoint
     */
    fun getMostRecentChannelCreatedTime(): Instant {
        return channelShims
            .map(ChannelShim::getCreationTimestamp)
            .maxOrNull() ?: NEVER
    }
}
