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

package org.jitsi.nlj.transform.node.incoming

import org.jitsi.nlj.Event
import org.jitsi.nlj.SetMediaStreamTracksEvent
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.rtp.VideoRtpPacket
import org.jitsi.nlj.transform.node.ObserverNode
import org.jitsi.nlj.util.cdebug
import org.jitsi_modified.impl.neomedia.rtp.MediaStreamTrackDesc
import org.jitsi_modified.impl.neomedia.rtp.RTPEncodingDesc

/**
 * When deciding what can be forwarded, we want to know the bitrate of a stream so we can fill the receiver's
 * available bandwidth as much as possible without going over.  This node tracks the incoming bitrate per each
 * individual encoding (that is, each forwardable stream taking into account spatial and temporal scalability) and
 * tags the [VideoRtpPacket] with a snapshot of the current estimated bitrate for the encoding to which it belongs
 */
class VideoBitrateCalculator : ObserverNode("Video bitrate calculator") {
    private var mediaStreamTrackDescs: Array<MediaStreamTrackDesc> = arrayOf()

    override fun observe(packetInfo: PacketInfo) {
        val videoRtpPacket: VideoRtpPacket = packetInfo.packet as VideoRtpPacket
        findRtpEncodingDesc(videoRtpPacket)?.let {
            val now = System.currentTimeMillis()
            it.updateBitrate(videoRtpPacket.length, now)
        }
    }

    private fun findRtpEncodingDesc(packet: VideoRtpPacket): RTPEncodingDesc? {
        for (track in mediaStreamTrackDescs) {
            track.findRtpEncodingDesc(packet)?.let {
                return it
            }
        }
        return null
    }

    override fun handleEvent(event: Event) {
        when (event) {
            is SetMediaStreamTracksEvent -> {
                mediaStreamTrackDescs = event.mediaStreamTrackDescs.copyOf()
                logger.cdebug { "Video bitrate calculator got media stream tracks:\n$mediaStreamTrackDescs" }
            }
        }
    }
}