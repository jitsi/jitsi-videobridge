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

package org.jitsi.rtp.rtcp

import org.jitsi.rtp.Packet

class CompoundRtcpSplitter {
    companion object {
        // Get all the contained RTCP packets from compoundRtcpPacket.  Each
        // packet will have been given its own buffer.
        fun getAll(compoundRtcpPacket: Packet): List<RtcpPacket> {
            var bytesRemaining = compoundRtcpPacket.length
            var currOffset = compoundRtcpPacket.offset
            val rtcpPackets = mutableListOf<RtcpPacket>()
            while (bytesRemaining > RtcpHeader.SIZE_BYTES) {
                val rtcpPacket = RtcpPacket.parse(compoundRtcpPacket.buffer, currOffset)
                rtcpPackets.add(rtcpPacket.clone())
                currOffset += rtcpPacket.length
                bytesRemaining -= rtcpPacket.length
            }
            return rtcpPackets
        }
    }
}