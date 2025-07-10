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
    private val endpointId: String,
    sources: List<AudioSourceDesc> = emptyList()
) {
    private var latestSubscription: ReceiverAudioSubscriptionMessage = ReceiverAudioSubscriptionMessage(
        listOf("*"),
        emptyList()
    )
    // Set every source to wantedSsrcs as initial state is including everything (include=["*"])
    private var wantedSsrcs: Set<Long> = sources.map { it.ssrc }.toSet()

    fun updateSubscription(subscription: ReceiverAudioSubscriptionMessage, sources: List<AudioSourceDesc>) {
        latestSubscription = subscription
        val descs = sources.filter { desc -> desc.owner != endpointId }
        // If exclude is wildcard, exclude everything
        if (subscription.exclude.contains("*")) {
            wantedSsrcs = emptySet()
            return
        }
        // If include is wildcard, put all Ssrcs to wantedSsrcs except ones the exclude list specifies
        if (subscription.include.contains("*")) {
            wantedSsrcs = descs.filterNot { desc ->
                subscription.exclude.contains(desc.sourceName)
            }.map(AudioSourceDesc::ssrc).toSet()
            return
        }
        wantedSsrcs = descs.filter { desc ->
            subscription.include.contains(desc.sourceName) && !subscription.exclude.contains(desc.sourceName)
        }.map(AudioSourceDesc::ssrc).toSet()
    }

    fun isSsrcWanted(ssrc: Long): Boolean =  wantedSsrcs.contains(ssrc)

    fun onConferenceSourceAdded(descs: Set<AudioSourceDesc>) {
        var newSsrcs: Set<Long>
        if (latestSubscription.exclude.contains("*")) {
            newSsrcs = descs.filter{ desc ->
                latestSubscription.include.contains(desc.sourceName)
            }.map(AudioSourceDesc::ssrc).toSet()
            wantedSsrcs = wantedSsrcs.union(newSsrcs)
            return
        }
        if (latestSubscription.include.contains("*")) {
            newSsrcs = descs.filterNot { desc ->
                latestSubscription.exclude.contains(desc.sourceName)
            }.map(AudioSourceDesc::ssrc).toSet()
            wantedSsrcs = wantedSsrcs.union(newSsrcs)
            return
        }
        newSsrcs = descs.filter { desc ->
            latestSubscription.include.contains(desc.sourceName) &&
                    !latestSubscription.exclude.contains(desc.sourceName)
        }.map(AudioSourceDesc::ssrc).toSet()
        wantedSsrcs = wantedSsrcs.union(newSsrcs)
    }

    fun onConferenceSourceRemoved(descs: Set<AudioSourceDesc>) {
        wantedSsrcs = wantedSsrcs.subtract(descs.map(AudioSourceDesc::ssrc).toSet())
    }
}
