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

package org.jitsi.videobridge

import org.jitsi.videobridge.message.ReceiverAudioSubscriptionMessage
import org.jitsi.videobridge.relay.AudioSourceDesc

class AudioSubscriptionEntry(
    private val endpointId: String
) {
    private var latestSubscription: ReceiverAudioSubscriptionMessage = ReceiverAudioSubscriptionMessage(
        listOf("*"),
        emptyList()
    )
    private var wantedSsrcs: Set<Long> = emptySet()

    fun updateSubscription(subscription: ReceiverAudioSubscriptionMessage, sources: List<AudioSourceDesc>) {
        latestSubscription = subscription
        if (subscription.exclude.contains("*") || subscription.include.contains("*")) {
            return
        }
        val descs = sources.filter { desc -> desc.owner != endpointId }
        wantedSsrcs = descs.filter { desc ->
            subscription.include.contains(desc.sourceName) && !subscription.exclude.contains(desc.sourceName)
        }.map(AudioSourceDesc::ssrc).toSet()
    }

    fun isSsrcWanted(ssrc: Long): Boolean {
        if (latestSubscription.exclude.contains("*")) {
            return false
        }
        if (latestSubscription.include.contains("*")) {
            return true
        }
        return wantedSsrcs.contains(ssrc)
    }

    fun onConferenceSourceAdded(descs: Set<AudioSourceDesc>) {
        if (latestSubscription.include.contains("*") || latestSubscription.exclude.contains("*")) {
            return
        }
        val filteredDescs = descs.filter { desc ->
            latestSubscription.include.contains(desc.sourceName) &&
                !latestSubscription.exclude.contains(desc.sourceName)
        }.map(AudioSourceDesc::ssrc).toSet()
        wantedSsrcs = wantedSsrcs.union(filteredDescs)
    }

    fun onConferenceSourceRemoved(descs: Set<AudioSourceDesc>) {
        if (latestSubscription.include.contains("*") || latestSubscription.exclude.contains("*")) {
            return
        }
        wantedSsrcs = wantedSsrcs.subtract(descs.map(AudioSourceDesc::ssrc).toSet())
    }
}
