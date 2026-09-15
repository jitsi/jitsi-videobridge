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

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import org.jitsi.config.JitsiConfig
import org.jitsi.metaconfig.config
import org.jitsi.nlj.Event
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.SetLocalSsrcEvent
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.node.TransformerNode
import org.jitsi.nlj.util.ReadOnlyStreamInformationStore
import org.jitsi.rtp.rtcp.rtcpfb.RtcpFbPacket
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbFirPacket
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbFirPacketBuilder
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbPliPacket
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbPliPacketBuilder
import org.jitsi.utils.MediaType
import org.jitsi.utils.RateLimit
import org.jitsi.utils.durationOfDoubleSeconds
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.cdebug
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.utils.min
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * [KeyframeRequester] handles a few things around keyframes:
 * 1) The bridge requesting a keyframe (e.g. in order to switch) via the [KeyframeRequester#requestKeyframe]
 * method which will create a new keyframe request and forward it
 * 2) PLI/FIR translation.  If a PLI or FIR packet is forwarded through here, this class may translate it depending
 * on what the client supports
 * 3) Aggregation.  This class will pace outgoing requests such that we don't spam the sender
 */
class KeyframeRequester @JvmOverloads constructor(
    private val streamInformationStore: ReadOnlyStreamInformationStore,
    parentLogger: Logger,
    private val clock: Clock = Clock.systemDefaultZone()
) : TransformerNode("Keyframe Requester") {
    private val logger = createChildLogger(parentLogger)

    // Map the tuple of requester and SSRC to a rate limiter
    private val perReceiverKeyframeLimiter = mutableMapOf<String, MutableMap<Long, RateLimit>>()
    private val perSourceKeyframeLimiter = mutableMapOf<Long, RateLimit>()
    private val keyframeLimiterSyncRoot = Any()
    private val firCommandSequenceNumber: AtomicInteger = AtomicInteger(0)
    private var localSsrc: Long? = null
    private var waitInterval = minInterval

    // Stats

    // Number of PLI/FIRs received and forwarded to the endpoint.
    private var numPlisForwarded: Int = 0
    private var numFirsForwarded: Int = 0

    // Number of PLI/FIRs received but dropped due to throttling.
    private var numPlisDropped: Int = 0
    private var numFirsDropped: Int = 0

    // Number of PLI/FIRs generated as a result of an API request or due to translation between PLI/FIR.
    private var numPlisGenerated: Int = 0
    private var numFirsGenerated: Int = 0

    // Number of calls to requestKeyframe
    private var numApiRequests: Int = 0

    // Number of calls to requestKeyframe ignored due to throttling
    private var numApiRequestsDropped: Int = 0

    // Number of requests dropped by each limiter, to show which one is binding.
    private var numRequestsDroppedPerReceiverLimit: Int = 0
    private var numRequestsDroppedSourceWideLimit: Int = 0

    @Volatile
    private var keyframeCostSupplier: ((Long) -> KeyframeCost?)? = null

    /**
     * For each source a request has been sent for, the source-wide limit computed when the most recent request was
     * sent, which applies to the next one. Only populated when keyframe budget limiting is enabled.
     */
    private val lastSourceWideLimits = ConcurrentHashMap<Long, SourceWideLimit>()

    // Keyframe budget stats: how often the budget, rather than the configured source-wide interval, governed a
    // request, and what it cost.

    /** Requests sent while the interval applied was the configured floor. */
    private var numRequestsSentAtFloor: Int = 0

    /** Requests sent while the interval applied had been lengthened by the budget. */
    private var numRequestsSentBudgetLengthened: Int = 0

    /** Sum over sent requests of the interval applied minus the floor. */
    private var totalBudgetExtensionMs: Long = 0

    /** Requests dropped by the source-wide limit which the floor alone would have accepted. */
    private var numRequestsDroppedByBudget: Int = 0

    /**
     * For each source, and each distinct requester dropped by the budget since the last request was sent for that
     * source, when that requester was first dropped. Keyed by requester rather than just source, so that when
     * several receivers are waiting on the same source concurrently, sending one request that satisfies all of them
     * counts every one of their waits, not just the first. A `null` key holds unattributed requesters (dominant
     * speaker switches, or requests relayed from another bridge) as one entry, since there is nothing to tell them
     * apart by.
     */
    private val budgetWaitStarts = mutableMapOf<Long, MutableMap<String?, PendingBudgetWait>>()

    /** The number of times a receiver waited for a request the budget had delayed, and the total time waited. */
    private var numBudgetWaits: Int = 0
    private var totalBudgetWaitMs: Long = 0

    /** The longest a receiver has waited for a request the budget had delayed. */
    private var maxBudgetWaitMs: Long = 0

    // The same counters as above, restricted to requests this bridge generated itself (a layer switch needing a
    // keyframe, or a dominant-speaker switch) rather than a PLI/FIR forwarded from a receiver's own decoder. Since a
    // bridge-generated request is what unblocks a stalled layer switch, this shows whether the budget's delay is
    // landing on that case specifically, as opposed to redundant re-requests from receivers already waiting.

    private var numRequestsSentAtFloorApi: Int = 0
    private var numRequestsSentBudgetLengthenedApi: Int = 0
    private var totalBudgetExtensionMsApi: Long = 0
    private var numRequestsDroppedByBudgetApi: Int = 0
    private var numBudgetWaitsApi: Int = 0
    private var totalBudgetWaitMsApi: Long = 0
    private var maxBudgetWaitMsApi: Long = 0

    override fun transform(packetInfo: PacketInfo): PacketInfo? {
        val pliOrFirPacket = packetInfo.getPliOrFirPacket() ?: return packetInfo

        val now = clock.instant()
        val sourceSsrc: Long
        val canSend: Boolean
        val forward: Boolean
        when (pliOrFirPacket) {
            is RtcpFbPliPacket -> {
                sourceSsrc = pliOrFirPacket.mediaSourceSsrc
                canSend = canSendKeyframeRequest(packetInfo.endpointId, sourceSsrc, now)
                forward = canSend && streamInformationStore.supportsPli
                if (forward) numPlisForwarded++
                if (!canSend) numPlisDropped++
            }

            is RtcpFbFirPacket -> {
                sourceSsrc = pliOrFirPacket.mediaSenderSsrc
                canSend = canSendKeyframeRequest(packetInfo.endpointId, sourceSsrc, now)
                // When both are supported, we favor generating a PLI rather than forwarding a FIR
                forward = canSend && streamInformationStore.supportsFir && !streamInformationStore.supportsPli
                if (forward) {
                    // When we forward a FIR we need to update the seq num.
                    pliOrFirPacket.seqNum = firCommandSequenceNumber.incrementAndGet()
                    // We manage the seq num space, so we should use the same SSRC
                    localSsrc?.let { pliOrFirPacket.mediaSenderSsrc = it }
                    numFirsForwarded++
                }
                if (!canSend) numFirsDropped++
            }

            // This is not possible, but the compiler doesn't know it.
            else -> throw IllegalStateException("Packet is neither PLI nor FIR")
        }

        if (!forward && canSend) {
            doRequestKeyframe(sourceSsrc)
        }

        return if (forward) packetInfo else null
    }

    /**
     * Returns 'true' when at least one method is supported, AND this requester hasn't sent a request very recently.
     * [apiTriggered] is whether this is a request the bridge generated itself, as opposed to one forwarded from a
     * receiver's own PLI/FIR; it is only used to attribute the budget stats below.
     */
    private fun canSendKeyframeRequest(
        requesterID: String?,
        mediaSsrc: Long,
        now: Instant,
        apiTriggered: Boolean = false
    ): Boolean {
        if (!streamInformationStore.supportsPli && !streamInformationStore.supportsFir) {
            return false
        }
        val floor = maxOf(waitInterval, sourceWideMinInterval)
        synchronized(keyframeLimiterSyncRoot) {
            /* A null requesterID is a dominant speaker switch, or a request relayed from another bridge (relayed
             * RTCP carries no endpoint id). There is no receiver to attribute it to, so skip only the per-receiver
             * limit; the source-wide limit still applies, since it is what protects the sender's encoder and this
             * is the only bridge that sees every requester for the source. */
            val perReceiverLimiter = requesterID?.let { requester ->
                perReceiverKeyframeLimiter.computeIfAbsent(requester) { mutableMapOf() }
                    .computeIfAbsent(mediaSsrc) {
                        RateLimit(
                            defaultMinInterval = minInterval,
                            maxRequests = maxRequests,
                            interval = maxRequestInterval
                        )
                    }
            }
            if (perReceiverLimiter != null && !perReceiverLimiter.wouldAccept(now, waitInterval)) {
                numRequestsDroppedPerReceiverLimit++
                logger.cdebug {
                    "Ignoring keyframe request for $mediaSsrc from $requesterID, per-receiver rate limited"
                }
                return false
            }

            val perSourceLimiter = perSourceKeyframeLimiter.computeIfAbsent(mediaSsrc) {
                RateLimit(
                    defaultMinInterval = sourceWideMinInterval,
                    maxRequests = sourceWideMaxRequests,
                    interval = sourceWideMaxRequestInterval
                )
            }
            /* The source-wide interval is the floor, lengthened by the keyframe budget as computed when the previous
             * request for this source was sent. The floor is applied here rather than when the budget is computed, so
             * that a change to it between requests takes effect at once. */
            val interval = lastSourceWideLimits[mediaSsrc]?.budgetInterval?.let { maxOf(it, floor) } ?: floor
            if (!perSourceLimiter.wouldAccept(now, interval)) {
                numRequestsDroppedSourceWideLimit++
                if (interval > floor && perSourceLimiter.wouldAccept(now, floor)) {
                    numRequestsDroppedByBudget++
                    if (apiTriggered) numRequestsDroppedByBudgetApi++
                    budgetWaitStarts.computeIfAbsent(mediaSsrc) { mutableMapOf() }
                        .putIfAbsent(requesterID, PendingBudgetWait(now, apiTriggered))
                }
                logger.cdebug { "Ignoring keyframe request for $mediaSsrc from $requesterID, per-source rate limited" }
                return false
            }

            /* Both limits accept, so record the request with both only now. A receiver waiting for a keyframe
             * re-requests on every packet, so if requests dropped by the source-wide limit counted against its
             * per-receiver limit it would exhaust that limit while the source-wide one is closed, and then be unable
             * to request again for max-request-interval after the source-wide limit reopens. */
            perReceiverLimiter?.record(now)
            perSourceLimiter.record(now)

            if (interval > floor) {
                numRequestsSentBudgetLengthened++
                if (apiTriggered) numRequestsSentBudgetLengthenedApi++
                val extensionMs = (interval - floor).toMillis()
                totalBudgetExtensionMs += extensionMs
                if (apiTriggered) totalBudgetExtensionMsApi += extensionMs
            } else {
                numRequestsSentAtFloor++
                if (apiTriggered) numRequestsSentAtFloorApi++
            }
            /* This one request satisfies every receiver waiting on this source, not just the one which triggered it,
             * so every requester recorded as waiting is resolved here, each with its own wait time. */
            budgetWaitStarts.remove(mediaSsrc)?.values?.forEach { wait ->
                val waitMs = Duration.between(wait.since, now).toMillis()
                numBudgetWaits++
                totalBudgetWaitMs += waitMs
                maxBudgetWaitMs = maxOf(maxBudgetWaitMs, waitMs)
                if (wait.apiTriggered) {
                    numBudgetWaitsApi++
                    totalBudgetWaitMsApi += waitMs
                    maxBudgetWaitMsApi = maxOf(maxBudgetWaitMsApi, waitMs)
                }
            }
        }

        /* Compute the limit to apply to the next request for this source now, once per request sent, so that the
         * cost lookup is off the per-packet request path. Outside the lock, since it calls into the receive
         * pipeline's measurements. */
        if (KeyframeBudgetConfig.enabled) {
            lastSourceWideLimits[mediaSsrc] = sourceWideLimit(mediaSsrc)
        }

        logger.cdebug { "Keyframe requester requesting keyframe for $mediaSsrc, requested by $requesterID" }
        return true
    }

    /**
     * The interval the keyframe budget calls for between keyframe requests for [mediaSsrc], from any receiver: with a
     * measured keyframe cost available for the source, the interval at which keyframes requested at that rate cost
     * [KeyframeBudgetConfig.maxBitrateFraction] of the source's current bitrate, capped at
     * [KeyframeBudgetConfig.maxInterval]. The configured floor is applied when the limit is checked, so the interval
     * actually enforced is never shorter than the floor, and never longer than max-interval unless the floor itself
     * is.
     */
    private fun sourceWideLimit(mediaSsrc: Long): SourceWideLimit {
        val cost = keyframeCostSupplier?.invoke(mediaSsrc)
        val impliedInterval = cost?.intervalAt(KeyframeBudgetConfig.maxBitrateFraction)
        return SourceWideLimit(impliedInterval?.coerceAtMost(KeyframeBudgetConfig.maxInterval), cost, impliedInterval)
    }

    /**
     * Set the source of measured keyframe costs used by [sourceWideLimit] when keyframe budget limiting is enabled.
     * The supplier is called with the SSRC a keyframe was requested for.
     */
    fun setKeyframeCostSupplier(supplier: (Long) -> KeyframeCost?) {
        keyframeCostSupplier = supplier
    }

    fun requestKeyframe(requesterID: String?, mediaSsrc: Long? = null) {
        val ssrc = mediaSsrc ?: streamInformationStore.primaryMediaSsrcs.firstOrNull() ?: run {
            numApiRequestsDropped++
            logger.cdebug { "No video SSRC found to request keyframe" }
            return
        }
        numApiRequests++
        if (!canSendKeyframeRequest(requesterID, ssrc, clock.instant(), apiTriggered = true)) {
            numApiRequestsDropped++
            return
        }

        doRequestKeyframe(ssrc)
    }

    private fun doRequestKeyframe(mediaSsrc: Long) {
        val pkt = when {
            streamInformationStore.supportsPli -> {
                numPlisGenerated++
                RtcpFbPliPacketBuilder(mediaSourceSsrc = mediaSsrc).build()
            }

            streamInformationStore.supportsFir -> {
                numFirsGenerated++
                RtcpFbFirPacketBuilder(
                    mediaSenderSsrc = mediaSsrc,
                    firCommandSeqNum = firCommandSequenceNumber.incrementAndGet()
                ).build()
            }

            else -> {
                logger.warn("Can not send neither PLI nor FIR")
                return
            }
        }

        next(PacketInfo(pkt))
    }

    override fun handleEvent(event: Event) {
        when (event) {
            is SetLocalSsrcEvent -> {
                if (event.mediaType == MediaType.VIDEO) {
                    localSsrc = event.ssrc
                }
            }
        }
    }

    override fun trace(f: () -> Unit) = f.invoke()

    override fun getNodeStats(): NodeStatsBlock = super.getNodeStats().apply {
        addNumber("wait_interval_ms", waitInterval.toMillis())
        addNumber("num_api_requests", numApiRequests)
        addNumber("num_api_requests_dropped", numApiRequestsDropped)
        addNumber("num_firs_dropped", numFirsDropped)
        addNumber("num_firs_generated", numFirsGenerated)
        addNumber("num_firs_forwarded", numFirsForwarded)
        addNumber("num_plis_dropped", numPlisDropped)
        addNumber("num_plis_generated", numPlisGenerated)
        addNumber("num_plis_forwarded", numPlisForwarded)
        addNumber("num_requests_dropped_per_receiver_limit", numRequestsDroppedPerReceiverLimit)
        addNumber("num_requests_dropped_source_wide_limit", numRequestsDroppedSourceWideLimit)
        addBoolean("keyframe_budget_enabled", KeyframeBudgetConfig.enabled)
        addNumber("num_requests_sent_at_floor", numRequestsSentAtFloor)
        addNumber("num_requests_sent_budget_lengthened", numRequestsSentBudgetLengthened)
        addNumber("total_budget_extension_ms", totalBudgetExtensionMs)
        addNumber("num_requests_dropped_by_budget", numRequestsDroppedByBudget)
        addNumber("num_budget_waits", numBudgetWaits)
        addNumber("total_budget_wait_ms", totalBudgetWaitMs)
        addNumber("max_budget_wait_ms", maxBudgetWaitMs)
        addNumber("num_requests_sent_at_floor_api", numRequestsSentAtFloorApi)
        addNumber("num_requests_sent_budget_lengthened_api", numRequestsSentBudgetLengthenedApi)
        addNumber("total_budget_extension_ms_api", totalBudgetExtensionMsApi)
        addNumber("num_requests_dropped_by_budget_api", numRequestsDroppedByBudgetApi)
        addNumber("num_budget_waits_api", numBudgetWaitsApi)
        addNumber("total_budget_wait_ms_api", totalBudgetWaitMsApi)
        addNumber("max_budget_wait_ms_api", maxBudgetWaitMsApi)
        lastSourceWideLimits.forEach { (ssrc, limit) ->
            addJson("source_wide_limit_$ssrc", limit.toJson())
        }
        budgetWaitStarts.forEach { (ssrc, waiters) ->
            addNumber("budget_waiters_$ssrc", waiters.size)
        }
    }

    override fun statsJson() = super.statsJson().apply {
        put("num_api_requests", numApiRequests)
        put("num_api_requests_dropped", numApiRequestsDropped)
        put("num_firs_dropped", numFirsDropped)
        put("num_firs_generated", numFirsGenerated)
        put("num_firs_forwarded", numFirsForwarded)
        put("num_plis_dropped", numPlisDropped)
        put("num_plis_generated", numPlisGenerated)
        put("num_plis_forwarded", numPlisForwarded)
        put("num_requests_dropped_per_receiver_limit", numRequestsDroppedPerReceiverLimit)
        put("num_requests_dropped_source_wide_limit", numRequestsDroppedSourceWideLimit)
        put("keyframe_budget_enabled", KeyframeBudgetConfig.enabled)
        put("num_requests_sent_at_floor", numRequestsSentAtFloor)
        put("num_requests_sent_budget_lengthened", numRequestsSentBudgetLengthened)
        put("total_budget_extension_ms", totalBudgetExtensionMs)
        put("num_requests_dropped_by_budget", numRequestsDroppedByBudget)
        put("num_budget_waits", numBudgetWaits)
        put("total_budget_wait_ms", totalBudgetWaitMs)
        put("max_budget_wait_ms", maxBudgetWaitMs)
        put("num_requests_dropped_by_budget_api", numRequestsDroppedByBudgetApi)
        put("num_budget_waits_api", numBudgetWaitsApi)
        put("total_budget_wait_ms_api", totalBudgetWaitMsApi)
        put("max_budget_wait_ms_api", maxBudgetWaitMsApi)
    }

    /**
     * A snapshot of this requester's cumulative keyframe budget counters, for aggregation into bridge-wide metrics.
     * Only the counters needed there are included; the rest remain available via [getNodeStats].
     */
    fun getKeyframeBudgetStats() = KeyframeRequesterStats(
        numRequestsDroppedByBudget = numRequestsDroppedByBudget,
        numRequestsDroppedByBudgetApi = numRequestsDroppedByBudgetApi,
        numBudgetWaits = numBudgetWaits,
        numBudgetWaitsApi = numBudgetWaitsApi,
        totalBudgetWaitMs = totalBudgetWaitMs,
        totalBudgetWaitMsApi = totalBudgetWaitMsApi
    )

    fun onRttUpdate(newRtt: Double) {
        // avg(rtt) + stddev(rtt) would be more accurate than rtt + 10.
        waitInterval = min(minInterval, durationOfDoubleSeconds((newRtt + 10) / 1e3))
    }

    companion object {
        private val minInterval: Duration by config {
            "jmt.keyframe.min-interval".from(JitsiConfig.newConfig)
        }
        private val maxRequests: Int by config {
            "jmt.keyframe.max-requests".from(JitsiConfig.newConfig)
        }
        private val maxRequestInterval: Duration by config {
            "jmt.keyframe.max-request-interval".from(JitsiConfig.newConfig)
        }

        private val sourceWideMinInterval: Duration by config {
            "jmt.keyframe.source-wide-min-interval".from(JitsiConfig.newConfig)
        }
        private val sourceWideMaxRequests: Int by config {
            "jmt.keyframe.source-wide-max-requests".from(JitsiConfig.newConfig)
        }
        private val sourceWideMaxRequestInterval: Duration by config {
            "jmt.keyframe.source-wide-max-request-interval".from(JitsiConfig.newConfig)
        }
    }
}

