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
package org.jitsi.rtp.rtcp

import org.jitsi.rtp.Packet
import org.jitsi.rtp.rtcp.rtcpfb.RtcpFbPacket
import java.nio.ByteBuffer

abstract class RtcpPacket : Packet() {
    abstract var header: RtcpHeader

    companion object {
        fun fromBuffer(buf: ByteBuffer): RtcpPacket {
            val packetType = RtcpHeader.getPacketType(buf)
            return when (packetType) {
                //TODO: 202 = SDES
                RtcpSrPacket.PT -> RtcpSrPacket(buf)
                RtcpRrPacket.PT -> RtcpRrPacket(buf)
                205, 206 -> RtcpFbPacket.fromBuffer(buf)
                else -> throw Exception("Unsupported RTCP packet type $packetType")
            }
        }

        /**
         * [buf] should be a buffer whose start represents the start of the
         * RTCP packet (i.e. the start of the RTCP header)
         */
        protected fun setHeader(buf: ByteBuffer, header: RtcpHeader) {
            buf.put(buf)
        }
    }

    override fun toString(): String {
        return with (StringBuffer()) {
            appendln("RTCP packet")
            append(header.toString())
            toString()
        }
    }
}
