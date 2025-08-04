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
import java.util.concurrent.ConcurrentHashMap

class AudioSubscriptionManager() {
    /**
     * A map of endpoint IDs to their audio subscriptions.
     */
    private val audioSubscriptions = ConcurrentHashMap<String, AudioSubscription>()

    /**
     * A map of local audio source names to a set of endpoint IDs that explicitly subscribe to the source.
     */
    private val subscribedLocalAudioSources = mutableMapOf<String, MutableSet<String>>()

    /**
     * Sets the audio subscription for a given endpoint.
     * @param endpointId the ID of the endpoint
     * @param subscription the audio subscription message
     */
    fun setEndpointAudioSubscription(
        endpointId: String,
        subscription: ReceiverAudioSubscriptionMessage,
        audioSources: List<AudioSourceDesc>
    ) {
        val audioSubscription = audioSubscriptions.getOrPut(endpointId) {
            AudioSubscription()
        }

        // Update subscribed local sources before updating subscription
        updateSubscribedLocalAudioSourcesForEndpoint(endpointId, subscription)
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
        return subscription?.isSsrcWanted(ssrc) ?: false
    }

    /**
     * Gets the set of local audio source names that are explicitly subscribed to by at least one endpoint.
     * @return a set of source names
     */
    fun getSubscribedLocalAudioSources(): Set<String> {
        return subscribedLocalAudioSources.keys.toSet()
    }

    /**
     * Updates the subscribed local audio sources for a specific endpoint based on their subscription.
     * @param endpointId the ID of the endpoint
     * @param subscription the audio subscription message
     */
    private fun updateSubscribedLocalAudioSourcesForEndpoint(
        endpointId: String,
        subscription: ReceiverAudioSubscriptionMessage
    ) {
        subscribedLocalAudioSources.values.forEach { endpointSet ->
            endpointSet.remove(endpointId)
        }
        subscribedLocalAudioSources.entries.removeIf { it.value.isEmpty() }
        when (subscription) {
            is ReceiverAudioSubscriptionMessage.Include -> {
                subscription.list.forEach { sourceName ->
                    subscribedLocalAudioSources.getOrPut(sourceName) { mutableSetOf() }.add(endpointId)
                }
            }
            else -> {
                // For All, None, and Exclude subscriptions, we don't track explicit subscriptions
            }
        }
    }

    /**
     * Called when new audio sources are added to the conference.
     * @param sources the new audio source descriptions
     */
    fun onSourcesAdded(sources: Set<AudioSourceDesc>) {
        audioSubscriptions.values.forEach { subscription ->
            subscription.onConferenceSourceAdded(sources)
        }
    }

    /**
     * Called when an endpoint is removed from the conference.
     * @param endpoint the endpoint that was removed
     */
    fun onEndpointRemoved(endpoint: AbstractEndpoint) {
        // Remove the endpoint from all sets
        // This is necessary to precisely maintain the number of subscriptions to a source
        subscribedLocalAudioSources.values.forEach { endpointSet ->
            endpointSet.remove(endpoint.id)
        }
        subscribedLocalAudioSources.entries.removeIf { it.value.isEmpty() }
        audioSubscriptions.remove(endpoint.id)
        onSourcesRemoved(endpoint.audioSources.toSet())
    }

    fun onSourcesRemoved(sources: Set<AudioSourceDesc>) {
        subscribedLocalAudioSources.keys.removeAll(sources.mapNotNull { it.sourceName })
        audioSubscriptions.values.forEach { subscription ->
            subscription.onConferenceSourceRemoved(sources)
        }
    }
}
