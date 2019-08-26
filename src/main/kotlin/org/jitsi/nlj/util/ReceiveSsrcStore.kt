/*
 * Copyright @ 2018 - Present, 8x8 Inc
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
package org.jitsi.nlj.util

import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.NodeStatsProducer
import org.jitsi.utils.MediaType
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

class ReceiveSsrcStore(
    private val ssrcAssociationStore: SsrcAssociationStore
) : NodeStatsProducer {
    // NOTE: to enable efficient lookup for various use cases, we store
    // different 'views' of the receive SSRCs in multiple data structures
    // (below).  Updates to these data structures do not happen atomically,
    // so it's possible the view across them may be inconsistent while changes
    // are propagating.  We don't currently have code that cares about this,
    // so it's done this way to avoid having to synchronize updates and access
    // across all of them.
    /**
     * All signaled receive SSRCs
     */
    val receiveSsrcs: MutableSet<Long> = CopyOnWriteArraySet()

    /**
     * All receive SSRCs indexed by their media type
     */
    private val receiveSsrcsByMediaType: MutableMap<MediaType, MutableSet<Long>> =
        ConcurrentHashMap()

    /**
     * 'Primary' receive media SSRCs (excludes things like RTX)
     */
    val primaryMediaSsrcs: MutableSet<Long> = CopyOnWriteArraySet()

    /**
     * 'Primary' *video* SSRCs
     */
    val primaryVideoSsrcs: MutableSet<Long> = CopyOnWriteArraySet()

    init {
        ssrcAssociationStore.onAssociation(this::onSsrcAssociation)
    }

    fun addReceiveSsrc(ssrc: Long, mediaType: MediaType) {
        receiveSsrcsByMediaType.getOrPut(mediaType, { mutableSetOf() }).add(ssrc)
        receiveSsrcs.add(ssrc)
        if (ssrcAssociationStore.isPrimarySsrc(ssrc)) {
            primaryMediaSsrcs.add(ssrc)
            if (mediaType == MediaType.VIDEO) {
                primaryVideoSsrcs.add(ssrc)
            }
        }
    }

    fun removeReceiveSsrc(ssrc: Long) {
        receiveSsrcs.remove(ssrc)
        receiveSsrcsByMediaType.values.forEach { it.remove(ssrc) }
        primaryMediaSsrcs.remove(ssrc)
        primaryVideoSsrcs.remove(ssrc)
    }

    /**
     * Handle new [SsrcAssociation]s
     */
    private fun onSsrcAssociation(ssrcAssociation: SsrcAssociation) {
        // Secondary SSRCs in associations shouldn't be considered
        // primary media or video SSRCs
        primaryVideoSsrcs.remove(ssrcAssociation.secondarySsrc)
        primaryMediaSsrcs.remove(ssrcAssociation.secondarySsrc)
    }

    override fun getNodeStats(): NodeStatsBlock = NodeStatsBlock("Receive SSRC store").apply {
        addString("Receive SSRCs", receiveSsrcsByMediaType.toString())
        addString("Primary media SSRCs", primaryMediaSsrcs.toString())
        addString("Primary video SSRCs", primaryVideoSsrcs.toString())
    }
}