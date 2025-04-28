/*
 * Copyright @ 2015 - Present, 8x8 Inc
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
package org.jitsi.videobridge.cc.allocation

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings
import org.jitsi.nlj.MediaSourceDesc
import org.jitsi.nlj.util.bps
import org.jitsi.utils.event.EventEmitter
import org.jitsi.utils.event.SyncEventEmitter
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging.TimeSeriesLogger
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.videobridge.cc.config.BitrateControllerConfig
import org.jitsi.videobridge.util.TaskPools
import org.json.simple.JSONObject
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.function.Supplier
import kotlin.math.abs

internal class BandwidthAllocator<T : MediaSourceContainer>(
    eventHandler: EventHandler,
    /**
     * Provide the current list of endpoints (in no particular order).
     * TODO: Simplify to avoid the weird (and slow) flow involving `endpointsSupplier` and `sortedEndpointIds`.
     */
    private val endpointsSupplier: Supplier<List<T>>,
    /**
     * Whether bandwidth allocation should be constrained to the available bandwidth (when `true`), or assume
     * infinite bandwidth (when `false`).
     */
    private val trustBwe: Supplier<Boolean>,
    parentLogger: Logger,
    private val diagnosticContext: DiagnosticContext,
    private val clock: Clock
) {
    private val logger = createChildLogger(parentLogger)

    /** The estimated available bandwidth in bits per second. */
    private var bweBps: Long = -1

    /** Whether this bandwidth estimator has been expired. Once expired we stop periodic re-allocation. */
    private var expired = false

    /**
     * The "effective" constraints for an endpoint indicate the maximum resolution/fps that this
     * [BandwidthAllocator] would allocate for this endpoint given enough bandwidth.
     *
     * They are the constraints signaled by the receiver, further reduced to 0 when the endpoint is "outside lastN".
     *
     * Effective constraints are used to signal to video senders to reduce their resolution to the minimum that
     * satisfies all receivers.
     *
     * With the multi-stream support added, the mapping is stored on a per source name basis instead of an endpoint id.
     *
     * When an endpoint falls out of the last N, the constraints of all the sources of this endpoint are reduced to 0.
     *
     * TODO Update this description when the endpoint ID signaling is removed from the JVB.
     */
    private var effectiveConstraints: EffectiveConstraintsMap = emptyMap()
    private val eventEmitter: EventEmitter<EventHandler> = SyncEventEmitter<EventHandler>().apply {
        addHandler(eventHandler)
    }

    /** The allocations settings signalled by the receiver. */
    private var allocationSettings =
        AllocationSettings(
            defaultConstraints = VideoConstraints(BitrateControllerConfig.config.initialMaxHeightPx)
        )

    /**
     * The last time [BandwidthAllocator.update] was called.
     * Initialized as initialization time to prevent triggering an update immediately, because the settings might not
     * have been configured yet.
     */
    private var lastUpdateTime: Instant = clock.instant()

    /** The result of the bitrate control algorithm, the last time it ran. */
    var allocation = BandwidthAllocation(emptySet())
        private set

    /** The task scheduled to call [.update]. */
    private var updateTask: ScheduledFuture<*>? = null

    init {
        rescheduleUpdate()
    }

    /** Gets a JSON representation of the parts of this object's state that are deemed useful for debugging. */
    @get:SuppressFBWarnings(
        value = ["IS2_INCONSISTENT_SYNC"],
        justification = "We intentionally avoid synchronizing while reading fields only used in debug output."
    )
    val debugState: JSONObject
        get() {
            val debugState = JSONObject()
            debugState["trustBwe"] = trustBwe.get()
            debugState["bweBps"] = bweBps
            debugState["allocation"] = allocation.debugState
            debugState["allocationSettings"] = allocationSettings.toJson()
            debugState["effectiveConstraints"] = effectiveConstraints.mapKeys { it.key.sourceName }
            return debugState
        }

    /** Get the available bandwidth, taking into account the `trustBwe` option. */
    private val availableBandwidth: Long
        get() = if (trustBwe.get()) bweBps else Long.MAX_VALUE

    /**
     * Notify the [BandwidthAllocator] that the estimated available bandwidth has changed.
     * @param newBandwidthBps the newly estimated bandwidth in bps
     */
    fun bandwidthChanged(newBandwidthBps: Long) {
        if (!bweChangeIsLargerThanThreshold(bweBps, newBandwidthBps)) {
            logger.debug {
                "New bwe ($newBandwidthBps) is not significantly changed from previous bwe ($bweBps), ignoring."
            }
            // If this is a "negligible" change in the bandwidth estimation
            // wrt the last bandwidth estimation that we reacted to, then
            // do not update the bandwidth allocation. The goal is to limit
            // the resolution changes due to bandwidth estimation changes,
            // as often resolution changes can negatively impact user
            // experience, at the risk of clogging the receiver pipe.
        } else {
            logger.debug { "new bandwidth is $newBandwidthBps, updating" }
            bweBps = newBandwidthBps
            update()
        }
    }

    /**
     * Updates the allocation settings and calculates a new bitrate [BandwidthAllocation].
     * @param allocationSettings the new allocation settings.
     */
    fun update(allocationSettings: AllocationSettings) {
        this.allocationSettings = allocationSettings
        update()
    }

    /**
     * Runs the bandwidth allocation algorithm, and fires events if the result is different from the previous result.
     */
    @Synchronized
    fun update() {
        if (expired) {
            return
        }
        lastUpdateTime = clock.instant()

        // Order the sources by selection, followed by Endpoint's speech activity.
        val sources = endpointsSupplier.get().flatMap { it.mediaSources.toList() }.toMutableList()
        val sortedSources = prioritize(sources, selectedSources)

        // Extract and update the effective constraints.
        val oldEffectiveConstraints = effectiveConstraints
        val newEffectiveConstraints = getEffectiveConstraints(sortedSources, allocationSettings)
        effectiveConstraints = newEffectiveConstraints

        logger.trace {
            "Allocating: sortedSources=${sortedSources.map { it.sourceName }}, " +
                "effectiveConstraints=${newEffectiveConstraints.map { "${it.key.sourceName}=${it.value}" }}"
        }

        // Compute the bandwidth allocation.
        val newAllocation = allocate(sortedSources)
        val allocationChanged = !allocation.isTheSameAs(newAllocation)
        val effectiveConstraintsChanged = effectiveConstraints != oldEffectiveConstraints

        if (timeSeriesLogger.isTraceEnabled) {
            timeSeriesLogger.trace(
                diagnosticContext.makeTimeSeriesPoint("allocator_update", lastUpdateTime)
                    .addField("target_bps", newAllocation.targetBps)
                    .addField("ideal_bps", newAllocation.idealBps)
                    .addField("bwe_bps", bweBps)
                    .addField("oversending", newAllocation.oversending)
                    .addField("allocation_changed", allocationChanged)
                    .addField("effective_constraints_changed", effectiveConstraintsChanged)
            )
        }

        if (allocationChanged) {
            eventEmitter.fireEvent { allocationChanged(newAllocation) }
        }

        allocation = newAllocation

        logger.trace {
            "Finished allocation: allocationChanged=$allocationChanged, " +
                "effectiveConstraintsChanged=$effectiveConstraintsChanged, " +
                "allocation=[$allocation]"
        }
        if (effectiveConstraintsChanged) {
            eventEmitter.fireEvent {
                effectiveVideoConstraintsChanged(oldEffectiveConstraints, effectiveConstraints)
            }
        }
    }

    // On-stage sources are considered selected (with higher priority).
    private val selectedSources: List<String>
        get() {
            // On-stage sources are considered selected (with higher priority).
            val selectedSources = allocationSettings.onStageSources.toMutableList()
            allocationSettings.selectedSources.forEach {
                if (!selectedSources.contains(it)) {
                    selectedSources.add(it)
                }
            }
            return selectedSources
        }

    /**
     * Implements the bandwidth allocation algorithm for the given ordered list of media sources.
     *
     * The new version which works with multiple streams per endpoint.
     *
     * @param conferenceMediaSources the list of endpoint media sources in order of priority to allocate for.
     * @return the new [BandwidthAllocation].
     */
    @Synchronized
    private fun allocate(conferenceMediaSources: List<MediaSourceDesc>): BandwidthAllocation {
        val sourceBitrateAllocations = createAllocations(conferenceMediaSources)
        if (sourceBitrateAllocations.isEmpty()) {
            return BandwidthAllocation(emptySet())
        }
        var remainingBandwidth = if (allocationSettings.assumedBandwidthBps >= 0) {
            logger.warn("Allocating with assumed bandwidth ${allocationSettings.assumedBandwidthBps.bps}.")
            allocationSettings.assumedBandwidthBps
        } else {
            availableBandwidth
        }
        var oldRemainingBandwidth: Long = -1
        var oversending = false
        while (oldRemainingBandwidth != remainingBandwidth) {
            oldRemainingBandwidth = remainingBandwidth
            for (i in sourceBitrateAllocations.indices) {
                val sourceBitrateAllocation = sourceBitrateAllocations[i]
                if (sourceBitrateAllocation.constraints.isDisabled()) {
                    continue
                }

                // In stage view improve greedily until preferred, in tile view go step-by-step.
                remainingBandwidth -= sourceBitrateAllocation.improve(remainingBandwidth, i == 0)
                if (remainingBandwidth < 0) {
                    oversending = true
                }

                // In stage view, do not allocate bandwidth for thumbnails until the on-stage reaches "preferred".
                // This prevents enabling thumbnail only to disable them when bwe slightly increases allowing on-stage
                // to take more.
                if (sourceBitrateAllocation.isOnStage() && !sourceBitrateAllocation.hasReachedPreferred()) {
                    break
                }
            }
        }

        // The sources which are in lastN, and are sending video, but were suspended due to bwe.
        val suspendedIds = sourceBitrateAllocations
            .filter { it.isSuspended }
            .map { it.mediaSource.sourceName }.toList()
        if (suspendedIds.isNotEmpty()) {
            logger.info("Sources suspended due to insufficient bandwidth (bwe=$availableBandwidth bps): $suspendedIds")
        }
        val allocations = mutableSetOf<SingleAllocation>()
        var targetBps: Long = 0
        var idealBps: Long = 0
        for (sourceBitrateAllocation: SingleSourceAllocation in sourceBitrateAllocations) {
            allocations.add(sourceBitrateAllocation.result)
            targetBps += sourceBitrateAllocation.targetBitrate
            idealBps += sourceBitrateAllocation.idealBitrate
        }
        return BandwidthAllocation(allocations, oversending, idealBps, targetBps, suspendedIds)
    }

    /**
     * Query whether the allocator has non-zero effective constraints for the given endpoint or source.
     */
    internal fun hasNonZeroEffectiveConstraints(source: MediaSourceDesc): Boolean {
        val constraints = effectiveConstraints[source] ?: return false
        return !constraints.isDisabled()
    }

    @Synchronized
    private fun createAllocations(conferenceMediaSources: List<MediaSourceDesc>): List<SingleSourceAllocation> =
        conferenceMediaSources.map { source ->
            SingleSourceAllocation(
                source.owner,
                source,
                // Note that we use the effective constraints and not the receiver's constraints
                // directly. This means we never even try to allocate bitrate to sources "outside
                // lastN". For example, if LastN=1 and the first endpoint sends a non-scalable
                // stream with bitrate higher that the available bandwidth, we will forward no
                // video at all instead of going to the second endpoint in the list.
                // I think this is not desired behavior. However, it is required for the "effective
                // constraints" to work as designed.
                effectiveConstraints[source]!!,
                allocationSettings.onStageSources.contains(source.sourceName),
                diagnosticContext,
                clock,
                logger
            )
        }.toList()

    /**
     * Expire this bandwidth allocator.
     */
    fun expire() {
        expired = true
        updateTask?.cancel(false)
    }

    /**
     * Submits a call to `update` in a CPU thread if bandwidth allocation has not been performed recently.
     *
     * Also, re-schedule the next update in at most `maxTimeBetweenCalculations`. This should only be run
     * in the constructor or in the scheduler thread, otherwise it will schedule multiple tasks.
     */
    private fun rescheduleUpdate() {
        if (expired) {
            return
        }
        val timeSinceLastUpdate = Duration.between(lastUpdateTime, clock.instant())
        val period = BitrateControllerConfig.config.maxTimeBetweenCalculations
        val delayMs = if (timeSinceLastUpdate > period) {
            logger.debug("Running periodic re-allocation.")
            TaskPools.CPU_POOL.execute { this.update() }
            period.toMillis()
        } else {
            period.minus(timeSinceLastUpdate).toMillis()
        }

        // Add 5ms to avoid having to re-schedule right away. This increases the average period at which we
        // re-allocate by an insignificant amount.
        updateTask = TaskPools.SCHEDULED_POOL.schedule(
            { rescheduleUpdate() },
            delayMs + 5,
            TimeUnit.MILLISECONDS
        )
    }

    companion object {
        private val timeSeriesLogger = TimeSeriesLogger.getTimeSeriesLogger(BandwidthAllocator::class.java)
    }

    interface EventHandler {
        fun allocationChanged(allocation: BandwidthAllocation)
        fun effectiveVideoConstraintsChanged(
            oldEffectiveConstraints: EffectiveConstraintsMap,
            newEffectiveConstraints: EffectiveConstraintsMap
        )
    }
}

