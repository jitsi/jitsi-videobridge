/*
 * Copyright @ 2018 Atlassian Pty Ltd
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
package org.jitsi.nlj.rtp

import org.jitsi.impl.neomedia.rtp.RTPEncodingDesc
import org.jitsi.rtp.rtp.RtpHeader
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.rtp.util.ByteBufferUtils
import java.nio.ByteBuffer

open class VideoRtpPacket(
    header: RtpHeader = RtpHeader(),
    payload: ByteBuffer = ByteBufferUtils.EMPTY_BUFFER,
    backingBuffer: ByteBuffer? = null
) : RtpPacket(header, payload, backingBuffer) {
    var isKeyFrame: Boolean = false
    var trackEncodings: Array<RTPEncodingDesc>? = null
    /**
     * The estimated bitrate of the encoding to which this packet belongs
     */
    var bitrateSnapshot: Long? = null

    override fun clone(): VideoRtpPacket {
        val clone = VideoRtpPacket(header.clone(), cloneMutablePayload())
        clone.isKeyFrame = isKeyFrame
        clone.trackEncodings = trackEncodings

        return clone
    }

    override fun toString(): String = with(StringBuffer()) {
        append(super.toString())
        appendln("isKeyFrame? $isKeyFrame")
        appendln("bitrate snapshot: $bitrateSnapshot bps")
        toString()
    }
}
