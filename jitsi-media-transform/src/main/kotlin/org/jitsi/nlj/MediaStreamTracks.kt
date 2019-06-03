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

import org.jitsi.nlj.rtp.SsrcAssociationType
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.NodeStatsProducer
import org.jitsi_modified.impl.neomedia.rtp.MediaStreamTrackDesc
import org.jitsi_modified.impl.neomedia.rtp.RTPEncodingDesc

/**
 * Maintains an array of [MediaStreamTrackDesc]. The set method preserves the existing tracks that match one of the new
 * tracks, because [MediaStreamTrackDesc] holds some local state (rate statistics) that we would like to keep. Ideally
 * this state should be out of the track descriptor, in which case this class would be obsolete.
 *
 * @author Boris Grozev
 */
class MediaStreamTracks : NodeStatsProducer {
    private var tracks: Array<MediaStreamTrackDesc> = arrayOf()

    fun setMediaStreamTracks(newTracks: Array<MediaStreamTrackDesc>): Boolean {
        val oldTracks = tracks

        if (oldTracks.isEmpty() || newTracks.isEmpty()) {
            tracks = newTracks
            return oldTracks.size != newTracks.size
        }

        var cntMatched = 0
        val mergedTracks: Array<MediaStreamTrackDesc> = Array(newTracks.size) { i ->
            val newEncoding = newTracks[i].rtpEncodings[0]
            for (j in 0 until oldTracks.size) {
                if (oldTracks[j].matches(newEncoding.primarySSRC)) {
                    cntMatched++
                    // TODO: update the old track instance with the
                    // configuration of the new one.
                    oldTracks[j]
                }
            }
            newTracks[i]
        }

        tracks = mergedTracks
        return oldTracks.size != newTracks.size || cntMatched != oldTracks.size
    }

    fun getMediaStreamTracks(): Array<MediaStreamTrackDesc> = tracks

    override fun getNodeStats(): NodeStatsBlock = NodeStatsBlock("MediaStreamTracks").apply {
        tracks.forEachIndexed { i, track ->
            val trackBlock = NodeStatsBlock("track_$i")
            trackBlock.addString("owner", track.owner)
            track.rtpEncodings.forEach { trackBlock.addBlock(it.getNodeStats()) }
            addBlock(trackBlock)
        }
    }
}

/**
 * Extracts a [NodeStatsBlock] from an [RTPEncodingDesc]. This is here temporarily, once we make [RTPEncodingDesc]
 * a native class of JMT it should go away.
 */
fun RTPEncodingDesc.getNodeStats() = NodeStatsBlock(primarySSRC.toString()).apply {
    addNumber("frameRate", frameRate)
    addNumber("height", height)
    addNumber("index", index)
    addNumber("bitrate_bps", getBitrateBps(System.currentTimeMillis()))
    addBoolean("is_received", isReceived)
    addNumber("rtx_ssrc", getSecondarySsrc(SsrcAssociationType.RTX))
    addNumber("fec_ssrc", getSecondarySsrc(SsrcAssociationType.FEC))
}