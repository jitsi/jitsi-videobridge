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

package org.jitsi.rtp.rtp

import org.jitsi.rtp.NewRawPacket

/**
 *
 * https://tools.ietf.org/html/rfc3550#section-5.1
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |V=2|P|X|  CC   |M|     PT      |       sequence number         |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                           timestamp                           |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |           synchronization source (SSRC) identifier            |
 * +=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+
 * |            contributing source (CSRC) identifiers             |
 * |                             ....                              |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |              ...extensions (if present)...                    |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                   payload                                     |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 */
open class RtpPacket(
    buffer: ByteArray,
    offset: Int,
    length: Int
) : NewRawPacket(buffer, offset, length) {

    var version: Int
        get() = RtpHeader.getVersion(buffer, offset)
        set(value) = RtpHeader.setVersion(buffer, offset, value)

    var hasPadding: Boolean
        get() = RtpHeader.hasPadding(buffer, offset)
        set(value) = RtpHeader.setPadding(buffer, offset, value)

    var hasExtensions: Boolean
        get() = RtpHeader.hasExtensions(buffer, offset)
        set(value) = RtpHeader.setHasExtensions(buffer, offset, value)

    val csrcCount: Int
        get() = RtpHeader.getCsrcCount(buffer, offset)

    var isMarked: Boolean
        get() = RtpHeader.getMarker(buffer, offset)
        set(value) = RtpHeader.setMarker(buffer, offset, value)

    var payloadType: Int
        get() = RtpHeader.getPayloadType(buffer, offset)
        set(value) = RtpHeader.setPayloadType(buffer, offset, value)

    var sequenceNumber: Int
        get() = RtpHeader.getSequenceNumber(buffer, offset)
        set(value) = RtpHeader.setSequenceNumber(buffer, offset, value)

    var timestamp: Long
        get() = RtpHeader.getTimestamp(buffer, offset)
        set(value) = RtpHeader.setTimestamp(buffer, offset, value)

    var ssrc: Long
        get() = RtpHeader.getSsrc(buffer, offset)
        set(value) = RtpHeader.setSsrc(buffer, offset, value)

    val csrcs: List<Long>
        get() = RtpHeader.getCsrcs(buffer, offset)

    override fun toString(): String = with (StringBuilder()) {
        append("RtpPacket: ")
        append("PT=$payloadType")
        append(", Ssrc=$ssrc")
        append(", SeqNum=$sequenceNumber")
        append(", M=$isMarked")
        append(", X=$hasExtensions")
        append(", Ts=$timestamp")
        toString()
    }
}