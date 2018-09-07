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
package org.jitsi.nlj.transform.node

import org.jitsi.impl.neomedia.rtp.RawPacketCache
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.util.toRawPacket
import org.jitsi_modified.impl.neomedia.transform.CachingTransformer

class PacketCache : Node("Packet cache") {
    /**
     * [CachingTransformer] has separate caches for incoming and outgoing because
     * it was used for both directions in the old pipeline (transform and reverse
     * transform).  Now the incoming and outgoing pipelines are separate, so we'll
     * have a separate caching transformer in each location, so [PacketCache] can be
     * a single class which can handle caching in either direction, so we'll always
     * just use [CachingTransformer#transform].
     */
    private val cachingTransformer = CachingTransformer(hashCode())

    init {
        cachingTransformer.setEnabled(true)
    }

    override fun doProcessPackets(p: List<PacketInfo>) {
        p.forEach { packetInfo ->
            cachingTransformer.transform(packetInfo.packet.toRawPacket())
        }
        next(p)
    }

    fun getPacketCache(): RawPacketCache = cachingTransformer.outgoingRawPacketCache
}