/** When a requester was first dropped by the budget, and whether that request was bridge-generated. */
private data class PendingBudgetWait(val since: Instant, val apiTriggered: Boolean)

/**
 * A snapshot of [KeyframeRequester]'s cumulative keyframe budget counters, for aggregation into bridge-wide metrics.
 * The `Api` counters are the subset of each which came from a request the bridge generated itself (a layer switch
 * needing a keyframe, or a dominant-speaker switch) rather than one forwarded from a receiver's own PLI/FIR.
 */
data class KeyframeRequesterStats(
    val numRequestsDroppedByBudget: Int,
    val numRequestsDroppedByBudgetApi: Int,
    val numBudgetWaits: Int,
    val numBudgetWaitsApi: Int,
    val totalBudgetWaitMs: Long,
    val totalBudgetWaitMsApi: Long
)

/**
 * What the keyframe budget calls for on the next request for one source: the interval it calls for, if a cost was
 * available, after the cap but before the floor; the keyframe cost it was derived from; and the interval that cost
 * implied before the cap.
 */
private class SourceWideLimit(val budgetInterval: Duration?, val cost: KeyframeCost?, val impliedInterval: Duration?) {
    fun toJson(): ObjectNode = JsonNodeFactory.instance.objectNode().apply {
        budgetInterval?.let { put("budget_interval_ms", it.toMillis()) }
        impliedInterval?.let { put("implied_interval_ms", it.toMillis()) }
        cost?.let {
            set<ObjectNode>("keyframe_cost", it.toJson())
            budgetInterval?.let { interval -> put("keyframe_fraction_at_interval", it.keyframeFractionAt(interval)) }
        }
    }
}

private fun PacketInfo.getPliOrFirPacket(): RtcpFbPacket? = when (val pkt = packet) {
    // We intentionally ignore compound RTCP packets in order to avoid unnecessary parsing. We can do this because:
    // 1. Compound packets coming from remote endpoint are terminated in RtcpTermination
    // 2. Whenever a PLI or FIR is generated in our code, it is not part of a compound packet.
    is RtcpFbFirPacket -> pkt

    is RtcpFbPliPacket -> pkt

    else -> null
}
