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
@file:Suppress("ktlint:standard:property-naming")

package org.jitsi.nlj.rtp.bandwidthestimation2

import org.jitsi.nlj.util.Bandwidth
import org.jitsi.nlj.util.DataSize
import org.jitsi.nlj.util.bps
import org.jitsi.nlj.util.per
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.ccfb.EcnMarking
import org.jitsi.utils.MAX_DURATION
import org.jitsi.utils.NEVER
import org.jitsi.utils.isFinite
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.ms
import org.jitsi.utils.toDoubleMillis
import java.time.Duration
import java.time.Instant

/** Common network types used for bandwidth estimation,
 * based on WebRTC api/transport/network_types.{h,cc} in
 * WebRTC tag branch-heads/7204 (Chromium 138).
 */

/* Configuration */
/** Represents constraints and rates related to the currently enabled streams.
 * This is used as input to the congestion controller via the StreamsConfig
 * struct.
 */
class BitrateAllocationLimits(
    /** The total minimum send bitrate required by all sending streams. */
    val minAllocatableRate: Bandwidth = Bandwidth.ZERO,
    /** The total maximum allocatable bitrate for all currently available streams. */
    val maxAllocatableRate: Bandwidth = Bandwidth.ZERO,
    /** The max bitrate to use for padding. The sum of the per-stream max padding
     * rate. */
    val maxPaddingRate: Bandwidth = Bandwidth.ZERO
)

/**
 * Use StreamsConfig for information about streams that is required for specific
 * adjustments to the algorithms in network controllers. Especially useful
 * for experiments.
 */
class StreamsConfig(
    val atTime: Instant = Instant.MAX,
    val requestsAlrProbing: Boolean? = null,
    // If `enable_repeated_initial_probing` is set to true, Probes are sent
    // periodically every 1s during the first 5s after the network becomes
    // available. The probes ignores max_total_allocated_bitrate.
    val enableRepeatedInitialProbing: Boolean? = null,

    val pacingFactor: Double? = null,

    // TODO(srte): Use BitrateAllocationLimits here.
    val minTotalAllocatedBitrate: Bandwidth? = null,
    val maxPaddingRate: Bandwidth? = null,
    val maxTotalAllocatedBitrate: Bandwidth? = null
)

class TargetRateConstraints(
    val atTime: Instant = Instant.MAX,
    val minDataRate: Bandwidth? = null,
    val maxDataRate: Bandwidth? = null,
    // The initial bandwidth estimate to base target rate on. This should be used
    // as the basis for initial OnTargetTransferRate and OnPacerConfig callbacks.
    var startingRate: Bandwidth? = null
)

/** Send side information */

class NetworkAvailability(
    val atTime: Instant = Instant.MAX,
    val networkAvailable: Boolean = false
)

class NetworkRouteChange(
    val atTime: Instant = Instant.MAX,
    // The TargetRateConstraints are set here so they can be changed synchronously
    // when network route changes.
    val constraints: TargetRateConstraints = TargetRateConstraints()
)

/**
 * Information about a paced packet
 */
data class PacedPacketInfo(
    val probeClusterId: Int = kNotAProbe,
    val probeClusterMinProbes: Int = -1,
    val probeClusterMinBytes: Int = -1,
    var sendBitrate: Bandwidth = 0.bps
) {
    // TODO(srte): Move probing info to a separate, optional struct.
    var probeClusterBytesSent = 0

    companion object {
        const val kNotAProbe = -1
    }
}

/**
 * A sent packet
 */
data class SentPacket(
    var sendTime: Instant = NEVER,
    /** Size of packet with overhead up to IP layer. */
    var size: DataSize = DataSize.ZERO,
    /** Size of preceeding packets that are not part of feedback */
    var priorUnackedData: DataSize = DataSize.ZERO,
    /** Probe cluster id and parameters including bitrate, number of packets and
     number of bytes. */
    var pacingInfo: PacedPacketInfo = PacedPacketInfo(),
    /** True if the packet is an audio packet, false for video, padding, RTX, etc. */
    var audio: Boolean = false,
    /** Transport independent sequence number, any tracked packet should have a
     sequence number that is unique over the whole call and increasing by 1 for
     each packet. */
    var sequenceNumber: Long = 0,
    /** Tracked data in flight when the packet was sent, excluding unacked data. */
    var dataInFlight: DataSize = DataSize.ZERO
)

