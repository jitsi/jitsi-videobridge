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
import org.jitsi.rtp.extensions.toHex
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

    fun hasNext(): Boolean {
        return buf.remaining() >= RtcpHeader.SIZE_BYTES
    }

    fun next(): RtcpPacket {
        if (!hasNext()) {
            throw Exception("No more items left on iterator")
        }
        val subBuf = buf.slice()
        try {
            val packet = RtcpPacket.fromBuffer(subBuf)
            // It's important we use the length from the header here instead of
            // packet.size, because packet.size will give us the size the packet
            // will be serialized to, not necessarily the size it was in the given
            // buffer (tcc packets, for example)
            buf.position(buf.position() + (packet.header.length + 1) * 4)
            return packet
        } catch (e: Exception) {
            println("Exception parsing packet in RTCPIterator: $e.  sub buf limit: ${subBuf.limit()}\n" +
                    "Entire packet buffer (limit ${buf.limit()} is:\n" + buf.toHex())
            throw e
        }
    }

    fun getAll(): List<RtcpPacket> {
        val packets = mutableListOf<RtcpPacket>()
        while (hasNext()) {
            packets.add(next())
        }
        return packets
    }
}
