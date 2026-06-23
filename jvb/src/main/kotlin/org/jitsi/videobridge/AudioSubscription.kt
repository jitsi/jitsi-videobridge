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

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import org.jitsi.videobridge.message.ReceiverAudioSubscriptionMessage
import org.jitsi.videobridge.relay.AudioSourceDesc

class AudioSubscription {
    /** Whether to receive sources not named in [include]/[exclude] (synthetic sources excepted -- see below). */
    private var all: Boolean = false

    /** The source names being explicitly included / excluded, and the SSRCs they currently resolve to. */
    private var include: Set<String> = emptySet()
    private var exclude: Set<String> = emptySet()
    private var includedSsrcs: Set<Long> = emptySet()
    private var excludedSsrcs: Set<Long> = emptySet()

    private val lock: Any = Any()

    fun updateSubscription(subscription: ReceiverAudioSubscriptionMessage, sources: List<AudioSourceDesc>) =
        synchronized(lock) {
            all = subscription.all
            include = subscription.include.toSet()
            exclude = subscription.exclude.toSet()
            includedSsrcs = ssrcsForNames(include, sources)
            excludedSsrcs = ssrcsForNames(exclude, sources)
        }

    private fun ssrcsForNames(names: Set<String>, sources: Collection<AudioSourceDesc>): Set<Long> =
        sources.filter { it.sourceName != null && it.sourceName in names }.map(AudioSourceDesc::ssrc).toSet()

    fun isSsrcWanted(ssrc: Long): Boolean = when {
        excludedSsrcs.contains(ssrc) -> false
        includedSsrcs.contains(ssrc) -> true
        else -> all
    }

    /**
     * Whether the given SSRC is explicitly wanted, i.e. named in the [include] list (and not excluded). Unlike
     * [isSsrcWanted] this is not satisfied by [all], so it gates synthetic sources, which are forwarded only on an
     * explicit subscription.
     */
    fun isExplicitlyWanted(ssrc: Long): Boolean = includedSsrcs.contains(ssrc) && !excludedSsrcs.contains(ssrc)

    fun onConferenceSourceAdded(descs: Set<AudioSourceDesc>) = synchronized(lock) {
        includedSsrcs = includedSsrcs.union(ssrcsForNames(include, descs))
        excludedSsrcs = excludedSsrcs.union(ssrcsForNames(exclude, descs))
    }

    fun onConferenceSourceRemoved(descs: Set<AudioSourceDesc>) = synchronized(lock) {
        val removed = descs.map(AudioSourceDesc::ssrc).toSet()
        includedSsrcs = includedSsrcs.subtract(removed)
        excludedSsrcs = excludedSsrcs.subtract(removed)
    }

    fun debugState(): ObjectNode = JsonNodeFactory.instance.objectNode().apply {
        synchronized(lock) {
            put("all", all)
            putArray("include").apply { include.forEach { add(it) } }
            putArray("exclude").apply { exclude.forEach { add(it) } }
            putArray("included_ssrcs").apply { includedSsrcs.forEach { add(it) } }
            putArray("excluded_ssrcs").apply { excludedSsrcs.forEach { add(it) } }
        }
    }
}
