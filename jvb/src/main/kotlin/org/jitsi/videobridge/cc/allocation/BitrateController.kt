/*
 * Copyright @ 2020 - present 8x8, Inc.
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

import org.jitsi.nlj.MediaSourceDesc
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.format.PayloadType
import org.jitsi.nlj.format.PayloadTypeEncoding
import org.jitsi.nlj.util.bps
import org.jitsi.rtp.rtcp.RtcpSrPacket
import org.jitsi.utils.event.EventEmitter
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging.TimeSeriesLogger
import org.jitsi.utils.logging2.Logger
import org.jitsi.videobridge.cc.config.BitrateControllerConfig
import org.jitsi.videobridge.message.ReceiverVideoConstraintsMessage
import org.jitsi.videobridge.util.BooleanStateTimeTracker
import org.json.simple.JSONObject
import java.time.Clock
import java.time.Duration
import java.util.function.Supplier

/**
 * [BitrateController] is responsible for controlling the send bitrate to an `Endpoint`. This includes two tasks:
 * 1. Decide how to allocate the available bandwidth between the available streams.
 * 2. Implement the allocation via a packet handling interface.
 *
 * Historically both were implemented in a single class, but they are now split between [BandwidthAllocator] (for
 * the allocation) and [BitrateControllerPacketHandler] (for packet handling). This class was introduced as a
 * lightweight shim in order to preserve the previous API.
 *
 */
