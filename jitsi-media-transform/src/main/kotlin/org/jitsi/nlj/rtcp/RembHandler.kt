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

package org.jitsi.nlj.rtcp

import org.jitsi.nlj.rtp.bandwidthestimation.BandwidthEstimator
import org.jitsi.nlj.util.bps
import org.jitsi.nlj.util.createChildLogger
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbRembPacket
import org.jitsi.utils.logging2.Logger
import java.util.concurrent.CopyOnWriteArrayList

class RembHandler(parentLogger: Logger) : RtcpListener {
    private val logger = parentLogger.createChildLogger(RembHandler::class)

    private val bweUpdateListeners: MutableList<BandwidthEstimator.Listener> =
        CopyOnWriteArrayList()

    override fun rtcpPacketReceived(packet: RtcpPacket?, receivedTime: Long) {
        if (packet is RtcpFbRembPacket) {
            logger.debug { "Received REMB packet" }
            onRembPacket(packet)
        }
    }

    fun addListener(bweUpdateListener: BandwidthEstimator.Listener) {
        bweUpdateListeners.add(bweUpdateListener)
    }

    private fun onRembPacket(rembPacket: RtcpFbRembPacket) {
        logger.debug { "Updating bandwidth to ${rembPacket.bitrate.bps}" }
        bweUpdateListeners.forEach { it.bandwidthEstimationChanged(rembPacket.bitrate.bps) }
    }
}
