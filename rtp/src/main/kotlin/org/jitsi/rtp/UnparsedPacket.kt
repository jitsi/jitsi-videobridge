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

package org.jitsi.rtp

import org.jitsi.rtp.rtp.RtpPacket

class UnparsedPacket(
    buffer: ByteArray,
    offset: Int,
    length: Int
) : Packet(buffer, offset, length) {

    constructor(buffer: ByteArray) : this(buffer, 0, buffer.size)

    /**
     * Note that we leave the same space at the start as for RTP packets, because an [UnparsedPacket]'s buffer
     * might be used directly to create an [RtpPacket].
     */
    override fun clone(): UnparsedPacket =
        UnparsedPacket(
            cloneBuffer(RtpPacket.BYTES_TO_LEAVE_AT_START_OF_PACKET),
            RtpPacket.BYTES_TO_LEAVE_AT_START_OF_PACKET,
            length
        )
}
