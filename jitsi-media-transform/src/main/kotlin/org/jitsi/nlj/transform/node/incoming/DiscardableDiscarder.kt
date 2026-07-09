/*
 * Copyright @ 2019 - present 8x8, Inc.
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
package org.jitsi.nlj.transform.node.incoming

import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.rtp.ResumableStreamRewriter
import org.jitsi.nlj.transform.node.TransformerNode
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.utils.LRUCache
import java.util.Collections

/**
 * Discards RTP packets which have shouldDiscard set to true, masking their loss
 * in the RTP sequence numbers of RTP packets.
 */
class DiscardableDiscarder(name: String, val keepHistory: Boolean) : TransformerNode(name) {
    /**
     * Per-SSRC rewriters, keyed on the RTP SSRC. Bounded with an LRU to limit the number of SSRCs tracked.
     */
    val rewriters: MutableMap<Long, ResumableStreamRewriter> =
        Collections.synchronizedMap(LRUCache(MAX_SSRCS, true))

    override fun transform(packetInfo: PacketInfo): PacketInfo? {
        val packet = packetInfo.packet as? RtpPacket ?: return packetInfo
        rewriters.computeIfAbsent(packet.ssrc) { ResumableStreamRewriter(keepHistory) }
            .rewriteRtp(!packetInfo.shouldDiscard, packet)

        return if (packetInfo.shouldDiscard) {
            null
        } else {
            packetInfo
        }
    }

    override fun trace(f: () -> Unit) = f.invoke()

    companion object {
        /** The maximum number of SSRCs to track rewriters for. */
        const val MAX_SSRCS = 64
    }
}
