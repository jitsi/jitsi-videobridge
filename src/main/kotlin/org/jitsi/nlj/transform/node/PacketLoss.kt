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
import java.util.Random

/**
 * Randomly drops packets at a rate of [lossRate].
 */
class PacketLoss(private val lossRate: Double) : FilterNode("Packet loss") {
    private val random = Random()
    private var packetsSeen = 0
    private var packetsDropped = 0
    override fun accept(packetInfo: PacketInfo): Boolean {
        packetsSeen ++
        if (random.nextDouble() > lossRate) {
            packetsDropped++
            return false
        } else {
            return true
        }
    }

    override fun getNodeStats(): NodeStatsBlock {
        val parentStats = super.getNodeStats()
        return NodeStatsBlock(name).apply {
            addAll(parentStats)
            addStat("configured drop rate: $lossRate, actual drop " +
                    "rate: ${packetsDropped / packetsSeen.toDouble()}")
        }
    }
}

class BurstPacketLoss(
    private val burstSize: Int = 30,
    private val burstInterval: Int = 3000
) : FilterNode("Burst packet loss") {
    private var packetsSeen = 0
    private var totalPacketsDropped = 0
    private var inBurst = false
    private var currentBurstPacketsDropped = 0

    override fun accept(packetInfo: PacketInfo): Boolean {
        packetsSeen++
        if (packetsSeen % burstInterval == 0) {
            inBurst = true
        }

        return if (inBurst) {
            totalPacketsDropped++
            currentBurstPacketsDropped++
            if (currentBurstPacketsDropped == burstSize) {
                inBurst = false
            }
            false
        } else {
            true
        }
    }
}