/**
 * Returns a boolean that indicates whether the current bandwidth estimation (in bps) has changed above the
 * configured threshold with respect to the previous bandwidth estimation.
 *
 * @param previousBwe the previous bandwidth estimation (in bps).
 * @param currentBwe the current bandwidth estimation (in bps).
 * @return true if the bandwidth has changed above the configured threshold, * false otherwise.
 */
private fun bweChangeIsLargerThanThreshold(previousBwe: Long, currentBwe: Long): Boolean {
    if (previousBwe == currentBwe) { // Even if we're "changing" -1 to -1
        return false
    }
    if (previousBwe == -1L || currentBwe == -1L) {
        return true
    }

    // We supress re-allocation when BWE has changed less than 15% (by default) of its previous value in order to
    // prevent excessive changes during ramp-up.
    // When BWE increases it should eventually increase past the threshold because of probing.
    // When BWE decreases it is probably above the threshold because of AIMD. It's not clear to me whether we need
    // the threshold in this case.
    // In any case, there are other triggers for re-allocation, so any suppression we do here will only last up to
    // a few seconds.
    val deltaBwe = abs(currentBwe - previousBwe)
    return deltaBwe > previousBwe * BitrateControllerConfig.config.bweChangeThreshold

    // If, on the other hand, the bwe has decreased, we require at least a 15% drop in order to update the bitrate
    // allocation. This is an ugly hack to prevent too many resolution/UI changes in case the bridge produces too
    // low bandwidth estimate, at the risk of clogging the receiver's pipe.
    // TODO: do we still need this? Do we ever ever see BWE drop by <%15?
}

typealias EffectiveConstraintsMap = Map<MediaSourceDesc, VideoConstraints>
