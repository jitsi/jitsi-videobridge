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
     * A map of local audio source names to a set of endpoint IDs that subscribe to the source with an "Include" type.
     */
    private val subscribedLocalAudioSources = ConcurrentHashMap<String, MutableSet<String>>()

    /**
     * The audio sources currently known to the conference. This is the authoritative set against which subscriptions
     * resolve their source names to SSRCs. It is maintained solely via [onSourcesAdded]/[removeSources] under [lock],
     * which is the same lock that guards subscription updates. Resolving against this (rather than a source list
     * captured by the caller outside [lock]) closes the race where a subscription arriving concurrently with the
     * colibri signaling of a source would resolve against a stale snapshot and permanently drop the source.
     */
    private val knownSources = mutableSetOf<AudioSourceDesc>()

    /**
     * An immutable snapshot classifying each currently-known audio SSRC as synthetic (true) or regular (false). An
     * SSRC absent from the map is not a currently-known source and is never routed (see [isEndpointAudioWanted]).
     *
     * This is the single value read on the per-packet hot path: reading one volatile reference to an immutable map
     * yields a consistent "is it known? is it synthetic?" answer with no tearing between two separate sets. It is
     * rebuilt from [knownSources] under [lock] whenever sources change.
     *
     * Gating routing on membership here is what closes the synthetic-source leak in both directions: a synthetic
     * SSRC that is not yet known (media arriving before colibri signals the source) or no longer known (media still
     * arriving just after the source was removed) is absent from this map and so is dropped, rather than falling
     * through to the permissive default and being forwarded as ordinary audio to non-subscribers.
     */
    @Volatile
    private var ssrcSynthetic: Map<Long, Boolean> = emptyMap()

    private val lock: Any = Any()

    /**
     * Sets the audio subscription for a given endpoint.
     * @param endpointId the ID of the endpoint
     * @param subscription the audio subscription message
     */
    fun setEndpointAudioSubscription(endpointId: String, subscription: ReceiverAudioSubscriptionMessage) =
        synchronized(lock) {
            val audioSubscription = audioSubscriptions.getOrPut(endpointId) {
                AudioSubscription()
            }

            // Update subscribed local sources before updating subscription
            updateSubscribedLocalAudioSourcesForEndpoint(endpointId, subscription)
            // Resolve against the authoritative set of known sources, not a snapshot supplied by the caller: the caller
            // gathers sources outside [lock], so a source signaled concurrently could otherwise be missed permanently.
            audioSubscription.updateSubscription(subscription, knownSources.toList())
        }

    /**
     * Checks if audio from a given SSRC is wanted by a specific endpoint.
     * @param endpointId the ID of the endpoint
     * @param ssrc the SSRC to check
     * @return true if the audio is wanted, false otherwise
     */
    /** Whether the given SSRC belongs to a (currently-known) synthetic source. */
    fun isSynthetic(ssrc: Long): Boolean = ssrcSynthetic[ssrc] == true

    /**
     * Returns the synthetic source with the given name, or null if there is none. Resolved against [knownSources]
     * under [lock] so it is consistent with [isSynthetic]/[ssrcSynthetic]: callers (e.g. injecting translated media)
     * see a synthetic source as resolvable exactly while it is treated as synthetic at routing time, rather than
     * relying on a separately-maintained, lock-skewed view of the conference's sources.
     */
    fun findSyntheticSource(sourceName: String): AudioSourceDesc? = synchronized(lock) {
        knownSources.firstOrNull { it.synthetic && it.sourceName == sourceName }
    }

    fun isEndpointAudioWanted(endpointId: String, ssrc: Long): Boolean {
        // Single consistent read: null => not a currently-known source.
        val synthetic = ssrcSynthetic[ssrc]
            // Don't route audio that doesn't correspond to a currently-known source. Real audio always has a source
            // signaled via colibri; an unrecognized SSRC is either a synthetic source whose desc hasn't arrived yet
            // or was just removed (which must not leak as ordinary audio), or a stray SSRC -- none should be routed.
            ?: return false
        if (synthetic) {
            // Synthetic sources are never routed automatically; only to an endpoint that explicitly (Include)
            // subscribed to them by name.
            return audioSubscriptions[endpointId]?.isExplicitlyWanted(ssrc) ?: false
        }
        val subscription = audioSubscriptions[endpointId]
        return subscription?.isSsrcWanted(ssrc) ?: true
    }

    /**
     * Checks if a specific local audio source is explicitly subscribed to by any endpoint.
     * @param sourceName the name of the audio source
     * @return true if the source is explicitly subscribed, false otherwise
     */
    fun isExplicitlySubscribed(sourceName: String?): Boolean {
        return subscribedLocalAudioSources.containsKey(sourceName)
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
        // Only the explicit `include` list counts as an explicit subscription.
        subscription.include.forEach { sourceName ->
            subscribedLocalAudioSources.getOrPut(sourceName) { mutableSetOf() }.add(endpointId)
        }
    }

    /**
     * Called when new audio sources are added to the conference.
     * @param sources the new audio source descriptions
     */
    fun onSourcesAdded(sources: Set<AudioSourceDesc>) = synchronized(lock) {
        knownSources.addAll(sources)
        rebuildSsrcClassification()
        audioSubscriptions.values.forEach { subscription ->
            subscription.onConferenceSourceAdded(sources)
        }
    }

    /** Rebuilds the [ssrcSynthetic] snapshot from [knownSources]. Must be called under [lock]. */
    private fun rebuildSsrcClassification() {
        ssrcSynthetic = knownSources.associate { it.ssrc to it.synthetic }
    }

    /**
     * Called when an endpoint is removed from the conference.
     * @param id the endpoint ID that was removed
     */
    fun removeEndpoint(id: String) = synchronized(lock) {
        // Remove the endpoint from all sets
        // This is necessary to precisely maintain the number of subscriptions to a source
        subscribedLocalAudioSources.values.forEach { endpointSet ->
            endpointSet.remove(id)
        }
        subscribedLocalAudioSources.entries.removeIf { it.value.isEmpty() }
        audioSubscriptions.remove(id)
    }

    fun removeSources(sources: Set<AudioSourceDesc>) = synchronized(lock) {
        knownSources.removeAll(sources)
        rebuildSsrcClassification()
        subscribedLocalAudioSources.keys.removeAll(sources.mapNotNull { it.sourceName })
        audioSubscriptions.values.forEach { subscription ->
            subscription.onConferenceSourceRemoved(sources)
        }
    }
}
