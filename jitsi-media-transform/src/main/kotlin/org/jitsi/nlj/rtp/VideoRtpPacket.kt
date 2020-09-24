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

import org.jitsi.rtp.rtp.RtpPacket

/**
 * A packet which we know contains video, but we've not yet
 * parsed (i.e. we don't know information gained from
 * parsing codec-specific data).
 */
open class VideoRtpPacket protected constructor(
    buffer: ByteArray,
    offset: Int,
    length: Int,
    qualityIndex: Int?
) : RtpPacket(buffer, offset, length) {

    constructor(
        buffer: ByteArray,
        offset: Int,
        length: Int
    ) : this(buffer, offset, length,
        qualityIndex = null
    )

    /** The index of this packet relative to its source's RtpLayers. */
    var qualityIndex: Int = qualityIndex ?: -1

    open val layerId = 0

    override fun clone(): VideoRtpPacket {
        return VideoRtpPacket(
            cloneBuffer(BYTES_TO_LEAVE_AT_START_OF_PACKET),
            BYTES_TO_LEAVE_AT_START_OF_PACKET,
            length,
            qualityIndex = qualityIndex
        )
    }
}
