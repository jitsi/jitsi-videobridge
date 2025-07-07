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
@file:Suppress("ktlint:standard:enum-entry-name-case")

package org.jitsi.nlj.rtp.bandwidthestimation2

import org.jitsi.nlj.util.DataSize
import org.jitsi.nlj.util.bps
import org.jitsi.nlj.util.bytes
import org.jitsi.nlj.util.div
import org.jitsi.nlj.util.min
import org.jitsi.nlj.util.times
import org.jitsi.utils.isFinite
import org.jitsi.utils.isInfinite
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.utils.min
import org.jitsi.utils.ms
import org.jitsi.utils.secs
import java.time.Duration
import java.time.Instant

/** Bitrate prober,
 * based on WebRTC modules/pacing/bitrate_prober.{h,cc} in
 * WebRTC tag branch-heads/7204 (Chromium 138).
 */

private val kProbeClusterTimeout = 5.secs
private val kMaxPendingProbeClusters = 5

data class BitrateProberConfig(
    var maxProbeDelay: Duration = 20.ms,
    var minPacketSize: DataSize = 200.bytes,
    var allowStartProbingImmediately: Boolean = false
)

class BitrateProber(
    parentLogger: Logger,
    configIn: BitrateProberConfig = BitrateProberConfig()
) {
    private val logger = createChildLogger(parentLogger)

    fun setEnabled(enable: Boolean) {
        if (enable) {
            if (probingState == ProbingState.kDisabled) {
                probingState = ProbingState.kInactive
                logger.info("Bandwidth probing enabled, set to inactive")
            }
        } else {
            probingState = ProbingState.kDisabled
            logger.info("Bandwidth probing disabled")
        }
    }

    fun setAllowProbeWithoutMediaPacket(allow: Boolean) {
        config.allowStartProbingImmediately = allow
        maybeSetActiveState(DataSize.ZERO)
    }

    // Returns true if the prober is in a probing session, i.e., it currently
    // wants packets to be sent out according to the time returned by
    // TimeUntilNextProbe().
    fun isProbing(): Boolean = probingState == ProbingState.kActive

    // Initializes a new probing session if the prober is allowed to probe. Does
    // not initialize the prober unless the packet size is large enough to probe
    // with.
    fun onIncomingPacket(packetSize: DataSize) {
        maybeSetActiveState(packetSize)
    }

    // Create a cluster used to probe.
    fun createProbeCluster(clusterConfig: ProbeClusterConfig) {
        check(probingState != ProbingState.kDisabled)
        check(clusterConfig.minProbeDelta > Duration.ZERO)

        while (clusters.isNotEmpty() && (
                Duration.between(clusters.first().requestedAt, clusterConfig.atTime) > kProbeClusterTimeout ||
                    clusters.size > kMaxPendingProbeClusters
                )
        ) {
            clusters.removeFirst()
        }

        val cluster = ProbeCluster(
            requestedAt = clusterConfig.atTime,
            paceInfo = PacedPacketInfo(
                probeClusterMinProbes = clusterConfig.targetProbeCount,
                probeClusterMinBytes = (clusterConfig.targetDataRate * clusterConfig.targetDuration).bytes.toInt(),
                probeClusterId = clusterConfig.id
            ),
            minProbeDelta = clusterConfig.minProbeDelta
        )
        check(cluster.paceInfo.probeClusterMinBytes >= 0)
        cluster.paceInfo.sendBitrate = clusterConfig.targetDataRate
        clusters.addLast(cluster)

        maybeSetActiveState(packetSize = DataSize.ZERO)

        check(probingState == ProbingState.kActive || probingState == ProbingState.kInactive)

        logger.info {
            "Probe cluster (bitrate_bps:min bytes:min packets): (" +
                "${cluster.paceInfo.sendBitrate}:${cluster.paceInfo.probeClusterMinBytes}:" +
                "${cluster.paceInfo.probeClusterMinProbes}, $probingState)"
        }
    }

    // Returns the time at which the next probe should be sent to get accurate
    // probing. If probing is not desired at this time, [Instant.MAX]
    // will be returned.
    // TODO(bugs.webrtc.org/11780): Remove `now` argument when old mode is gone.
    fun nextProbeTime(now: Instant): Instant {
        // Probing is not active or probing is already complete.
        if (probingState != ProbingState.kActive || clusters.isEmpty()) {
            return Instant.MAX
        }
        return nextProbeTime
    }

    // Information about the current probing cluster.
    fun currentCluster(now: Instant): PacedPacketInfo? {
        if (clusters.isEmpty() || probingState != ProbingState.kActive) {
            return null
        }

        if (nextProbeTime.isFinite() &&
            Duration.between(nextProbeTime, now) > config.maxProbeDelay
        ) {
            logger.warn(
                "Probe delay too high (next:$nextProbeTime, now:$now)" +
                    "discarding probe cluster."
            )
            clusters.removeFirst()
            if (clusters.isEmpty()) {
                probingState = ProbingState.kInactive
                return null
            }
        }

        val info = clusters.first().paceInfo.copy()
        info.probeClusterBytesSent = clusters.first().sentBytes
        return info
    }

    // Returns the minimum number of bytes that the prober recommends for
    // the next probe, or zero if not probing. A probe can consist of multiple
    // packets that are sent back to back.
    fun recommendedMinProbeSize(): DataSize {
        if (clusters.isEmpty()) {
            return DataSize.ZERO
        }
        val sendRate = clusters.first().paceInfo.sendBitrate
        return sendRate * clusters.first().minProbeDelta
    }

    // Called to report to the prober that a probe has been sent. In case of
    // multiple packets per probe, this call would be made at the end of sending
    // the last packet in probe. `size` is the total size of all packets in probe.
    fun probeSent(now: Instant, size: DataSize) {
        check(probingState == ProbingState.kActive)
        check(size != DataSize.ZERO)

        if (clusters.isNotEmpty()) {
            val cluster = clusters.first()
            if (cluster.sentProbes == 0) {
                check(cluster.startedAt.isInfinite())
                cluster.startedAt = now
            }
            cluster.sentBytes += size.bytes.toInt()
            cluster.sentProbes += 1
            nextProbeTime = calculateNextProbeTime(cluster)
            if (cluster.sentBytes >= cluster.paceInfo.probeClusterMinBytes &&
                cluster.sentProbes >= cluster.paceInfo.probeClusterMinProbes
            ) {
                clusters.removeFirst()
            }
            if (clusters.isEmpty()) {
                probingState = ProbingState.kInactive
            }
        }
    }

    private enum class ProbingState {
        // Probing will not be triggered in this state at all times.
        kDisabled,

        // Probing is enabled and ready to trigger on the first packet arrival if
        // there is a probe cluster.
        kInactive,

        // Probe cluster is filled with the set of data rates to be probed and
        // probes are being sent.
        kActive,
    }

    // A probe cluster consists of a set of probes. Each probe in turn can be
    // divided into a number of packets to accommodate the MTU on the network.
    private data class ProbeCluster(
        val paceInfo: PacedPacketInfo,
        var sentProbes: Int = 0,
        var sentBytes: Int = 0,
        var minProbeDelta: Duration = Duration.ZERO,
        var requestedAt: Instant = Instant.MIN,
        var startedAt: Instant = Instant.MIN
    )

    private fun calculateNextProbeTime(cluster: ProbeCluster): Instant {
        check(cluster.paceInfo.sendBitrate >= 0.bps)
        check(cluster.startedAt.isFinite())

        // Compute the time delta from the cluster start to ensure probe bitrate stays
        // close to the target bitrate. Result is in milliseconds.
        val sentBytes = cluster.sentBytes.bytes
        val sendBitrate = cluster.paceInfo.sendBitrate

        val delta = sentBytes / sendBitrate
        return cluster.startedAt + delta
    }

    private fun maybeSetActiveState(packetSize: DataSize) {
        if (readyToSetActiveState(packetSize)) {
            nextProbeTime = Instant.MIN
            probingState = ProbingState.kActive
        }
    }

    private fun readyToSetActiveState(packetSize: DataSize): Boolean {
        if (clusters.isEmpty()) {
            check(probingState == ProbingState.kDisabled || probingState == ProbingState.kInactive)
            return false
        }
        when (probingState) {
            ProbingState.kDisabled, ProbingState.kActive -> {
                return false
            }
            ProbingState.kInactive -> {
                if (config.allowStartProbingImmediately) {
                    return true
                }
                // If config_.min_packet_size > 0, a "large enough" packet must be
                // sent first, before a probe can be generated and sent. Otherwise,
                // send the probe asap.
                return packetSize >= min(recommendedMinProbeSize(), config.minPacketSize)
            }
        }
    }

    private var probingState: ProbingState = ProbingState.kInactive

    // Probe bitrate per packet. These are used to compute the delta relative to
    // the previous probe packet based on the size and time when that packet was
    // sent.
    private val clusters = ArrayDeque<ProbeCluster>()

    // Time that the next probe should be sent when in kActive state
    private var nextProbeTime: Instant = Instant.MAX

    private var config = configIn.copy()
}
