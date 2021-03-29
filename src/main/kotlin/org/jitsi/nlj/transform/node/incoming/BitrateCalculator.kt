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

import org.jitsi.config.JitsiConfig
import org.jitsi.metaconfig.config
import org.jitsi.nlj.Event
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.SetMediaSourcesEvent
import org.jitsi.nlj.rtp.VideoRtpPacket
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.node.ObserverNode
import org.jitsi.nlj.util.Bandwidth
import org.jitsi.utils.logging2.cdebug
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.nlj.MediaSourceDesc
import org.jitsi.nlj.findRtpLayerDesc
import org.jitsi.nlj.util.BitrateTracker
import org.jitsi.nlj.util.bytes
import org.jitsi.utils.secs
import org.jitsi.utils.stats.RateTracker
import java.time.Clock
import java.time.Duration

/**
 * When deciding what can be forwarded, we want to know the bitrate of a stream so we can fill the receiver's
 * available bandwidth as much as possible without going over.  This node tracks the incoming bitrate per each
 * individual layer (that is, each forwardable stream taking into account spatial and temporal scalability) and
 * tags the [VideoRtpPacket] with a snapshot of the current estimated bitrate for the encoding to which it belongs
 */
class VideoBitrateCalculator(
    parentLogger: Logger,
    // Screen sharing static content can result in very low packet/bit rates, hence the low threshold.
    activePacketRateThreshold: Int = 1
) : BitrateCalculator("Video bitrate calculator", activePacketRateThreshold) {
    private val logger = createChildLogger(parentLogger)
    private var mediaSourceDescs: Array<MediaSourceDesc> = arrayOf()

    override fun observe(packetInfo: PacketInfo) {
        super.observe(packetInfo)

        val videoRtpPacket: VideoRtpPacket = packetInfo.packet as VideoRtpPacket
        mediaSourceDescs.findRtpLayerDesc(videoRtpPacket)?.let {
            val now = System.currentTimeMillis()
            it.updateBitrate(videoRtpPacket.length.bytes, now)
        }
    }

    override fun handleEvent(event: Event) {
        when (event) {
            is SetMediaSourcesEvent -> {
                mediaSourceDescs = event.mediaSourceDescs.copyOf()
                logger.cdebug { "Video bitrate calculator got media sources:\n${mediaSourceDescs.joinToString()}" }
            }
        }
    }

    override fun trace(f: () -> Unit) = f.invoke()
}

open class BitrateCalculator(
    name: String = "Bitrate calculator",
    /**
     * At what threshold the stream is considered active.
     */
    private val activePacketRateThreshold: Int = 5,
    private val clock: Clock = Clock.systemUTC()
) : ObserverNode(name) {
    private val bitrateTracker = createBitrateTracker()
    private val packetRateTracker = createRateTracker()
    val bitrate: Bandwidth
        get() = bitrateTracker.rate
    val packetRatePps: Long
        get() = packetRateTracker.rate
    private val start = clock.instant()

    /**
     * Keep track of whether the stream is active (has packets at at least [activePacketRateThreshold])
     */
    val active: Boolean
        get() = if (Duration.between(start, clock.instant()) <= GRACE_PERIOD) {
            // In the grace period any received data counts, and we check the bitrate because we can only access the
            // packet rate rounded to an Int.
            bitrate.bps > 0
        } else packetRatePps >= activePacketRateThreshold

    override fun observe(packetInfo: PacketInfo) {
        val now = System.currentTimeMillis()
        bitrateTracker.update(packetInfo.packet.length.bytes, now)
        packetRateTracker.update(1, now)
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

    companion object {
        /**
         * The initial period in which we consider the stream active regardless of packet rate.
         */
        val GRACE_PERIOD = 10.secs

        /**
         * The size of the window over which to calculate average rates.
         */
        val windowSize: Duration by config {
            "jmt.rtp.bitrate-calculator.window-size".from(JitsiConfig.newConfig)
        }

        /**
         * The size of the buckets to use when calculating average rates.
         */
        val bucketSize: Duration by config {
            "jmt.rtp.bitrate-calculator.bucket-size".from(JitsiConfig.newConfig)
        }

        fun createBitrateTracker() = BitrateTracker(windowSize, bucketSize)
        fun createRateTracker() = RateTracker(windowSize, bucketSize)
    }
}
