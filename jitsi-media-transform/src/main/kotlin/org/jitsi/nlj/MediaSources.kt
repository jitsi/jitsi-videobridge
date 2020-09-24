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

package org.jitsi.nlj

import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.NodeStatsProducer

/**
 * Maintains an array of [MediaSourceDesc]. The set method preserves the existing sources that match one of the new
 * sources, because [MediaSourceDesc] holds some local state (rate statistics) that we would like to keep. Ideally
 * this state should be out of the source descriptor, in which case this class would be obsolete.
 *
 * @author Boris Grozev
 */
class MediaSources : NodeStatsProducer {
    private var sources: Array<MediaSourceDesc> = arrayOf()

    fun setMediaSources(newSources: Array<MediaSourceDesc>): Boolean {
        val oldSources = sources

        if (oldSources.isEmpty() || newSources.isEmpty()) {
            sources = newSources
            return oldSources.size != newSources.size
        }

        var cntMatched = 0
        val mergedSources: Array<MediaSourceDesc> = Array(newSources.size) { i ->
            val newPrimarySSRC = newSources[i].primarySSRC
            for (j in 0 until oldSources.size) {
                if (oldSources[j].matches(newPrimarySSRC)) {
                    cntMatched++
                    // NOTE: we deliberately do not update the old source instance
                    // with the encodings of the new one.  Values set on the
                    // source are more likely to be correct than ones generated
                    // from signaling about the source.  (Revisit this if we
                    // encounter a scenario where this isn't true...)
                    return@Array oldSources[j]
                }
            }
            newSources[i]
        }

        sources = mergedSources
        return oldSources.size != newSources.size || cntMatched != oldSources.size
    }

    fun getMediaSources(): Array<MediaSourceDesc> = sources

    override fun getNodeStats(): NodeStatsBlock = NodeStatsBlock("MediaStreamSources").apply {
        sources.forEachIndexed { i, source ->
            val sourceBlock = NodeStatsBlock("source_$i")
            source.owner?.let { sourceBlock.addString("owner", it) }
            source.rtpEncodings.forEach { sourceBlock.addBlock(it.getNodeStats()) }

            addBlock(sourceBlock)
        }
    }
}
