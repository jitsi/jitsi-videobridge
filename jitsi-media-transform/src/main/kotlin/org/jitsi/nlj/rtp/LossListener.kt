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

package org.jitsi.nlj.rtp

import org.jitsi.utils.OrderedJsonObject
import org.jitsi.utils.secs
import org.jitsi.utils.stats.RateTracker

class LossTracker : LossListener {
    private val lostPackets = RateTracker(60.secs, 1.secs)
    private val receivedPackets = RateTracker(60.secs, 1.secs)

    @Synchronized
    override fun packetReceived(previouslyReportedLost: Boolean) {
        receivedPackets.update(1)
        if (previouslyReportedLost) {
            lostPackets.update(-1)
        }
    }

    @Synchronized
    override fun packetLost(numLost: Int) {
        lostPackets.update(numLost.toLong())
    }

    @Synchronized
    fun getSnapshot(): Snapshot {
        return Snapshot(
            lostPackets.getAccumulatedCount(),
            receivedPackets.getAccumulatedCount()
        )
    }

    data class Snapshot(
        val packetsLost: Long,
        val packetsReceived: Long
    ) {
        fun toJson() = OrderedJsonObject().apply {
            put("packets_lost", packetsLost)
            put("packets_received", packetsReceived)
        }
    }
}

/**
 * An interface to report when a packet is received, or is observed to be lost.
 */
/* TODO?  This kind of overlaps with BandwidthEstimator?  But it can be used in cases where we
*   don't have all the information the BandwidthEstimator API needs. */
interface LossListener {
    fun packetReceived(previouslyReportedLost: Boolean)
    fun packetLost(numLost: Int)
}
