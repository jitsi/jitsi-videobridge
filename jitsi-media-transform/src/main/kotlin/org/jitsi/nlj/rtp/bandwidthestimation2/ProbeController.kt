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
@file:Suppress("ktlint:standard:property-naming", "ktlint:standard:enum-entry-name-case")

package org.jitsi.nlj.rtp.bandwidthestimation2

import org.jitsi.nlj.util.Bandwidth
import org.jitsi.nlj.util.kbps
import org.jitsi.nlj.util.min
import org.jitsi.nlj.util.times
import org.jitsi.utils.MAX_DURATION
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging.TimeSeriesLogger
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.utils.max
import org.jitsi.utils.ms
import org.jitsi.utils.secs
import java.time.Duration
import java.time.Instant

/** Probe controller,
 * based on WebRTC modules/congestion_controller/goog_cc/probe_controller.{h,cc} in
 * WebRTC tag branch-heads/7204 (Chromium 138).
 *
 * Field trial settings have been generally removed, set to their default settings.
 */

// Maximum waiting time from the time of initiating probing to getting
// the measured results back.
private val kMaxWaitingTimeForProbingResult = 1.secs

// Default probing bitrate limit. Applied only when the application didn't
// specify max bitrate.
private val kDefaultMaxProbingBitrate = 5000.kbps

// If the bitrate drops to a factor `kBitrateDropThreshold` or lower
// and we recover within `kBitrateDropTimeoutMs`, then we'll send
// a probe at a fraction `kProbeFractionAfterDrop` of the original bitrate.
private const val kBitrateDropThreshold = 0.66
private val kBitrateDropTimeout = 5.secs
private const val kProbeFractionAfterDrop = 0.85

// Timeout for probing after leaving ALR. If the bitrate drops significantly,
// (as determined by the delay based estimator) and we leave ALR, then we will
// send a probe if we recover within `kLeftAlrTimeoutMs` ms.
private val kAlrEndedTimeout = 3.secs

// This is a limit on how often probing can be done when there is a BW
// drop detected in ALR.
private val kMinTimeBetweenAlrProbes = 5.secs

// The expected uncertainty of probe result (as a fraction of the target probe
// bitrate). Used to avoid probing if the probe bitrate is close to our current
// estimate.
private const val kProbeUncertainty = 0.05

class ProbeControllerConfig(
    // These parameters configure the initial probes. First we send one or two
    // probes of sizes p1 * start_bitrate_ and p2 * start_bitrate_.
    // Then whenever we get a bitrate estimate of at least further_probe_threshold
    // times the size of the last sent probe we'll send another one of size
    // step_size times the new estimate.
    val firstExponentialProbeScale: Double = 3.0,
    val secondExponentialProbeScale: Double? = 6.0,
    val furtherExponentialProbeScale: Double = 2.0,
    val furtherProbeThreshold: Double = 0.7,
    val abortFurtherProbeIfMaxLowerThanCurrent: Boolean = false,

    val repeatedInitialProbingTimePeriod: Duration = 5.secs,
    // The minimum probing duration of an individual probe during
    // the repeated_initial_probing_time_period.
    val initialProbeDuration: Duration = 100.ms,
    // Delta time between sent bursts of packets in a probe during
    // the repeated_initial_probing_time_period.
    val initialMinProbeDelta: Duration = 20.ms,
    // Configures how often we send ALR probes and how big they are.
    val alrProbingInterval: Duration = 5.secs,
    val alrProbeScale: Double = 2.0,
    // Configures how often we send probes if NetworkStateEstimate is available.
    val networkStateEstimateProbingInterval: Duration = MAX_DURATION,
    // Periodically probe as long as the ratio between current estimate and
    // NetworkStateEstimate is lower then this.
    val probeIfEstimateLowerThanNetworkStateEstimateRatio: Double = 0.0,
    val estimateLowerThanNetworkStateProbingInterval: Duration = 3.secs,
    val networkStateProbeScale: Double = 1.0,
    // Overrides min_probe_duration if network_state_estimate_probing_interval
    // is set and a network state estimate is known.
    val networkStateProbeDuration: Duration = 15.ms,
    // Overrides min_probe_delta if network_state_estimate_probing_interval
    // is set and a network state estimate is known and equal or higher than the
    // probe target.
    val networkStateMinProbeDelta: Duration = 20.ms,

    // Configures the probes emitted by changed to the allocated bitrate.
    val probeOnMaxAllocatedBitrateChange: Boolean = true,
    val firstAllocationProbeScale: Double? = 1.0,
    val secondAllocationProbeScale: Double? = 2.0,
    val allocationProbeLimitByCurrentScale: Double = 2.0,

    // The minimum number probing packets used.
    val minProbePacketsSent: Int = 5,
    // The minimum probing duration.
    val minProbeDuration: Duration = 15.ms,
    // Delta time between sent bursts of packets in a probe.
    val minProbeDelta: Duration = 2.ms,
    val lossLimitedProbeScale: Double = 1.5,
    // Don't send a probe if min(estimate, network state estimate) is larger than
    // this fraction of the set max or max allocated bitrate.
    val skipIfEstimateLargerThanFractionOfMax: Double = 0.0,
    // Scale factor of the max allocated bitrate. Used when deciding if a probe
    // can be skiped due to that the estimate is already high enough.
    val skipProbeMaxAllocatedScale: Double = 1.0,
)

