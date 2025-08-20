/*
 * Copyright @ 2025 - present 8x8, Inc.
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

package org.jitsi.videobridge

import org.jitsi.videobridge.message.ReceiverAudioSubscriptionMessage
import org.jitsi.videobridge.relay.AudioSourceDesc
import org.jitsi.videobridge.relay.RelayedEndpoint
import java.util.concurrent.ConcurrentHashMap

class AudioSubscriptionManager(private val findSourceOwner: (String) -> AbstractEndpoint? = { null }) {
    /**
     * A map of endpoint IDs to their audio subscriptions.
     */
    private val audioSubscriptions = ConcurrentHashMap<String, AudioSubscription>()

    /**
     * A map of local audio source names to a set of endpoint IDs that subscribe to the source with an "Include" type.
     */
    private val subscribedLocalAudioSources = ConcurrentHashMap<String, MutableSet<String>>()

    /**
     * A map of remote audio source names to a set of endpoint IDs that subscribe to the source with an "Include" type.s
     */
    private val subscribedRemoteAudioSources = ConcurrentHashMap<String, MutableSet<String>>()

    private val lock: Any = Any()

    /**
     * Sets the audio subscription for a given endpoint.
     * @param endpointId the ID of the endpoint
     * @param subscription the audio subscription message
     */
    fun setEndpointAudioSubscription(
        endpointId: String,
        subscription: ReceiverAudioSubscriptionMessage,
        audioSources: List<AudioSourceDesc>
    ) = synchronized(lock) {
        val audioSubscription = audioSubscriptions.getOrPut(endpointId) {
            AudioSubscription()
        }

        // Update subscribed local sources before updating subscription
        updateSubscribedAudioSourcesForEndpoint(endpointId, subscription, audioSources)
        audioSubscription.updateSubscription(subscription, audioSources)
    }

    /**
     * Checks if audio from a given SSRC is wanted by a specific endpoint.
     * @param endpointId the ID of the endpoint
     * @param ssrc the SSRC to check
     * @return true if the audio is wanted, false otherwise
     */
    fun isEndpointAudioWanted(endpointId: String, ssrc: Long): Boolean {
        val subscription = audioSubscriptions[endpointId]
        return subscription?.isSsrcWanted(ssrc) ?: true
    }

    /**
     * Checks if a specific local audio source is explicitly subscribed to by any endpoint.
     * @param sourceName the name of the audio source
     * @return true if the source is explicitly subscribed, false otherwise
     */
    fun isExplicitlySubscribed(sourceName: String?): Boolean {
        return subscribedLocalAudioSources.containsKey(sourceName) ||
            subscribedRemoteAudioSources.containsKey(sourceName)
    }

    /**
     * Updates the subscribed local audio sources for a specific endpoint based on their subscription.
     * @param endpointId the ID of the endpoint
     * @param subscription the audio subscription message
     * @param audioSources the list of available local audio sources
     */
    private fun updateSubscribedAudioSourcesForEndpoint(
        endpointId: String,
        subscription: ReceiverAudioSubscriptionMessage,
        audioSources: List<AudioSourceDesc>
    ) {
        removeEndpointFromSubscribedLocalAudioSources(endpointId)
        val emptyEntries = mutableSetOf<String>()
        subscribedRemoteAudioSources.forEach {
            it.value.remove(endpointId)
            if (it.value.isEmpty()) {
                // Do not remove entry here because this source may still be included in new subscription
                emptyEntries.add(it.key)
            }
        }
        when (subscription) {
            is ReceiverAudioSubscriptionMessage.Include -> {
                subscription.list.forEach { sourceName ->
                    val localSource = audioSources.find { it.sourceName == sourceName }
                    if (localSource != null) {
                        subscribedLocalAudioSources.getOrPut(sourceName) { mutableSetOf() }.add(endpointId)
                    } else {
                        val endpoint = findSourceOwner(sourceName)
                        if (endpoint is RelayedEndpoint) {
                            val entry = subscribedRemoteAudioSources.get(sourceName)
                            if (entry != null) {
                                entry.add(endpointId)
                                emptyEntries.remove(sourceName) // The source is still subscribed by this endpoint
                            } else {
                                subscribedRemoteAudioSources[sourceName] = mutableSetOf(endpointId)
                                // TODO: Notify relay of new explicit subscription
                            }
                        }
                    }
                }
            }
            else -> {
                // For All, None, and Exclude subscriptions, we don't track explicit subscriptions
            }
        }
        // SourceNames remaining in emptyEntries here are no longer subscribed by any endpoint
        emptyEntries.forEach { sourceName ->
            subscribedRemoteAudioSources.remove(sourceName)
            // TODO: Notify the relay of removed explicit subscription
        }
    }

    /**
     * Called when new audio sources are added to the conference.
     * @param sources the new audio source descriptions
     */
    fun onSourcesAdded(sources: Set<AudioSourceDesc>) = synchronized(lock) {
        audioSubscriptions.values.forEach { subscription ->
            subscription.onConferenceSourceAdded(sources)
        }
    }

    /**
     * Called when an endpoint is removed from the conference.
     * @param id the endpoint ID that was removed
     */
    fun removeEndpoint(id: String) = synchronized(lock) {
        // Remove the endpoint from all sets in both local and remote map
        // This is necessary to precisely maintain the number of subscriptions to a source
        removeEndpointFromSubscribedLocalAudioSources(id)
        subscribedRemoteAudioSources.forEach {
            it.value.remove(id)
            if (it.value.isEmpty()) {
                subscribedRemoteAudioSources.remove(it.key)
            }
        }
        audioSubscriptions.remove(id)
    }

    fun removeSources(sources: Set<AudioSourceDesc>) = synchronized(lock) {
        subscribedLocalAudioSources.keys.removeAll(sources.mapNotNull { it.sourceName })
        audioSubscriptions.values.forEach { subscription ->
            subscription.onConferenceSourceRemoved(sources)
        }
    }

    fun removeEndpointFromSubscribedLocalAudioSources(endpointId: String) = synchronized(lock) {
        subscribedLocalAudioSources.forEach {
            it.value.remove(endpointId)
            if (it.value.isEmpty()) {
                subscribedLocalAudioSources.remove(it.key)
            }
        }
    }
}
