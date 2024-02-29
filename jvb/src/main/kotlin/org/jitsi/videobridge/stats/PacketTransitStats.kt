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
package org.jitsi.videobridge.stats

import org.jitsi.config.JitsiConfig
import org.jitsi.metaconfig.config
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.stats.BridgeJitterStats
import org.jitsi.nlj.stats.PacketDelayStats
import org.jitsi.rtp.extensions.looksLikeRtcp
import org.jitsi.rtp.extensions.looksLikeRtp
import org.jitsi.utils.OrderedJsonObject
import org.jitsi.utils.logging2.createLogger
import org.jitsi.utils.stats.BucketStats
import org.jitsi.videobridge.Endpoint
import org.jitsi.videobridge.metrics.VideobridgeMetricsContainer
import java.time.Clock
import java.time.Duration

/**
 * Track how long it takes for all RTP and RTCP packets to make their way through the bridge.
 * [Endpoint] and [Relay] are the 'last place' that is aware of [PacketInfo] in the outgoing
 * chains; they track these stats here.  Since they're static, these members will track the delay
 * for packets going out to all endpoints.
 */
object PacketTransitStats {
    private val jsonEnabled: Boolean by config {
        "videobridge.stats.transit-time.enable-json".from(JitsiConfig.newConfig)
    }
    private val prometheusEnabled: Boolean by config {
        "videobridge.stats.transit-time.enable-prometheus".from(JitsiConfig.newConfig)
    }
    private val jitterEnabled: Boolean by config {
        "videobridge.stats.transit-time.enable-jitter".from(JitsiConfig.newConfig)
    }
    private val enabled = jsonEnabled || prometheusEnabled || jitterEnabled

    private val logger = createLogger()
    private val clock: Clock = Clock.systemUTC()

    init {
        logger.info(
            "Initializing, jsonEnabled=$jsonEnabled, prometheusEnabled=$prometheusEnabled, " +
                "jitterEnabled=$jitterEnabled"
        )
    }

    private val rtpPacketDelayStats = if (jsonEnabled) PacketDelayStats() else null
    private val rtcpPacketDelayStats = if (jsonEnabled) PacketDelayStats() else null
    private val prometheusRtpDelayStats = if (prometheusEnabled) {
        PrometheusPacketDelayStats("rtp_transit_time")
    } else {
        null
    }
    private val prometheusRtcpDelayStats = if (prometheusEnabled) {
        PrometheusPacketDelayStats("rtcp_transit_time")
    } else {
        null
    }

    private val bridgeJitterStats = if (jitterEnabled) BridgeJitterStats() else null

    @JvmStatic
    fun packetSent(packetInfo: PacketInfo) {
        if (!enabled) {
            return
        }

        val delayMs = packetInfo.receivedTime?.let { Duration.between(it, clock.instant()).toMillis() }
        if (packetInfo.packet.looksLikeRtp()) {
            if (delayMs != null) {
                rtpPacketDelayStats?.addDelay(delayMs)
                prometheusRtpDelayStats?.addDelay(delayMs)
                bridgeJitterStats?.packetSent(packetInfo)
            } else {
                rtpPacketDelayStats?.addUnknown()
                prometheusRtpDelayStats?.addUnknown()
            }
        } else if (packetInfo.packet.looksLikeRtcp()) {
            if (delayMs != null) {
                rtcpPacketDelayStats?.addDelay(delayMs)
                prometheusRtcpDelayStats?.addDelay(delayMs)
            } else {
                rtcpPacketDelayStats?.addUnknown()
                prometheusRtcpDelayStats?.addUnknown()
            }
        }
    }

    @JvmStatic
    val statsJson: OrderedJsonObject
        get() {
            val stats = OrderedJsonObject()
            stats["e2e_packet_delay"] = getPacketDelayStats()
            bridgeJitterStats?.let {
                stats["overall_bridge_jitter"] = it.jitter
            }
            return stats
        }

    @JvmStatic
    val bridgeJitter
        get() = bridgeJitterStats?.jitter

    private fun getPacketDelayStats() = OrderedJsonObject().apply {
        rtpPacketDelayStats?.let {
            put("rtp", it.toJson(format = BucketStats.Format.CumulativeRight))
        }
        rtcpPacketDelayStats?.let {
            put("rtcp", it.toJson(format = BucketStats.Format.CumulativeRight))
        }
    }
}

class PrometheusPacketDelayStats(name: String) {
    private val histogram = VideobridgeMetricsContainer.instance.registerHistogram(
        name,
        "Packet delay stats for $name",
        0.0,
        5.0,
        50.0,
        500.0
    )
    private val numPacketsWithoutTimestamps = VideobridgeMetricsContainer.instance.registerCounter(
        "${name}_unknown_delay",
        "Number of packets without an unknown delay ($name)"
    )

    fun addUnknown() {
        numPacketsWithoutTimestamps.inc()
    }
    fun addDelay(delayMs: Long) {
        histogram.histogram.observe(delayMs.toDouble())
    }
}
