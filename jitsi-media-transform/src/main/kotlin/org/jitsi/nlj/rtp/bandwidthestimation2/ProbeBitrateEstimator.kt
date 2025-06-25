/*
 * Copyright @ 2019-present 8x8, Inc
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
@file:Suppress("ktlint:standard:property-naming")

package org.jitsi.nlj.rtp.bandwidthestimation2

import org.jitsi.nlj.util.Bandwidth
import org.jitsi.nlj.util.DataSize
import org.jitsi.nlj.util.bytes
import org.jitsi.nlj.util.min
import org.jitsi.nlj.util.per
import org.jitsi.nlj.util.times
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging.TimeSeriesLogger
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.utils.secs
import java.time.Duration
import java.time.Instant
import java.util.*

/**
 * Basic implementation to estimate bitrate of probes.
 *
 * Based on WebRTC modules/congestion_controller/goog_cc/probe_bitrate_estimator.{h,cc} in
 * WebRTC tag branch-heads/7204 (Chromium 138).
 */
class ProbeBitrateEstimator(
    parentLogger: Logger,
    private val diagnosticContext: DiagnosticContext
) {
    // TODO: pass parent logger in so we have log contexts
    private val logger = createChildLogger(parentLogger)

    private val clusters = TreeMap<Int, AggregatedCluster>()

    private var estimatedDataRate: Bandwidth? = null

    fun handleProbeAndEstimateBitrate(packetFeedback: PacketResult): Bandwidth? {
        val clusterId = packetFeedback.sentPacket.pacingInfo.probeClusterId
        check(clusterId != PacedPacketInfo.kNotAProbe)

        eraseOldClusters(packetFeedback.receiveTime)

        val cluster = clusters.getOrPut(clusterId) { AggregatedCluster() }

        if (packetFeedback.sentPacket.sendTime < cluster.firstSend) {
            cluster.firstSend = packetFeedback.sentPacket.sendTime
        }
        if (packetFeedback.sentPacket.sendTime > cluster.lastSend) {
            cluster.lastSend = packetFeedback.sentPacket.sendTime
            cluster.sizeLastSend = packetFeedback.sentPacket.size
        }
        if (packetFeedback.receiveTime < cluster.firstReceive) {
            cluster.firstReceive = packetFeedback.receiveTime
            cluster.sizeFirstReceive = packetFeedback.sentPacket.size
        }
        if (packetFeedback.receiveTime > cluster.lastReceive) {
            cluster.lastReceive = packetFeedback.receiveTime
        }
        cluster.sizeTotal += packetFeedback.sentPacket.size
        cluster.numProbes += 1

        check(packetFeedback.sentPacket.pacingInfo.probeClusterMinProbes > 0)
        check(packetFeedback.sentPacket.pacingInfo.probeClusterMinBytes > 0)

        val minProbes = packetFeedback.sentPacket.pacingInfo.probeClusterMinProbes *
            kMinReceivedProbesRatio
        val minSize = packetFeedback.sentPacket.pacingInfo.probeClusterMinBytes.bytes *
            kMinReceivedBytesRatio
        if (cluster.numProbes < minProbes || cluster.sizeTotal < minSize) {
            return null
        }

        val sendInterval = Duration.between(cluster.firstSend, cluster.lastSend)
        val receiveInterval = Duration.between(cluster.firstReceive, cluster.lastReceive)
        if (sendInterval <= Duration.ZERO || sendInterval > kMaxProbeInterval ||
            receiveInterval <= Duration.ZERO ||
            receiveInterval > kMaxProbeInterval
        ) {
            logger.info(
                "Probing unsuccessful, invalid send/receive interval " +
                    "[cluster id: $clusterId] [sendInterval: $sendInterval] " +
                    "[receive interval: $receiveInterval]"
            )
            return null
        }
        // Since the `send_interval` does not include the time it takes to actually
        // send the last packet the size of the last sent packet should not be
        // included when calculating the send bitrate.
        check(cluster.sizeTotal >= cluster.sizeLastSend)
        val sendSize = cluster.sizeTotal - cluster.sizeLastSend
        val sendRate = sendSize.per(sendInterval)

        // Since the `receive_interval` does not include the time it takes to
        // actually receive the first packet the size of the first received packet
        // should not be included when calculating the receive bitrate.
        check(cluster.sizeTotal >= cluster.sizeFirstReceive)
        val receiveSize = cluster.sizeTotal - cluster.sizeFirstReceive
        val receiveRate = receiveSize.per(receiveInterval)

        val ratio = receiveRate / sendRate
        if (ratio > kMaxValidRatio) {
            logger.info(
                "Probing unsuccessful, receive/send ratio too high " +
                    "[cluster id: $clusterId] [send: $sendSize/$sendInterval = $sendRate] " +
                    "[receive: $receiveSize/$receiveInterval = $receiveRate] " +
                    "[ratio: $receiveRate / $sendRate = $ratio > kMaxValidRatio ($kMaxValidRatio)]"
            )
            return null
        }
        logger.info(
            "Probing successful [cluster id: $clusterId] " +
                "[send: $sendSize / $sendInterval = $sendRate]" +
                "[receive: $receiveSize / $receiveInterval = $receiveRate]"
        )

        var res = min(sendRate, receiveRate)
        // If we're receiving at significantly lower bitrate than we were sending at,
        // it suggests that we've found the true capacity of the link. In this case,
        // set the target bitrate slightly lower to not immediately overuse.
        if (receiveRate < kMinRatioForUnsaturatedLink * sendRate) {
            check(sendRate > receiveRate)
            res = kTargetUtilizationFraction * receiveRate
        }
        timeSeriesLogger.trace {
            diagnosticContext.makeTimeSeriesPoint("probe_result_success")
                .addField("id", clusterId)
                .addField("bitrate_bps", res.bps)
        }
        estimatedDataRate = res
        return estimatedDataRate
    }

    fun fetchAndResetLastEstimatedBitrate(): Bandwidth? {
        val estimatedDataRate = this.estimatedDataRate
        this.estimatedDataRate = null
        return estimatedDataRate
    }

    private fun eraseOldClusters(timestamp: Instant) {
        clusters.entries.removeIf {
            it.value.lastReceive + kMaxClusterHistory < timestamp
        }
    }

    private data class AggregatedCluster(
        var numProbes: Int = 0,
        var firstSend: Instant = Instant.MAX,
        var lastSend: Instant = Instant.MIN,
        var firstReceive: Instant = Instant.MAX,
        var lastReceive: Instant = Instant.MIN,
        var sizeLastSend: DataSize = DataSize.ZERO,
        var sizeFirstReceive: DataSize = DataSize.ZERO,
        var sizeTotal: DataSize = DataSize.ZERO
    )

    companion object {
        /** The minumum number of probes we need to receive feedback about in percent
         * in order to have a valid estimate. */
        const val kMinReceivedProbesRatio = 0.80

        /** The minumum number of bytes we need to receive feedback about in percent
         * in order to have a valid estimate. */
        const val kMinReceivedBytesRatio = 0.80

        /** The maximum |receive rate| / |send rate| ratio for a valid estimate. */
        const val kMaxValidRatio = 2.0f

        /** The minimum |receive rate| / |send rate| ratio assuming that the link is
         * not saturated, i.e. we assume that we will receive at least
         * kMinRatioForUnsaturatedLink * |send rate| if |send rate| is less than the
         * link capacity. */
        const val kMinRatioForUnsaturatedLink = 0.9

        /* The target utilization of the link. If we know true link capacity
         * we'd like to send at 95% of that rate. */
        const val kTargetUtilizationFraction = 0.95

        /* The maximum time period over which the cluster history is retained.
         * This is also the maximum time period beyond which a probing burst is not
         * expected to last. */
        val kMaxClusterHistory = 1.secs

        /* The maximum time interval between first and the last probe on a cluster
         * on the sender side as well as the receive side. */
        val kMaxProbeInterval = 1.secs

        private val timeSeriesLogger = TimeSeriesLogger.getTimeSeriesLogger(ProbeBitrateEstimator::class.java)
    }
}
