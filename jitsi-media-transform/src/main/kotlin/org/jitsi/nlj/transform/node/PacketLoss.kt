/*
 * Copyright @ 2018 - Present, 8x8 Inc
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

import java.util.Random
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.util.addRatio

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
        return super.getNodeStats().apply {
            addNumber("configured_drop_rate", lossRate)
            addNumber("packetsDropped", packetsDropped)
            addNumber("packetsSeen", packetsSeen)
            addRatio("actual_drop_rate", "packetsDropped", "packetsSeen")
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