/** Transport level feedback */

class RemoteBitrateReport(
    val receiveTime: Instant = Instant.MAX,
    val bandwidth: Bandwidth = Bandwidth.INFINITY
)

class RoundTripTimeUpdate(
    val receiveTime: Instant = Instant.MAX,
    val roundTripTime: Duration = MAX_DURATION,
    val smoothed: Boolean = false
)

class TransportLossReport(
    val receiveTime: Instant = Instant.MAX,
    val startTime: Instant = Instant.MAX,
    val endTime: Instant = Instant.MAX,
    val packetsLostDelta: Long = 0,
    val packetsReceivedDelta: Long = 0
)

/**
 * The result of packet feedback
 */
class PacketResult(
    var sentPacket: SentPacket = SentPacket(),
    var receiveTime: Instant = NEVER,
    var ecn: EcnMarking = EcnMarking.kNotEct,
    // `rtp_packet_info` is only set if the feedback is related to a RTP packet.
    var rtpPacketInfo: RtpPacketInfo? = null,
    // Jitsi extension: Whether a packet was previously reported lost
    var previouslyReportedLost: Boolean = false
) {
    data class RtpPacketInfo(
        val ssrc: Long = 0,
        val rtpSequenceNumber: Int = 0,
        val isRetransmission: Boolean = false
    )

    fun isReceived() = receiveTime.isFinite()

    fun copy(): PacketResult = PacketResult(sentPacket.copy(), receiveTime, ecn, rtpPacketInfo, previouslyReportedLost)
}

/**
 * Summary of transport packets feedback
 */
class TransportPacketsFeedback {
    var feedbackTime: Instant = NEVER
    var dataInFlight: DataSize = DataSize.ZERO
    var packetFeedbacks: MutableList<PacketResult> = ArrayList()
    var transportSupportsEcn: Boolean = false

    /** Arrival times for messages without send times information */
    val sendlessArrivalTimes = ArrayList<Instant>()

    fun receivedWithSendInfo(): List<PacketResult> {
        return packetFeedbacks.filter { it.isReceived() }
    }

    fun lostWithSendInfo(): List<PacketResult> {
        return packetFeedbacks.filterNot { it.isReceived() }
    }

    fun packetsWithFeedback(): List<PacketResult> {
        return packetFeedbacks
    }

    fun sortedByReceiveTime(): List<PacketResult> {
        return receivedWithSendInfo().sortedBy { it.receiveTime }
    }
}

// Network estimation
class NetworkEstimate {
    var atTime = Instant.MAX

    // Deprecated, use TargetTransferRate::target_rate instead.
    var bandwidth = Bandwidth.INFINITY
    var roundTripTime = MAX_DURATION
    var bwePeriod = MAX_DURATION

    var lossRateRatio = 0.0f

    /* Jitsi local */
    override fun toString(): String {
        return "atTime $atTime: " +
            "bandwidth $bandwidth, rtt $roundTripTime, bwePeriod $bwePeriod, lossRateRatio $lossRateRatio"
    }
}

class PacerConfig {
    var atTime = Instant.MAX

    // Pacer should send at most data_window data over time_window duration.
    var dataWindow = DataSize.INFINITY
    var timeWindow = MAX_DURATION

    // Pacer should send at least pad_window data over time_window duration.
    var padWindow = DataSize.ZERO

    fun dataRate() = dataWindow.per(timeWindow)
    fun padRate() = padWindow.per(timeWindow)

    /* Jitsi Local */
    override fun toString(): String {
        return "Data rate ${dataRate()} ($dataWindow per $timeWindow), " +
            "pad rate ${padRate()} ($padWindow per $timeWindow)"
    }
}

