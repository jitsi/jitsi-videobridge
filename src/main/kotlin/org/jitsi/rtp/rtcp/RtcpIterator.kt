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
        //TODO: we should be able to get rid of this now that packets
        // correctly start parsing from the buf's current position
        val subBuf = buf.slice()
        try {
            val startPosition = buf.position()
            val packet = RtcpPacket.parse(subBuf)
            // We continue to set this, even though all packets should now leave
            // the buffer's position at where their data ended, just in case
            // we have some parsing errors (specifically, i'm worried about us
            // not parsing potentially multiple FCI blocks for some packets)
            buf.position(startPosition + (packet.header.length + 1) * 4)
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
