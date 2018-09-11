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

import org.jitsi.impl.neomedia.rtp.FrameDesc
import org.jitsi.impl.neomedia.rtp.RTPEncodingDesc
import org.jitsi.rtp.Packet
import org.jitsi.rtp.RtpHeader
import org.jitsi.rtp.RtpPacket
import org.jitsi.rtp.extensions.clone
import java.nio.ByteBuffer

class VideoRtpPacket : RtpPacket {
    var isKeyFrame: Boolean = false
    var frameDesc: FrameDesc? = null
    var trackEncodings: Array<RTPEncodingDesc>? = null
//    var rtpEncodingDesc: RTPEncodingDesc? = null

    constructor(buf: ByteBuffer) : super(buf)

    constructor(
        header: RtpHeader = RtpHeader(),
        payload: ByteBuffer = ByteBuffer.allocate(0)
    ) : super(header, payload)

    override fun clone(): Packet {
        val clone = VideoRtpPacket(getBuffer().clone())
        clone.isKeyFrame = isKeyFrame
        clone.frameDesc = frameDesc
        clone.trackEncodings = trackEncodings

        return clone
    }
}
