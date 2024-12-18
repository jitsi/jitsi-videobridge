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
package org.jitsi.nlj.rtp

import org.jitsi.nlj.rtcp.RtcpListener
import org.jitsi.nlj.util.Bandwidth
import org.jitsi.nlj.util.DataSize
import org.jitsi.rtp.rtcp.RtcpPacket
import java.time.Duration
import java.time.Instant

abstract class TransportCcEngine : RtcpListener {
    protected val lossListeners = mutableListOf<LossListener>()

    /**
     * Called when an RTP sender has a new round-trip time estimate.
     */
    abstract fun onRttUpdate(rtt: Duration)

    abstract override fun rtcpPacketReceived(rtcpPacket: RtcpPacket, receivedTime: Instant?)

    abstract fun mediaPacketTagged(tccSeqNum: Int, length: DataSize)

    abstract fun mediaPacketSent(tccSeqNum: Int, length: DataSize)

    abstract fun getStatistics(): StatisticsSnapshot

    /**
     * Adds a loss listener to be notified about packet arrival and loss reports.
     * @param listener
     */
    @Synchronized
    fun addLossListener(listener: LossListener) {
        lossListeners.add(listener)
    }

    /**
     * Removes a loss listener.
     * @param listener
     */
    @Synchronized
    fun removeLossListener(listener: LossListener) {
        lossListeners.remove(listener)
    }

    abstract fun addBandwidthListener(listener: TransportCcEngine.BandwidthListener)

    abstract fun removeBandwidthListener(listener: TransportCcEngine.BandwidthListener)

    abstract class StatisticsSnapshot {
        abstract fun toJson(): Map<*, *>
    }

    interface BandwidthListener {
        fun bandwidthEstimationChanged(newValue: Bandwidth)
    }
}