/* Reason that bandwidth estimate is limited. Bandwidth estimate can be limited
 * by either delay based bwe, or loss based bwe when it increases/decreases the
 * estimate.
 */
enum class BandwidthLimitedCause {
    kLossLimitedBweIncreasing,
    kLossLimitedBwe,
    kDelayBasedLimited,
    kDelayBasedLimitedDelayIncreased,
    kRttBasedBackOffHighRtt
}

/* This class controls initiation of probing to estimate initial channel
 * capacity. There is also support for probing during a session when max
 * bitrate is adjusted by an application.
 */
class ProbeController(
    parentLogger: Logger,
    private val diagnosticContext: DiagnosticContext,
    private val config: ProbeControllerConfig = ProbeControllerConfig()
) {
    private val logger = createChildLogger(parentLogger)

    private var networkAvailable = false
    private var repeatedInitialProbingEnabled = false
    private var lastAllowedRepeatedInitialProbe = Instant.MIN
    private var bandwidthLimitedCause = BandwidthLimitedCause.kDelayBasedLimited
    private var state = State.kInit
    private var minBitrateToProbeFurther = Bandwidth.INFINITY
    private var timeLastProbingInitiated = Instant.MIN
    private var estimatedBitrate = Bandwidth.ZERO

    /* Skipping network_estimate */

    private var startBitrate = Bandwidth.ZERO
    private var maxBitrate = Bandwidth.INFINITY
    private var lastBweDropProbingTime = Instant.MIN
    private var alrStartTime: Instant? = null
    private var alrEndTime: Instant? = null
    private var enablePeriodicAlrProbing = false
    private var timeOfLastLargeDrop = Instant.MIN
    private var bitrateBeforeLastLargeDrop = Bandwidth.ZERO
    private var maxTotalAllocatedBitrate = Bandwidth.ZERO

    private val inRapidRecoveryExperiment = false

    private var nextProbeClusterId = 1

    fun setBitrates(
        minBitrate: Bandwidth,
        startBitrate: Bandwidth,
        maxBitrate: Bandwidth,
        atTime: Instant
    ): MutableList<ProbeClusterConfig> {
        if (startBitrate > Bandwidth.ZERO) {
            this.startBitrate = startBitrate
            estimatedBitrate = startBitrate
        } else if (this.startBitrate == Bandwidth.ZERO) {
            this.startBitrate = minBitrate
        }

        // The reason we use the variable `old_max_bitrate_pbs` is because we
        // need to set `max_bitrate_` before we call InitiateProbing.
        val oldMaxBitrate = this.maxBitrate
        this.maxBitrate = if (maxBitrate.isFinite()) {
            maxBitrate
        } else {
            kDefaultMaxProbingBitrate
        }

        when (state) {
            State.kInit ->
                if (networkAvailable) {
                    return initiateExponentialProbing(atTime)
                }

            State.kWaitingForProbingResult ->
                Unit

            State.kProbingComplete ->
                // If the new max bitrate is higher than both the old max bitrate and the
                // estimate then initiate probing.
                if (estimatedBitrate != Bandwidth.ZERO && oldMaxBitrate < this.maxBitrate &&
                    estimatedBitrate < this.maxBitrate
                ) {
                    return initiateProbing(atTime, listOf(maxBitrate), false)
                }
        }

        return mutableListOf()
    }

    // The total bitrate, as opposed to the max bitrate, is the sum of the
    // configured bitrates for all active streams.
    fun onMaxTotalAllocatedBitrate(
        maxTotalAllocatedBitrate: Bandwidth,
        atTime: Instant
    ): MutableList<ProbeClusterConfig> {
        val inAlr = alrStartTime != null
        val allowAllocationProbe = inAlr

        if (config.probeOnMaxAllocatedBitrateChange &&
            state == State.kProbingComplete &&
            maxTotalAllocatedBitrate != this.maxTotalAllocatedBitrate &&
            estimatedBitrate < maxBitrate &&
            estimatedBitrate < maxTotalAllocatedBitrate &&
            allowAllocationProbe
        ) {
            this.maxTotalAllocatedBitrate = maxTotalAllocatedBitrate

            if (config.firstAllocationProbeScale == null) {
                return mutableListOf()
            }
            var firstProbeRate = maxTotalAllocatedBitrate * config.firstAllocationProbeScale
            val currentBweLimit =
                config.allocationProbeLimitByCurrentScale *
                    estimatedBitrate
            var limitedByCurrentBwe = currentBweLimit < firstProbeRate
            if (limitedByCurrentBwe) {
                firstProbeRate = currentBweLimit
            }

            val probes = mutableListOf(firstProbeRate)
            if (!limitedByCurrentBwe && config.secondAllocationProbeScale != null) {
                var secondProbeRate = maxTotalAllocatedBitrate * config.secondAllocationProbeScale
                limitedByCurrentBwe = currentBweLimit < secondProbeRate
                if (limitedByCurrentBwe) {
                    secondProbeRate = currentBweLimit
                }
                if (secondProbeRate > firstProbeRate) {
                    probes.add(secondProbeRate)
                }
            }
            val allowFurtherProbing = limitedByCurrentBwe
            return initiateProbing(atTime, probes, allowFurtherProbing)
        }
        if (maxTotalAllocatedBitrate != Bandwidth.ZERO) {
            lastAllowedRepeatedInitialProbe = atTime
        }

        this.maxTotalAllocatedBitrate = maxTotalAllocatedBitrate
        return mutableListOf()
    }

    fun onNetworkAvailability(msg: NetworkAvailability): MutableList<ProbeClusterConfig> {
        networkAvailable = msg.networkAvailable

        if (!networkAvailable && state == State.kWaitingForProbingResult) {
            state = State.kProbingComplete
            minBitrateToProbeFurther = Bandwidth.INFINITY
        }

        if (networkAvailable && state == State.kInit && startBitrate != Bandwidth.ZERO) {
            return initiateExponentialProbing(msg.atTime)
        }
        return mutableListOf()
    }

    private fun updateState(newState: State) {
        when (newState) {
            State.kInit ->
                state = State.kInit
            State.kWaitingForProbingResult ->
                state = State.kWaitingForProbingResult
            State.kProbingComplete -> {
                state = State.kProbingComplete
                minBitrateToProbeFurther = Bandwidth.INFINITY
            }
        }
    }

    private fun initiateExponentialProbing(atTime: Instant): MutableList<ProbeClusterConfig> {
        assert(networkAvailable)
        assert(state == State.kInit)
        assert(startBitrate > Bandwidth.ZERO)

        // When probing at 1.8 Mbps ( 6x 300), this represents a threshold of
        // 1.2 Mbps to continue probing.
        val probes = mutableListOf(config.firstExponentialProbeScale * startBitrate)
        if (config.secondExponentialProbeScale != null && config.secondExponentialProbeScale > 0.0) {
            probes.add(config.secondExponentialProbeScale * startBitrate)
        }
        if (repeatedInitialProbingEnabled && maxTotalAllocatedBitrate == Bandwidth.ZERO) {
            lastAllowedRepeatedInitialProbe =
                atTime + config.repeatedInitialProbingTimePeriod
            logger.info {
                "Repeated initial probing enabled, last allowed probe: $lastAllowedRepeatedInitialProbe now: $atTime"
            }
        }

        return initiateProbing(atTime, probes, true)
    }

    fun setEstimatedBitrate(
        bitrate: Bandwidth,
        bandwidthLimitedCause: BandwidthLimitedCause,
        atTime: Instant
    ): MutableList<ProbeClusterConfig> {
        this.bandwidthLimitedCause = bandwidthLimitedCause
        if (bitrate < kBitrateDropThreshold * estimatedBitrate) {
            timeOfLastLargeDrop = atTime
            bitrateBeforeLastLargeDrop = estimatedBitrate
        }
        estimatedBitrate = bitrate

        if (state == State.kWaitingForProbingResult) {
            // Continue probing if probing results indicate channel has greater
            // capacity unless we already reached the needed bitrate.
            if (config.abortFurtherProbeIfMaxLowerThanCurrent && (
                    bitrate > maxBitrate || (
                        maxTotalAllocatedBitrate != Bandwidth.ZERO &&
                            bitrate > 2 * maxTotalAllocatedBitrate
                        )
                    )
            ) {
                // No need to continue probing
                minBitrateToProbeFurther = Bandwidth.INFINITY
            }
            val networkStateEstimateProbeFurtherLimit =
                /* Skipping networkEstimate */
                Bandwidth.INFINITY
            logger.info(
                "Measured bitrate: $bitrate Minimum to probe further: $minBitrateToProbeFurther " +
                    "upper limit: $networkStateEstimateProbeFurtherLimit"
            )

            if (bitrate > minBitrateToProbeFurther &&
                bitrate <= networkStateEstimateProbeFurtherLimit
            ) {
                return initiateProbing(atTime, listOf(config.furtherExponentialProbeScale * bitrate), true)
            }
        }
        return mutableListOf()
    }

    fun enablePeriodicAlrProbing(enable: Boolean) {
        enablePeriodicAlrProbing = enable
    }

    // Probes are sent periodically every 1s during the first 5s after the network
    // becomes available or until OnMaxTotalAllocatedBitrate is invoked with a
    // none zero max_total_allocated_bitrate (there are active streams being
    // sent.) Probe rate is up to max configured bitrate configured via
    // SetBitrates.
    fun enableRepeatedInitialProbing(enable: Boolean) {
        repeatedInitialProbingEnabled = enable
    }

    fun setAlrStartTimeMs(alrStartTimeMs: Long?) {
        if (alrStartTimeMs != null) {
            alrStartTime = Instant.ofEpochMilli(alrStartTimeMs)
        } else {
            alrStartTime = null
        }
    }

    fun setAlrEndedTimeMs(alrEndTimeMs: Long) {
        alrEndTime = Instant.ofEpochMilli(alrEndTimeMs)
    }

    fun requestProbe(atTime: Instant): MutableList<ProbeClusterConfig> {
        // Called once we have returned to normal state after a large drop in
        // estimated bandwidth. The current response is to initiate a single probe
        // session (if not already probing) at the previous bitrate.
        //
        // If the probe session fails, the assumption is that this drop was a
        // real one from a competing flow or a network change.
        val inAlr = alrStartTime != null
        val alrEndedRecently = (
            alrEndTime != null &&
                Duration.between(alrEndTime, atTime) < kAlrEndedTimeout
            )
        if (inAlr || alrEndedRecently || inRapidRecoveryExperiment) {
            if (state == State.kProbingComplete) {
                val suggestedProbe = kProbeFractionAfterDrop * bitrateBeforeLastLargeDrop
                val minExpectedProbeResult = (1 - kProbeUncertainty) * suggestedProbe
                val timeSinceDrop = Duration.between(timeOfLastLargeDrop, atTime)
                val timeSinceProbe = Duration.between(lastBweDropProbingTime, atTime)
                if (minExpectedProbeResult > estimatedBitrate &&
                    timeSinceDrop < kBitrateDropTimeout &&
                    timeSinceProbe > kMinTimeBetweenAlrProbes
                ) {
                    logger.info("Detected big bandwidth drop, start probing")
                    // Track how often we probe in response to bandwidth drop in ALR.
                    // TODO: histogram
                    lastBweDropProbingTime = atTime
                    return initiateProbing(atTime, listOf(suggestedProbe), false)
                }
            }
        }
        return mutableListOf()
    }

    /* Skipping setNetworkStateEstimate */

    /**
     * Resets the ProbeController to a state equivalent to as if it was just
     * created EXCEPT for configuration settings like
     * `enable_periodic_alr_probing_` `network_available_` and
     * `max_total_allocated_bitrate_`.
     */
    fun reset(atTime: Instant) {
        bandwidthLimitedCause = BandwidthLimitedCause.kDelayBasedLimited
        state = State.kInit
        minBitrateToProbeFurther = Bandwidth.INFINITY
        timeLastProbingInitiated = Instant.MIN
        estimatedBitrate = Bandwidth.ZERO
        startBitrate = Bandwidth.ZERO
        maxBitrate = kDefaultMaxProbingBitrate
        val now = atTime
        lastBweDropProbingTime = now
        alrEndTime = null
        timeOfLastLargeDrop = now
        bitrateBeforeLastLargeDrop = Bandwidth.ZERO
    }

    private fun timeForAlrProbe(atTime: Instant): Boolean {
        if (enablePeriodicAlrProbing && alrStartTime != null) {
            val nextProbeTime =
                max(alrStartTime!!, timeLastProbingInitiated) +
                    config.alrProbingInterval
            return atTime >= nextProbeTime
        }
        return false
    }

    private fun timeForNetworkStateProbe(atTime: Instant): Boolean {
        /* Not using network_estimate */
        return false
    }

    private fun timeForNextRepeatedInitialProbe(atTime: Instant): Boolean {
        if (state != State.kWaitingForProbingResult &&
            lastAllowedRepeatedInitialProbe > atTime
        ) {
            val nextProbeTime = timeLastProbingInitiated + kMaxWaitingTimeForProbingResult
            if (atTime >= nextProbeTime) {
                return true
            }
        }
        return false
    }

    private fun createProbeClusterConfig(atTime: Instant, bitrate: Bandwidth): ProbeClusterConfig {
        val config = ProbeClusterConfig()
        config.atTime = atTime
        config.targetDataRate = bitrate
        if (atTime < lastAllowedRepeatedInitialProbe) {
            config.targetDuration = this.config.initialProbeDuration
            config.minProbeDelta = this.config.initialMinProbeDelta
        } else {
            config.targetDuration = this.config.minProbeDuration
            config.minProbeDelta = this.config.minProbeDelta
        }
        config.targetProbeCount = this.config.minProbePacketsSent
        config.id = nextProbeClusterId
        nextProbeClusterId++
        maybeLogProbeClusterCreated(diagnosticContext, config)
        return config
    }

    fun process(atTime: Instant): MutableList<ProbeClusterConfig> {
        if (Duration.between(timeLastProbingInitiated, atTime) > kMaxWaitingTimeForProbingResult) {
            if (state == State.kWaitingForProbingResult) {
                logger.info("kWaitingForProbingResult: timeout")
                updateState(State.kProbingComplete)
            }
        }
        if (estimatedBitrate == Bandwidth.ZERO || state != State.kProbingComplete) {
            return mutableListOf()
        }
        if (timeForNextRepeatedInitialProbe(atTime)) {
            return initiateProbing(atTime, listOf(estimatedBitrate * config.firstExponentialProbeScale), true)
        }
        if (timeForAlrProbe(atTime) || timeForNetworkStateProbe(atTime)) {
            return initiateProbing(atTime, listOf(estimatedBitrate * config.alrProbeScale), true)
        }
        return mutableListOf()
    }

    private enum class State {
        /** Initial state where no probing has been triggrered yet */
        kInit,

        /** Waiting for probing results to continue further probing. */
        kWaitingForProbingResult,

        /** Probing is complete. */
        kProbingComplete
    }

    private fun initiateProbing(
        now: Instant,
        bitratesToProbe: List<Bandwidth>,
        probeFurtherIn: Boolean
    ): MutableList<ProbeClusterConfig> {
        var probeFurther = probeFurtherIn
        if (config.skipIfEstimateLargerThanFractionOfMax > 0.0) {
            val networkEstimate = Bandwidth.INFINITY
            val maxProbeRate = if (maxTotalAllocatedBitrate == Bandwidth.ZERO) {
                maxBitrate
            } else {
                min(config.skipProbeMaxAllocatedScale * maxTotalAllocatedBitrate, maxBitrate)
            }
            if (min(networkEstimate, estimatedBitrate) > config.skipIfEstimateLargerThanFractionOfMax * maxProbeRate) {
                updateState(State.kProbingComplete)
                return mutableListOf()
            }
        }

        var maxProbeBitrate = maxBitrate
        if (maxTotalAllocatedBitrate > Bandwidth.ZERO) {
            // If a max allocated bitrate has been configured, allow probing up to 2x
            // that rate. This allows some overhead to account for bursty streams,
            // which otherwise would have to ramp up when the overshoot is already in
            // progress.
            // It also avoids minor quality reduction caused by probes often being
            // received at slightly less than the target probe bitrate.
            maxProbeBitrate = min(maxProbeBitrate, maxTotalAllocatedBitrate * 2)
        }

        when (bandwidthLimitedCause) {
            BandwidthLimitedCause.kRttBasedBackOffHighRtt,
            BandwidthLimitedCause.kDelayBasedLimitedDelayIncreased,
            BandwidthLimitedCause.kLossLimitedBwe -> {
                logger.info { "Not sending probe in bandwidth limited state. $bandwidthLimitedCause" }
                return mutableListOf()
            }
            BandwidthLimitedCause.kLossLimitedBweIncreasing ->
                maxProbeBitrate =
                    min(maxProbeBitrate, estimatedBitrate * config.lossLimitedProbeScale)
            BandwidthLimitedCause.kDelayBasedLimited ->
                Unit
            else ->
                Unit
        }

        /* Skipping use of networkEstimate */

        val pendingProbes = mutableListOf<ProbeClusterConfig>()
        for (b in bitratesToProbe) {
            assert(b != Bandwidth.ZERO)
            var bitrate = b
            if (bitrate >= maxProbeBitrate) {
                bitrate = maxProbeBitrate
                probeFurther = false
            }

            pendingProbes.add(createProbeClusterConfig(now, bitrate))
        }
        timeLastProbingInitiated = now
        if (probeFurther) {
            updateState(State.kWaitingForProbingResult)
            // Dont expect probe results to be larger than a fraction of the actual
            // probe rate.
            minBitrateToProbeFurther = pendingProbes.last().targetDataRate * config.furtherProbeThreshold
        } else {
            updateState(State.kProbingComplete)
        }
        return pendingProbes
    }

    companion object {
        private val timeSeriesLogger = TimeSeriesLogger.getTimeSeriesLogger(ProbeController::class.java)

        private fun maybeLogProbeClusterCreated(diagnosticContext: DiagnosticContext, probe: ProbeClusterConfig) {
            val minDataSize = probe.targetDataRate * probe.targetDuration
            timeSeriesLogger.trace {
                diagnosticContext.makeTimeSeriesPoint("ProbeClusterCreated")
                    .addField("probe_id", probe.id)
                    .addField("probe_target_data_rate_bps", probe.targetDataRate.bps)
                    .addField("probe_target_probe_count", probe.targetProbeCount)
                    .addField("probe_min_data_size", minDataSize.bytes)
            }
        }
    }
}
