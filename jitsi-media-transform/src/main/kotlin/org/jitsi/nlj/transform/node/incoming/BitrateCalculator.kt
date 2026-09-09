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
import org.jitsi.metaconfig.from
import org.jitsi.metaconfig.optionalconfig
import org.jitsi.nlj.Event
import org.jitsi.nlj.MediaSourceDesc
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.SetMediaSourcesEvent
import org.jitsi.nlj.findRtpLayerDescs
import org.jitsi.nlj.findRtpSource
import org.jitsi.nlj.rtcp.KeyframeBudgetConfig
import org.jitsi.nlj.rtcp.KeyframeCost
import org.jitsi.nlj.rtp.ParsedVideoPacket
import org.jitsi.nlj.rtp.VideoRtpPacket
import org.jitsi.nlj.rtp.bandwidthestimation.BandwidthEstimatorConfig
import org.jitsi.nlj.rtp.bandwidthestimation.BandwidthEstimatorEngine
import org.jitsi.nlj.rtp.bandwidthestimation.GoogleCcEstimator
import org.jitsi.nlj.rtp.bandwidthestimation2.GoogCcTransportCcEngine
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.node.ObserverNode
import org.jitsi.nlj.util.Bandwidth
import org.jitsi.nlj.util.BitrateTracker
import org.jitsi.nlj.util.bits
import org.jitsi.nlj.util.bps
import org.jitsi.nlj.util.bytes
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.cdebug
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.utils.secs
import org.jitsi.utils.stats.RateTracker
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * When deciding what can be forwarded, we want to know the bitrate of a stream so we can fill the receiver's
 * available bandwidth as much as possible without going over.  This node tracks the incoming bitrate per each
 * individual layer (that is, each forwardable stream taking into account spatial and temporal scalability) and
 * tags the [VideoRtpPacket] with a snapshot of the current estimated bitrate for the encoding to which it belongs
 */
