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

package org.jitsi.rtp.rtcp.rtcpfb

/**
 * Represents an RTCP feedback packet whose packet types falls in the valid range
 * (according to the spec), but isn't currently implemented/supported.
 */
class UnsupportedRtcpFbPacket(
    buf: ByteArray,
    offset: Int,
    packetLengthBytes: Int
) : RtcpFbPacket(buf, offset, packetLengthBytes) {
    override fun clone(): UnsupportedRtcpFbPacket =
        UnsupportedRtcpFbPacket(cloneBuffer(0), 0, length)
}
