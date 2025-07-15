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

class AudioSubscription() {
    private var latestSubscription: ReceiverAudioSubscriptionMessage = ReceiverAudioSubscriptionMessage.All

    // wantedSsrcs is a set of SSRCs that the endpoint wants to receive audio for.
    // This is only managed when the subscription is "Custom".
    private var wantedSsrcs: Set<Long> = emptySet()

    fun updateSubscription(subscription: ReceiverAudioSubscriptionMessage, sources: List<AudioSourceDesc>) {
        latestSubscription = subscription
        when (subscription) {
            is ReceiverAudioSubscriptionMessage.All -> return
            is ReceiverAudioSubscriptionMessage.None -> return
            is ReceiverAudioSubscriptionMessage.Include -> {
                wantedSsrcs = sources.filter { desc ->
                    subscription.list.contains(desc.sourceName)
                }.map(AudioSourceDesc::ssrc).toSet()
            }
            is ReceiverAudioSubscriptionMessage.Exclude -> {
                wantedSsrcs = sources.filterNot { desc ->
                    subscription.list.contains(desc.sourceName)
                }.map(AudioSourceDesc::ssrc).toSet()
            }
        }
    }

    fun isSsrcWanted(ssrc: Long): Boolean = when (latestSubscription) {
        is ReceiverAudioSubscriptionMessage.All -> true
        is ReceiverAudioSubscriptionMessage.None -> false
        else -> wantedSsrcs.contains(ssrc)
    }

    fun onConferenceSourceAdded(descs: Set<AudioSourceDesc>) {
        when (latestSubscription) {
            is ReceiverAudioSubscriptionMessage.All -> return
            is ReceiverAudioSubscriptionMessage.None -> return
            is ReceiverAudioSubscriptionMessage.Include -> {
                val subscription = latestSubscription as ReceiverAudioSubscriptionMessage.Include
                // If the subscription is include, we need to check if the new sources are included in the subscription.
                val newSsrcs = descs.filter { desc ->
                    subscription.list.contains(desc.sourceName)
                }.map(AudioSourceDesc::ssrc).toSet()
                wantedSsrcs = wantedSsrcs.union(newSsrcs)
                return
            }
            is ReceiverAudioSubscriptionMessage.Exclude -> {
                val subscription = latestSubscription as ReceiverAudioSubscriptionMessage.Exclude
                // If the subscription is exclude, we need to check if the new sources are excluded from the subscription.
                val newSsrcs = descs.filterNot { desc ->
                    subscription.list.contains(desc.sourceName)
                }.map(AudioSourceDesc::ssrc).toSet()
                wantedSsrcs = wantedSsrcs.union(newSsrcs)
                return
            }
        }
    }

    fun onConferenceSourceRemoved(descs: Set<AudioSourceDesc>) {
        wantedSsrcs = wantedSsrcs.subtract(descs.map(AudioSourceDesc::ssrc).toSet())
    }
}