data class ProbeClusterConfig(
    var atTime: Instant = Instant.MAX,
    var targetDataRate: Bandwidth = Bandwidth.ZERO,

    // Duration of a probe.
    var targetDuration: Duration = Duration.ZERO,

    // Delta time between sent bursts of packets during probe.
    var minProbeDelta: Duration = 2.ms,
    var targetProbeCount: Int = 0,
    var id: Int = 0
) {
    /* Jitsi local */
    override fun toString(): String {
        return "atTime $atTime: ID=$id: DataRate $targetDataRate Duration $targetDuration " +
            "ProbeDelta $minProbeDelta ProbeCount $targetProbeCount"
    }
}

class TargetTransferRate {
    var atTime = Instant.MAX

    // The estimate on which the target rate is based on.
    var networkEstimate = NetworkEstimate()
    var targetRate = Bandwidth.ZERO
    var stableTargetRate = Bandwidth.ZERO
    var cwndReduceRatio = 0.0

    /* Jitsi local */
    override fun toString(): String {
        return "atTime $atTime: networkEstimate {$networkEstimate}, " +
            "targetRate $targetRate, stableTargetRate $stableTargetRate, cwndReduceRatio $cwndReduceRatio"
    }
}

// Contains updates of network controller comand state. Using nullables to
// indicate whether a member has been updated. The array of probe clusters
// should be used to send out probes if not empty.
open class NetworkControlUpdate(
    open val congestionWindow: DataSize? = null,
    open val pacerConfig: PacerConfig? = null,
    open val probeClusterConfigs: List<ProbeClusterConfig> = listOf(),
    open val targetRate: TargetTransferRate? = null
) {
    /* Jitsi local */
    fun isEmpty(): Boolean {
        return congestionWindow == null && pacerConfig == null && probeClusterConfigs.isEmpty() && targetRate == null
    }

    fun isNotEmpty() = !isEmpty()

    val atTime: Instant?
        get() = targetRate?.atTime ?: probeClusterConfigs.firstOrNull()?.atTime ?: pacerConfig?.atTime

    fun addToTimeSeriesPoint(point: DiagnosticContext.TimeSeriesPoint) {
        targetRate?.let {
            point.addField("target_rate_bps", it.targetRate.bps)
            point.addField("stable_target_rate_bps", it.stableTargetRate.bps)
            point.addField("cwnd_reduce_ratio", it.cwndReduceRatio)
            point.addField("rtt_ms", it.networkEstimate.roundTripTime.toDoubleMillis())
            point.addField("bwe_period_ms", it.networkEstimate.bwePeriod.toDoubleMillis())
            point.addField("loss_rate_ratio", it.networkEstimate.lossRateRatio)
        }
        congestionWindow?.let {
            point.addField("congestion_window_bytes", it.bytes)
        }
        pacerConfig?.let {
            point.addField("pacer_data_rate_bps", it.dataRate().bps)
            point.addField("pacer_pad_rate_bps", it.padRate().bps)
        }
        probeClusterConfigs.forEachIndexed { i, it ->
            point.addField("probe_cluster_" + i + "_id", it.id)
            point.addField("probe_cluster_" + i + "_data_rate_bps", it.targetDataRate.bps)
            point.addField("probe_cluster_" + i + "_duration_ms", it.targetDuration.toDoubleMillis())
            point.addField("probe_cluster_" + i + "_delta_ms", it.minProbeDelta.toDoubleMillis())
            point.addField("probe_cluster_" + i + "_count", it.targetProbeCount)
        }
    }
}

class MutableNetworkControlUpdate(
    override var congestionWindow: DataSize? = null,
    override var pacerConfig: PacerConfig? = null,
    override var probeClusterConfigs: MutableList<ProbeClusterConfig> = mutableListOf(),
    override var targetRate: TargetTransferRate? = null
) : NetworkControlUpdate()

/** Process control */
class ProcessInterval(
    val atTime: Instant = Instant.MAX,
    val pacerQueue: DataSize? = null
)
