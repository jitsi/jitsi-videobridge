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
import org.jitsi.nlj.util.appendLnIndent
import java.util.*

/**
 * Randomly drops packets at a rate of [lossRate].
 */
class PacketLoss(private val lossRate: Double) : Node("Packet loss") {
    private val random = Random()
    private var packetsSeen = 0
    private var packetsDropped = 0
    override fun doProcessPackets(p: List<PacketInfo>) {
        packetsSeen += p.size
        val forwardedPackets = p.filter { random.nextDouble() > lossRate }
        packetsDropped += (p.size - forwardedPackets.size)
        next(forwardedPackets)
    }

    override fun getStats(indent: Int): String {
        return with (StringBuffer()) {
            append(super.getStats(indent))
            appendLnIndent(indent + 2, "configured drop rate: $lossRate, actual drop " +
                    "rate: ${packetsDropped / packetsSeen.toDouble()}")
            toString()
        }
    }
}
