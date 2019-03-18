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

import org.jitsi_modified.impl.neomedia.rtp.MediaStreamTrackDesc

/**
 * Maintains an array of [MediaStreamTrackDesc]. The set method preserves the existing tracks that match one of the new
 * tracks, because [MediaStreamTrackDesc] holds some local state (rate statistics) that we would like to keep. Ideally
 * this state should be out of the track descriptor, in which case this class would be obsolete.
 *
 * @author Boris Grozev
 */
class MediaStreamTracks {
    private var tracks: Array<MediaStreamTrackDesc> = arrayOf()

    fun setMediaStreamTracks(newTracks: Array<MediaStreamTrackDesc>): Boolean {
        val oldTracks = tracks;

        if (oldTracks.isEmpty() || newTracks.isEmpty())
        {
            tracks = newTracks;
            return oldTracks.size != newTracks.size;
        }

        var cntMatched = 0
        val mergedTracks: Array<MediaStreamTrackDesc> = Array(newTracks.size) { i ->
            val newEncoding = newTracks[i].rtpEncodings[0]
            for (j in 0 until oldTracks.size)
            {
                if (oldTracks[j] != null
                    && oldTracks[j].matches(newEncoding.primarySSRC))
                {
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
}