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

import org.jitsi.rtp.extensions.subBuffer
import java.nio.ByteBuffer

/**
 * Iterate over all compound RTCP packets in the given
 * buffer.  Each call to next will return an instance
 * of the next RTCP packet or null if there are no
 * more.  Will work correctly even if [buf] does not
 * contain multiple compound RTCP packets.
 */
class RtcpIterator(buf: ByteBuffer) {
    private val buf = buf.slice()

    fun hasNext(): Boolean = buf.remaining() >= RtcpHeader.SIZE_BYTES

    fun next(): RtcpPacket {
        val packet = RtcpPacket.fromBuffer(buf.subBuffer(buf.position()))
        buf.position(buf.position() + packet.size)
        return packet
    }

    fun getAll(): List<RtcpPacket> {
        val packets = mutableListOf<RtcpPacket>()
        while (hasNext()) {
            packets.add(next())
        }
        return packets
    }
}
