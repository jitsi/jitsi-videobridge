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

package org.jitsi.nlj.rtp.codec

import org.jitsi.nlj.MediaSourceDesc
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.RtpEncodingDesc
import org.jitsi.nlj.RtpLayerDesc
import org.jitsi.nlj.findRtpLayerDesc
import org.jitsi.nlj.rtp.VideoRtpPacket

/**
 * Abstract interface to the codec-specific parsing of a video source.
 *
 * Typically this will set layer information as interpreted from received packets,
 * and verify stream consistency.
 */
abstract class VideoCodecParser(
    var sources: Array<MediaSourceDesc>
) {
    abstract fun parse(packetInfo: PacketInfo)

    protected fun findRtpEncodingDesc(packet: VideoRtpPacket): RtpEncodingDesc? {
        for (source in sources) {
            source.findRtpEncodingDesc(packet.ssrc)?.let {
                return it
            }
        }
        return null
    }

    protected fun findSourceDescAndRtpEncodingDesc(packet: VideoRtpPacket): Pair<MediaSourceDesc, RtpEncodingDesc>? {
        for (source in sources) {
            source.findRtpEncodingDesc(packet.ssrc)?.let {
                return Pair(source, it)
            }
        }
        return null
    }

    protected fun findRtpLayerDesc(packet: VideoRtpPacket): RtpLayerDesc? = sources.findRtpLayerDesc(packet)
}
