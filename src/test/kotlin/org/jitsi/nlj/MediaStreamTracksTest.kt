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

import io.kotlintest.IsolationMode
import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec
import org.jitsi_modified.impl.neomedia.rtp.MediaStreamTrackDesc
import org.jitsi_modified.impl.neomedia.rtp.RTPEncodingDesc

class MediaStreamTracksTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf
    init {
        val mediaStreamTracks = MediaStreamTracks()

        val trackA = createTrack(1000)
        val trackA2 = createTrack(1000)
        val trackB = createTrack(2000, 2001)
        val trackB2 = createTrack(2000, 2001)
        val trackC = createTrack(3000, 3001)

        var changed = mediaStreamTracks.setMediaStreamTracks(arrayOf(trackA, trackB))
        "Setting initially  must signal a change." {
            changed shouldBe true

            val newTracks = mediaStreamTracks.getMediaStreamTracks()
            newTracks[0] shouldBe trackA
            newTracks[1] shouldBe trackB
        }

        "Setting the same tracks must not signal a change." {
            changed = mediaStreamTracks.setMediaStreamTracks(arrayOf(trackA, trackB))
            changed shouldBe false

            val newTracks = mediaStreamTracks.getMediaStreamTracks()
            newTracks[0] shouldBe trackA
            newTracks[1] shouldBe trackB
        }

        "Setting matching tracks must not signal a change, or change the saved tracks" {
            changed = mediaStreamTracks.setMediaStreamTracks(arrayOf(trackA2, trackB2))
            changed shouldBe false

            val newTracks = mediaStreamTracks.getMediaStreamTracks()
            newTracks[0] shouldBe trackA
            newTracks[1] shouldBe trackB
        }

        "Adding a new track must signal a change, but not change the previous tracks" {
            changed = mediaStreamTracks.setMediaStreamTracks(arrayOf(trackA2, trackB2, trackC))
            changed shouldBe true

            val newTracks = mediaStreamTracks.getMediaStreamTracks()
            newTracks[0] shouldBe trackA
            newTracks[1] shouldBe trackB
            newTracks[2] shouldBe trackC
        }

        "Removing a track must signal a change, but not change the previous track" {
            changed = mediaStreamTracks.setMediaStreamTracks(arrayOf(trackA))
            changed shouldBe true

            val newTracks = mediaStreamTracks.getMediaStreamTracks()
            newTracks[0] shouldBe trackA
        }

        "Adding and removing must signal a change, but not change the previous track" {
            changed = mediaStreamTracks.setMediaStreamTracks(arrayOf(trackA2, trackC))
            changed shouldBe true

            val newTracks = mediaStreamTracks.getMediaStreamTracks()
            newTracks[0] shouldBe trackA
            newTracks[1] shouldBe trackC
        }
    }

    companion object {
        fun createTrack(vararg ssrcs: Long): MediaStreamTrackDesc {
            val encodings = Array<RTPEncodingDesc?>(ssrcs.size) { null }
            val track = MediaStreamTrackDesc(encodings)
            ssrcs.forEachIndexed { i, ssrc ->
                encodings[i] = RTPEncodingDesc(track, ssrc)
            }

            return track
        }
    }
}