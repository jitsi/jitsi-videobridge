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

import org.jitsi.rtp.extensions.bytearray.cloneFromPool
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi_modified.impl.neomedia.rtp.RTPEncodingDesc

//open class VideoRtpPacket(
//    header: RtpHeader = RtpHeader(),
//    backingBuffer: ByteBuffer = BufferPool.getBuffer(1500)
//) : RtpPacket(header, backingBuffer) {
//    var isKeyFrame: Boolean = false
//    var trackEncodings: Array<RTPEncodingDesc>? = null
//    /**
//     * The estimated bitrate of the encoding to which this packet belongs
//     */
//    var bitrateSnapshot: Long? = null
//
//    override fun clone(): VideoRtpPacket {
//        val clone = VideoRtpPacket(header.clone(), cloneBackingBuffer())
//        clone.isKeyFrame = isKeyFrame
//        clone.trackEncodings = trackEncodings
//
//        return clone
//    }
//
//    override fun cloneWithBackingBuffer(backingBuffer: ByteBuffer): VideoRtpPacket {
//        backingBuffer.put(header.sizeBytes, payload)
//        backingBuffer.limit(header.sizeBytes + payload.limit())
//        val clone = VideoRtpPacket(header.clone(), backingBuffer)
//        clone.isKeyFrame = isKeyFrame
//        clone.trackEncodings = trackEncodings
//
//        return clone
//    }
//
//    override fun toString(): String = with(StringBuffer()) {
//        append(super.toString())
//        appendln("isKeyFrame? $isKeyFrame")
//        appendln("bitrate snapshot: $bitrateSnapshot bps")
//        toString()
//    }
//}

open class VideoRtpPacket(
    data: ByteArray,
    offset: Int,
    length: Int
) : RtpPacket(data, offset, length) {
    /**
     * The estimated bitrate of the encoding to which this packet belongs
     */
    var bitrateSnapshot: Long? = null
    var isKeyframe = false
    var trackEncodings: Array<RTPEncodingDesc>? = null

    override fun clone(): VideoRtpPacket {
        val clone = VideoRtpPacket(buffer.cloneFromPool(), offset, length)
        clone.isKeyframe = isKeyframe
        return clone
    }
}
