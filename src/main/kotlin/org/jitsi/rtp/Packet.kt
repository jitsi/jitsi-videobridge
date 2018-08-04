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
package org.jitsi.rtp

import org.jitsi.rtp.rtcp.RtcpPacket
import unsigned.toUInt
import java.nio.ByteBuffer

abstract class Packet {
    abstract var buf: ByteBuffer
    val isRtp
        get() = this is RtpPacket
    val isRtcp
        get() = this is RtcpPacket
    abstract val size: Int
    val tags = mutableMapOf<String, Any>()

    companion object {
        private fun getPacketType(buf: ByteBuffer): Int = buf.get(1).toUInt()
        fun parse(buf: ByteBuffer): Packet {
            val packetType = getPacketType(buf)
            return when (packetType) {
                in 200..211 -> RtcpPacket.fromBuffer(buf)
                else -> RtpPacket.fromBuffer(buf)
            }
        }
    }
}

class UnparsedPacket(override var buf: ByteBuffer) : Packet() {
    override val size: Int = buf.limit()
}
