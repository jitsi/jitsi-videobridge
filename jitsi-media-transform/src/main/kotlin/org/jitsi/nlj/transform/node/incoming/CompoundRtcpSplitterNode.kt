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

package org.jitsi.nlj.transform.node.incoming

import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.transform.node.MultipleOutputTransformerNode
import org.jitsi.nlj.util.BufferPool
import org.jitsi.rtp.NewRawPacket
import org.jitsi.rtp.rtcp.CompoundRtcpSplitter

//TODO: this isn't ideal, as we copy each RTCP packet into its own buffer, but forwarding them as a single
// buffer makes it difficult to know when we're done with the entire buffer (and therefore when it can
// be returned to the pool).  Instead we could copy only the ones we'll forward in
// rtcp termination, or, maybe don't even copy then and we can rely on the final handler to return those
// that are forwarded.
class CompoundRtcpSplitterNode : MultipleOutputTransformerNode("Compound RTCP splitter") {
    override fun transform(packetInfo: PacketInfo): List<PacketInfo> {
        val splitRtcpPackets = CompoundRtcpSplitter.getAll(packetInfo.packetAs<NewRawPacket>())
                .map { PacketInfo(it, packetInfo.timeline.clone()).apply { receivedTime = packetInfo.receivedTime } }

        // We've cloned each of the compound RTCP packets into their own buffer, so we can return the original
        packetDiscarded(packetInfo)

        return splitRtcpPackets
    }
}