class BitrateController<T : MediaSourceContainer> @JvmOverloads constructor(
    eventHandler: EventHandler,
    endpointsSupplier: Supplier<List<T>>,
    private val diagnosticContext: DiagnosticContext,
    parentLogger: Logger,
    private val clock: Clock = Clock.systemUTC()
) {
    val eventEmitter = EventEmitter<EventHandler>()

    private val bitrateAllocatorEventHandler = BitrateAllocatorEventHandler()
    /**
     * Keep track of the "forwarded" endpoints, i.e. the endpoints for which we are forwarding *some* layer.
     */
    private var forwardedEndpoints: Set<String> = emptySet()

    /**
     * Keep track of how much time we spend knowingly oversending (due to enableOnstageVideoSuspend being false)
     */
    val oversendingTimeTracker = BooleanStateTimeTracker()

    val timeSeriesLogger = TimeSeriesLogger.getTimeSeriesLogger(BitrateController::class.java).let {
        if (it.isTraceEnabled) it else null
    }

    /**
     * NOTE(george): this flag acts as an approximation for determining whether or not adaptivity/probing is
     * supported. Eventually we need to scrap this and implement something cleaner, i.e. disable adaptivity if the
     * endpoint hasn't signaled `goog-remb` nor `transport-cc`.
     *
     * Unfortunately the channel iq from jicofo lists `goog-remb` and `transport-cc` support, even tho the jingle from
     * firefox doesn't (which is the main use case for wanting to disable adaptivity).
     */
    private var supportsRtx = false

    private val packetHandler: BitrateControllerPacketHandler =
        BitrateControllerPacketHandler(clock, parentLogger, diagnosticContext, eventEmitter)
    private val bandwidthAllocator: BandwidthAllocator<T> =
        BandwidthAllocator(
            bitrateAllocatorEventHandler,
            endpointsSupplier,
            Supplier { trustBwe },
            parentLogger,
            diagnosticContext,
            clock
        )

    private val allocationSettingsWrapper = AllocationSettingsWrapper()
    val allocationSettings
        get() = allocationSettingsWrapper.get()

    init {
        eventEmitter.addHandler(eventHandler)
    }

    /**
     * Ignore the bandwidth estimations in the first 10 seconds because the REMBs don't ramp up fast enough. This needs
     * to go but it's related to our GCC implementation that needs to be brought up to speed.
     * TODO: Is this comment still accurate?
     */
    private val trustBwe: Boolean
        get() = BitrateControllerConfig.trustBwe() && supportsRtx && packetHandler.timeSinceFirstMedia() >= 10000

    // Proxy to the allocator
    fun endpointOrderingChanged() = bandwidthAllocator.update()
    var lastN: Int
        get() = allocationSettingsWrapper.lastN
        set(value) {
            if (allocationSettingsWrapper.setLastN(value)) {
                bandwidthAllocator.update(allocationSettingsWrapper.get())
            }
        }

    fun setMaxFrameHeight(maxFrameHeight: Int) {
        if (allocationSettingsWrapper.setMaxFrameHeight(maxFrameHeight)) {
            bandwidthAllocator.update(allocationSettingsWrapper.get())
        }
    }
    fun setSelectedEndpoints(selectedEndpoints: List<String>) {
        if (allocationSettingsWrapper.setSelectedEndpoints(selectedEndpoints)) {
            bandwidthAllocator.update(allocationSettingsWrapper.get())
        }
    }

    /**
     * Return the number of endpoints whose streams are currently being forwarded.
     */
    fun numForwardedEndpoints(): Int = forwardedEndpoints.size
    fun getTotalOversendingTime(): Duration = oversendingTimeTracker.totalTimeOn()
    fun isOversending() = oversendingTimeTracker.state
    fun bandwidthChanged(newBandwidthBps: Long) {
        timeSeriesLogger?.logBweChange(newBandwidthBps)
        bandwidthAllocator.bandwidthChanged(newBandwidthBps)
    }

    // Proxy to the packet handler
    fun accept(packetInfo: PacketInfo): Boolean = packetHandler.accept(packetInfo)
    fun accept(rtcpSrPacket: RtcpSrPacket?): Boolean {
        // TODO: It is not clear why this is here, and why it isn't in the other accept() method.
        bandwidthAllocator.maybeUpdate()

        return packetHandler.accept(rtcpSrPacket)
    }
    fun transformRtcp(rtcpSrPacket: RtcpSrPacket?): Boolean = packetHandler.transformRtcp(rtcpSrPacket)
    fun transformRtp(packetInfo: PacketInfo): Boolean = packetHandler.transformRtp(packetInfo)

    val debugState: JSONObject = JSONObject().apply {
        put("bitrate_allocator", bandwidthAllocator.debugState)
        put("packet_handler", packetHandler.debugState)
        put("forwardedEndpoints", forwardedEndpoints.toString())
        put("oversending", oversendingTimeTracker.state)
        put("total_oversending_time_secs", oversendingTimeTracker.totalTimeOn().seconds)
        put("supportsRtx", supportsRtx)
        put("trust_bwe", trustBwe)
    }

    fun addPayloadType(payloadType: PayloadType) {
        packetHandler.addPayloadType(payloadType)

        if (payloadType.encoding == PayloadTypeEncoding.RTX) {
            supportsRtx = true
        }
    }

    fun setBandwidthAllocationSettings(message: ReceiverVideoConstraintsMessage) {
        if (allocationSettingsWrapper.setBandwidthAllocationSettings(message)) {
            bandwidthAllocator.update(allocationSettingsWrapper.get())
        }
    }

    /**
     * Get the target and ideal bitrate of the current [BandwidthAllocation], as well as the list of SSRCs being
     * forwarded, for use in probing.
     *
     * Note that the ideal layers are calculated with the allocation, and inactive layers are not considered. So when a
     * higher layer becomes active, it will not be accounted for until until the allocation updates. Conversely, if the
     * ideal layer becomes inactive, it will contribute 0 bps to the total ideal bitrate until the allocation updates
     * and a lower layer is selected as idea.
     */
    fun getStatusSnapshot(): BitrateControllerStatusSnapshot {
        var totalTargetBitrate = 0.bps
        var totalIdealBitrate = 0.bps
        val activeSsrcs = mutableSetOf<Long>()

        val nowMs = clock.instant().toEpochMilli()
        val allocation = bandwidthAllocator.allocation
        allocation.allocations.forEach {
            it.targetLayer?.getBitrate(nowMs)?.let { targetBitrate ->
                totalTargetBitrate += targetBitrate
                it.source?.primarySSRC?.let { primarySsrc -> activeSsrcs.add(primarySsrc) }
            }
            it.idealLayer?.getBitrate(nowMs)?.let { idealBitrate ->
                totalIdealBitrate += idealBitrate
            }
        }

        activeSsrcs.removeIf { it < 0 }

        return BitrateControllerStatusSnapshot(
            currentTargetBps = totalTargetBitrate.bps.toLong(),
            currentIdealBps = totalIdealBitrate.bps.toLong(),
            activeSsrcs = activeSsrcs
        )
    }

    private fun TimeSeriesLogger.logBweChange(newBweBps: Long) {
        trace(diagnosticContext.makeTimeSeriesPoint("new_bwe").addField("bwe_bps", newBweBps))
    }

    private fun TimeSeriesLogger.logAllocationChange(allocation: BandwidthAllocation) {
        val nowMs = clock.millis()

        var totalTargetBps = 0.0
        var totalIdealBps = 0.0

        allocation.allocations.forEach {
            it.targetLayer?.getBitrate(nowMs)?.let { bitrate -> totalTargetBps += bitrate.bps }
            it.idealLayer?.getBitrate(nowMs)?.let { bitrate -> totalIdealBps += bitrate.bps }
            trace(
                diagnosticContext
                    .makeTimeSeriesPoint("allocation_for_source", nowMs)
                    .addField("remote_endpoint_id", it.endpointId)
                    .addField("target_idx", it.targetLayer?.index ?: -1)
                    .addField("ideal_idx", it.idealLayer?.index ?: -1)
                    .addField("target_bps", it.targetLayer?.getBitrate(nowMs)?.bps ?: -1)
                    .addField("ideal_bps", it.idealLayer?.getBitrate(nowMs)?.bps ?: -1)
            )
        }

        trace(
            diagnosticContext
                .makeTimeSeriesPoint("allocation", nowMs)
                .addField("total_target_bps", totalTargetBps)
                .addField("total_ideal_bps", totalIdealBps)
        )
    }

    interface EventHandler {
        fun forwardedEndpointsChanged(forwardedEndpoints: Set<String>)
        fun effectiveVideoConstraintsChanged(
            oldEffectiveConstraints: Map<String, VideoConstraints>,
            newEffectiveConstraints: Map<String, VideoConstraints>
        )
        fun keyframeNeeded(endpointId: String?, ssrc: Long)
        /**
         * This is meant to be internal to BitrateAllocator, but is exposed here temporarily for the purposes of testing.
         */
        fun allocationChanged(allocation: BandwidthAllocation) { }
    }

    private inner class BitrateAllocatorEventHandler : BandwidthAllocator.EventHandler {
        override fun allocationChanged(allocation: BandwidthAllocation) {
            timeSeriesLogger?.logAllocationChange(allocation)
            // Actually implement the allocation (configure the packet filter to forward the chosen target layers).
            packetHandler.allocationChanged(allocation)

            // TODO(george) bring back sending this message on message transport  connect
            val newForwardedEndpoints = allocation.forwardedEndpoints
            if (forwardedEndpoints != newForwardedEndpoints) {
                forwardedEndpoints = newForwardedEndpoints
                eventEmitter.fireEventSync { forwardedEndpointsChanged(newForwardedEndpoints) }
            }

            oversendingTimeTracker.setState(allocation.oversending)

            // TODO: this is for testing only. Should we change the tests to work with [BitrateAllocator] directly?
            eventEmitter.fireEventSync { allocationChanged(allocation) }
        }

        override fun effectiveVideoConstraintsChanged(
            oldEffectiveConstraints: Map<String, VideoConstraints>,
            newEffectiveConstraints: Map<String, VideoConstraints>
        ) {
            // Forward to the outer EventHandler.
            eventEmitter.fireEventSync {
                effectiveVideoConstraintsChanged(oldEffectiveConstraints, newEffectiveConstraints)
            }
        }
    }
}

/**
 * Abstracts a source endpoint for the purposes of [BandwidthAllocator].
 */
interface MediaSourceContainer {
    val id: String
    val mediaSources: Array<MediaSourceDesc>?
}

data class BitrateControllerStatusSnapshot(
    val currentTargetBps: Long = -1L,
    val currentIdealBps: Long = -1L,
    val activeSsrcs: Collection<Long> = emptyList()
)