class VideoBitrateCalculator(
    parentLogger: Logger,
    // Screen sharing static content can result in very low packet/bit rates, hence the low threshold.
    activePacketRateThreshold: Int = 1,
    clock: Clock = Clock.systemUTC()
) : BitrateCalculator("Video bitrate calculator", activePacketRateThreshold, clock) {
    private val logger = createChildLogger(parentLogger)

    @Volatile
    private var mediaSourceDescs: Array<MediaSourceDesc> = arrayOf()

    /**
     * The keyframe cost tracker for each RTP stream of the media sources, keyed by primary SSRC. Only populated
     * when keyframe budget limiting is enabled, since that is its only consumer.
     */
    private val keyframeCosts = ConcurrentHashMap<Long, KeyframeCostTracker>()

    override fun observe(packetInfo: PacketInfo) {
        super.observe(packetInfo)

        val videoRtpPacket: VideoRtpPacket = packetInfo.packet as VideoRtpPacket
        val now = clock.millis()

        /* Before the layer lookup: the tracker only needs the stream's own properties, and a packet with no known
         * layer, as when an encoding's structure has not been seen yet, is still part of the stream's bitrate. So is a
         * packet of a codec which is not parsed, though no keyframe can be recognized on it. The map is empty unless
         * keyframe budget limiting is enabled, so this costs the disabled path one emptiness check. */
        if (keyframeCosts.isNotEmpty()) {
            keyframeCosts[videoRtpPacket.ssrc]?.observe(
                videoRtpPacket.timestamp,
                videoRtpPacket.length,
                (videoRtpPacket as? ParsedVideoPacket)?.isKeyframe ?: false,
                now
            )
        }

        val layerDescs = mediaSourceDescs.findRtpLayerDescs(videoRtpPacket)

        if (layerDescs.isEmpty()) {
            logger.warn("No layer found for packet $videoRtpPacket")
            return
        }

        layerDescs.forEach {
            if (it.updateBitrate(videoRtpPacket.length.bytes, now)) {
                /* When a layer is started when it was previously inactive,
                 * we want to recalculate bandwidth allocation.
                 */
                packetInfo.layeringChanged = true
            }
        }
    }

    /**
     * The measured cost of a keyframe for the source which has [ssrc] as one of its SSRCs: the mean size of one
     * keyframe, summed over the encodings the sender is currently sending, and the total bitrate of those encodings.
     * Both are measured from the same packets, per SSRC, so they cover the same streams whatever the codec's layer
     * structure. An encoding is included only once it has been observed long enough for its bitrate to be meaningful
     * and a keyframe has been observed on it, so that both sides of the ratio cover the same encodings. Returns null
     * if keyframe budget limiting is disabled, the source is unknown, or no encoding qualifies.
     */
    fun getKeyframeCost(ssrc: Long): KeyframeCost? {
        if (!KeyframeBudgetConfig.enabled) {
            return null
        }
        val source = mediaSourceDescs.findRtpSource(ssrc) ?: return null
        val now = clock.millis()
        var keyframeBits = 0L
        var sourceBitrate = 0.bps
        var keyframeBitrate = 0.bps
        source.rtpEncodings.forEach { encoding ->
            keyframeCosts[encoding.primarySSRC]?.let { tracker ->
                val meanKeyframeSize = tracker.getMeanKeyframeSize(now)
                val encodingBitrate = tracker.getStreamBitrate(now)
                /* An encoding which is not currently being sent has no bitrate and generates no keyframes. */
                if (meanKeyframeSize != null && tracker.isWarm(now) && encodingBitrate.bps > 0) {
                    keyframeBits += meanKeyframeSize.bits
                    sourceBitrate += encodingBitrate
                    keyframeBitrate += tracker.getKeyframeBitrate(now)
                }
            }
        }
        if (keyframeBits <= 0L) {
            return null
        }
        return KeyframeCost(keyframeBits.bits, sourceBitrate, keyframeBitrate)
    }

    override fun getNodeStats(): NodeStatsBlock = super.getNodeStats().apply {
        addBoolean("keyframe_budget_enabled", KeyframeBudgetConfig.enabled)
        val now = clock.millis()
        keyframeCosts.forEach { (ssrc, tracker) ->
            addJson("keyframe_tracker_$ssrc", tracker.debugState(now))
        }
        mediaSourceDescs.forEach { source ->
            getKeyframeCost(source.primarySSRC)?.let { cost ->
                addJson("keyframe_cost_${source.primarySSRC}", cost.toJson())
            }
        }
    }

    override fun handleEvent(event: Event) {
        when (event) {
            is SetMediaSourcesEvent -> {
                mediaSourceDescs = event.mediaSourceDescs.copyOf()
                val ssrcs: Set<Long> = mediaSourceDescs.flatMap { source ->
                    source.rtpEncodings.map { it.primarySSRC }
                }.toSet()
                keyframeCosts.keys.retainAll(ssrcs)
                if (KeyframeBudgetConfig.enabled) {
                    ssrcs.forEach { keyframeCosts.computeIfAbsent(it) { KeyframeCostTracker() } }
                }
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
    protected val clock: Clock = Clock.systemUTC()
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
        } else {
            packetRatePps >= activePacketRateThreshold
        }

    override fun observe(packetInfo: PacketInfo) {
        val now = clock.millis()
        bitrateTracker.update(packetInfo.packet.length.bytes, now)
        packetRateTracker.update(1, now)
    }

    override fun trace(f: () -> Unit) = f.invoke()

    override fun getNodeStats(): NodeStatsBlock = super.getNodeStats().apply {
        addNumber("bitrate_bps", bitrate.bps)
        addNumber("packet_rate_pps", packetRatePps)
        addBoolean("active", active)
    }

    override fun getNodeStatsToAggregate(): NodeStatsBlock = super.getNodeStats()

    companion object {
        /**
         * The initial period in which we consider the stream active regardless of packet rate.
         */
        val GRACE_PERIOD = 10.secs

        private val _windowSize: Duration? by optionalconfig {
            "jmt.rtp.bitrate-calculator.window-size".from(JitsiConfig.newConfig)
        }

        /** The default bitrate calculator window size if not set in jvb.conf, based on the BWE algorithm in use.*/
        private fun defaultWindowSize(): Duration = when (BandwidthEstimatorConfig.engine) {
            BandwidthEstimatorEngine.GoogleCc -> GoogleCcEstimator.defaultRateTrackerWindowSize
            BandwidthEstimatorEngine.GoogleCc2 -> GoogCcTransportCcEngine.defaultRateTrackerWindowSize
        }

        /**
         * The size of the window over which to calculate average rates.
         */
        val windowSize: Duration
            get() = _windowSize ?: defaultWindowSize()

        private val _bucketSize: Duration? by optionalconfig {
            "jmt.rtp.bitrate-calculator.bucket-size".from(JitsiConfig.newConfig)
        }

        /** The default bitrate calculator bucket size if not set in jvb.conf, based on the BWE algorithm in use.*/
        private fun defaultBucketSize(): Duration = when (BandwidthEstimatorConfig.engine) {
            BandwidthEstimatorEngine.GoogleCc -> GoogleCcEstimator.defaultRateTrackerBucketSize
            BandwidthEstimatorEngine.GoogleCc2 -> GoogCcTransportCcEngine.defaultRateTrackerBucketSize
        }

        /**
         * The size of the buckets to use when calculating average rates.
         */
        val bucketSize
            get() = _bucketSize ?: defaultBucketSize()

        /**
         * The size of the window over which to calculate average rates for sources whose bitrate is bursty, i.e.
         * screen sharing. See [createSmoothedBitrateTracker].
         */
        val smoothedWindowSize: Duration by config(
            "jmt.rtp.bitrate-calculator.smoothed-window-size".from(JitsiConfig.newConfig)
        )

        /** The size of the buckets to use with [smoothedWindowSize]. This must divide it evenly. */
        val smoothedBucketSize: Duration by config(
            "jmt.rtp.bitrate-calculator.smoothed-bucket-size".from(JitsiConfig.newConfig)
        )

        fun createBitrateTracker() = BitrateTracker(windowSize, bucketSize)

        /**
         * Creates a tracker with a longer window than [createBitrateTracker], for use with sources whose bitrate is
         * bursty. A screen sharing encoder is allowed to accumulate about a second of its target bitrate while the
         * screen is static and then spend it on a single frame, and libwebrtc's screen sharing rate control tolerates
         * intervals of up to 2.75 seconds between frames, so a rate measured over [windowSize] (which matches the
         * bandwidth estimator's window) is not representative of the bandwidth such a source actually needs.
         */
        fun createSmoothedBitrateTracker() = BitrateTracker(smoothedWindowSize, smoothedBucketSize)

        fun createRateTracker() = RateTracker(windowSize, bucketSize)
    }
}
