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
package org.jitsi.nlj.transform.node.incoming

import org.jitsi.nlj.Event
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.SetMediaStreamTracksEvent
import org.jitsi.nlj.format.Vp8PayloadType
import org.jitsi.nlj.rtp.VideoRtpPacket
import org.jitsi.nlj.rtp.codec.vp8.Vp8Packet
import org.jitsi.nlj.transform.node.TransformerNode
import org.jitsi.nlj.util.ReadOnlyStreamInformationStore
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi_modified.impl.neomedia.rtp.MediaStreamTrackDesc
import org.jitsi_modified.impl.neomedia.rtp.RTPEncodingDesc

/**
 * Parse video packets at a codec level and set appropriate meta-information
 */
class VideoParser(
    private val streamInformationStore: ReadOnlyStreamInformationStore
) : TransformerNode("Video parser") {
    private var tracks: Array<MediaStreamTrackDesc> = arrayOf()

    // TODO: things we want to detect here:
    // does this packet belong to a keyframe?
    // does this packet represent the start of a frame?
    // does this packet represent the end of a frame?
    override fun transform(packetInfo: PacketInfo): PacketInfo? {
        val rtpPacket = packetInfo.packetAs<RtpPacket>()
        streamInformationStore.rtpPayloadTypes[rtpPacket.payloadType.toByte()]?.let { payloadType ->
            val videoRtpPacket = when (payloadType) {
                is Vp8PayloadType -> {
                    val vp8Packet = rtpPacket.toOtherType(::Vp8Packet)
                    findRtpEncodingDesc(vp8Packet)?.let {
                        vp8Packet.qualityIndex = it.index
                    }
                    vp8Packet
                }
                else -> rtpPacket
            }
            packetInfo.packet = videoRtpPacket
            packetInfo.resetPayloadVerification()
        } ?: run {
            logger.error("Unrecognized video payload type ${rtpPacket.payloadType}, cannot parse video information")
        }
        return packetInfo
    }

    private fun findRtpEncodingDesc(packet: VideoRtpPacket): RTPEncodingDesc? {
        for (track in tracks) {
            track.findRtpEncodingDesc(packet)?.let {
                return it
            }
        }
        return null
    }

    override fun handleEvent(event: Event) {
        when (event) {
            is SetMediaStreamTracksEvent -> {
                tracks = event.mediaStreamTrackDescs
            }
        }
        super.handleEvent(event)
    }
}
