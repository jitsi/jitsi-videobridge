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
package org.jitsi.nlj.rtp

import org.jitsi.nlj.RtpLayerDesc
import org.jitsi.rtp.rtp.RtpPacket

/**
 * A packet which we know contains video, but we've not yet
 * parsed (i.e. we don't know information gained from
 * parsing codec-specific data).
 */
open class VideoRtpPacket @JvmOverloads constructor(
    buffer: ByteArray,
    offset: Int,
    length: Int,
    /** The encoding ID of this packet. */
    var encodingId: Int = RtpLayerDesc.SUSPENDED_ENCODING_ID
) : RtpPacket(buffer, offset, length) {

    open val layerIds: Collection<Int> = listOf(0)

    override fun toString(): String {
        return super.toString() + ", EncID=$encodingId"
    }

    override fun clone(): VideoRtpPacket {
        return VideoRtpPacket(
            cloneBuffer(BYTES_TO_LEAVE_AT_START_OF_PACKET),
            BYTES_TO_LEAVE_AT_START_OF_PACKET,
            length,
            encodingId = encodingId
        )
    }
}
