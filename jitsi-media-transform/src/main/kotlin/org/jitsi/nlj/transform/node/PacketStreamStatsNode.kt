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
package org.jitsi.nlj.transform.node

import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.stats.PacketStreamStats

/**
 * A [Node] which keeps track of the basic statistics for a stream of packets (packet and bit rates)
 *
 * @author Boris Grozev
 */
class PacketStreamStatsNode(private val packetStreamStats: PacketStreamStats = PacketStreamStats()) :
    ObserverNode("PacketStreamStats") {

    override fun observe(packetInfo: PacketInfo) {
        packetStreamStats.update(packetInfo.packet.length)
    }

    fun snapshot() = packetStreamStats.snapshot()

    fun getBitrate() = snapshot().bitrate

    /**
     * Creates a new [Node] instance which shares the same [packetStreamStats]. Useful when we want to add nodes to
     * different branches of a [Node] tree.
     */
    fun createNewNode() = PacketStreamStatsNode(packetStreamStats)
}
