/*
 * Copyright @ 2020 - Present, 8x8 Inc
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

import org.jitsi.config.JitsiConfig
import org.jitsi.metaconfig.config
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.util.addRatio
import kotlin.random.Random

/**
 * A [Node] which drops packets randomly with a certain probability and a uniform distribution.
 */
class PacketLossNode(val config: PacketLossConfig) : FilterNode("PacketLossNode($config)") {
    private val random = Random(System.currentTimeMillis())
    private var inBurst = false
    private var packetsSeen = 0
    private var currentBurstPacketsDropped = 0

    override fun accept(packetInfo: PacketInfo) = acceptBurst() && random.nextDouble() >= config.uniformRate

    private fun acceptBurst(): Boolean {
        if (!config.burstEnabled) return true

        packetsSeen++
        if (packetsSeen % config.burstInterval == 0) {
            inBurst = true
        }

        return if (inBurst) {
            currentBurstPacketsDropped++
            if (currentBurstPacketsDropped == config.burstSize) {
                inBurst = false
                currentBurstPacketsDropped = 0
            }
            false
        } else true
    }

    override fun trace(f: () -> Unit) { }

    override fun getNodeStats(): NodeStatsBlock {
        return super.getNodeStats().apply {
            addNumber("configured_uniform_rate", config.uniformRate)
            addNumber("configured_burst_size", config.burstSize)
            addNumber("configured_burst_interval", config.burstInterval)
            addRatio("actual_drop_rate", "num_discarded_packets", "num_input_packets")
        }
    }

    override fun getNodeStatsToAggregate(): NodeStatsBlock {
        return super.getNodeStatsToAggregate().apply {
            addRatio("actual_drop_rate", "num_discarded_packets", "num_input_packets")
        }
    }
}

class PacketLossConfig(val base: String) {
    val uniformRate: Double by config {
        "$base.uniform-rate".from(JitsiConfig.newConfig)
    }
    val burstSize: Int by config {
        "$base.burst-size".from(JitsiConfig.newConfig)
    }
    val burstInterval: Int by config {
        "$base.burst-interval".from(JitsiConfig.newConfig)
    }

    val burstEnabled = burstSize > 0 && burstInterval > 0
    val enabled = burstEnabled || uniformRate > 0

    override fun toString() = "uniform rate ${uniformRate * 100}%, burst size $burstSize, burst interval $burstInterval"
}
