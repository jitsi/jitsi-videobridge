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

package org.jitsi.nlj.transform.node.incoming

import org.jitsi.nlj.Event
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.SetMediaSourcesEvent
import org.jitsi.nlj.rtp.VideoRtpPacket
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.node.ObserverNode
import org.jitsi.nlj.util.Bandwidth
import org.jitsi.nlj.util.bps
import org.jitsi.utils.logging2.cdebug
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.utils.stats.RateStatistics
import org.jitsi.nlj.MediaSourceDesc
import org.jitsi.nlj.RtpLayerDesc

/**
 * When deciding what can be forwarded, we want to know the bitrate of a stream so we can fill the receiver's
 * available bandwidth as much as possible without going over.  This node tracks the incoming bitrate per each
 * individual layer (that is, each forwardable stream taking into account spatial and temporal scalability) and
 * tags the [VideoRtpPacket] with a snapshot of the current estimated bitrate for the encoding to which it belongs
 */
class VideoBitrateCalculator(
    parentLogger: Logger
) : BitrateCalculator("Video bitrate calculator") {
    private val logger = createChildLogger(parentLogger)
    private var mediaSourceDescs: Array<MediaSourceDesc> = arrayOf()

    override fun observe(packetInfo: PacketInfo) {
        super.observe(packetInfo)

        val videoRtpPacket: VideoRtpPacket = packetInfo.packet as VideoRtpPacket
        findRtpLayerDesc(videoRtpPacket)?.let {
            val now = System.currentTimeMillis()
            it.updateBitrate(videoRtpPacket.length, now)
        }
    }

    private fun findRtpLayerDesc(packet: VideoRtpPacket): RtpLayerDesc? {
        for (source in mediaSourceDescs) {
            source.findRtpLayerDesc(packet)?.let {
                return it
            }
        }
        return null
    }

    override fun handleEvent(event: Event) {
        when (event) {
            is SetMediaSourcesEvent -> {
                mediaSourceDescs = event.mediaSourceDescs.copyOf()
                logger.cdebug { "Video bitrate calculator got media sources:\n$mediaSourceDescs" }
            }
        }
    }

    override fun trace(f: () -> Unit) = f.invoke()
}

open class BitrateCalculator(name: String = "Bitrate calculator") : ObserverNode(name) {
    private val bitrateStatistics = RateStatistics(5000, 8000f)
    private val packetRateStatistics = RateStatistics(5000, 1000f)
    val bitrate: Bandwidth
        get() = bitrateStatistics.rate.bps
    val packetRatePps: Long
        get() = packetRateStatistics.rate

    override fun observe(packetInfo: PacketInfo) {
        val now = System.currentTimeMillis()
        bitrateStatistics.update(packetInfo.packet.length, now)
        packetRateStatistics.update(1, now)
    }

    override fun trace(f: () -> Unit) = f.invoke()

    override fun getNodeStats(): NodeStatsBlock {
        return super.getNodeStats().apply {
            addNumber("bitrate_bps", bitrate.bps)
        }
    }

    override fun getNodeStatsToAggregate(): NodeStatsBlock {
        return super.getNodeStats()
    }
}
