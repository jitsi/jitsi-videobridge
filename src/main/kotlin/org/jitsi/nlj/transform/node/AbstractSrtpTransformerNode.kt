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

import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi_modified.impl.neomedia.transform.SinglePacketTransformer

abstract class AbstractSrtpTransformerNode(name: String) : MultipleOutputTransformerNode(name) {
    /**
     * The [SinglePacketTransformer] instance to use for srtp transform/reverseTransforms.
     * Note that this is private on purpose: subclasses should use the transformer given to
     * them in [doTransform] (which will never be null, whereas this may be null).
     */
    private var transformer: SinglePacketTransformer? = null

    fun setTransformer(t: SinglePacketTransformer?) {
        transformer = t
    }
    /**
     * We'll cache all packets that come through before [transformer]
     * gets set so that we don't lose any packets at the beginning
     * (likely a keyframe)
     */
    private var cachedPackets = mutableListOf<PacketInfo>()

    /**
     * The function which subclasses should implement to do the actual srtp/srtcp encryption/decryption
     */
    abstract fun doTransform(pkts: List<PacketInfo>, transformer: SinglePacketTransformer): List<PacketInfo>

    private var firstPacketReceivedTimestamp = -1L
    private var firstPacketForwardedTimestamp = -1L
    /**
     * How many packets, total, we put into the cache while waiting for the transformer
     * (this includes packets which may have been dropped due to the cache filling up)
     */
    private var numCachedPackets = 0

    override fun transform(packetInfo: PacketInfo): List<PacketInfo> {
        if (firstPacketReceivedTimestamp == -1L) {
            firstPacketReceivedTimestamp = System.currentTimeMillis()
        }
        transformer?.let {
            if (firstPacketForwardedTimestamp == -1L) {
                firstPacketForwardedTimestamp = System.currentTimeMillis()
            }
            val outPackets = mutableListOf<PacketInfo>()
            outPackets.addAll(doTransform(cachedPackets, it))
            cachedPackets.clear()
            outPackets.addAll(doTransform(listOf(packetInfo), it))
            return outPackets
        } ?: run {
            numCachedPackets++
            cachedPackets.add(packetInfo)
            while (cachedPackets.size > 1024) {
                cachedPackets.removeAt(0)?.let {
                    packetDiscarded(it)
                }
            }
            return emptyList()
        }
    }

    override fun getNodeStats(): NodeStatsBlock {
        val parentStats = super.getNodeStats()
        return NodeStatsBlock(name).apply {
            addAll(parentStats)
            addStat("num cached packets: ${cachedPackets.size}")
            val timeBetweenReceivedAndForwarded = firstPacketForwardedTimestamp - firstPacketReceivedTimestamp
            addStat("time between first packet received and first forwarded: " +
                    "$timeBetweenReceivedAndForwarded ms")
        }
    }
}
