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

import com.google.common.collect.ImmutableMap
import org.jitsi.nlj.MediaSourceDesc
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.format.PayloadType
import org.jitsi.nlj.util.bps
import org.jitsi.rtp.rtcp.RtcpSrPacket
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging2.Logger
import org.jitsi.videobridge.VideoConstraints
import org.jitsi.videobridge.util.EventEmitter
import org.json.simple.JSONObject
import java.time.Clock
import java.time.Duration
import java.util.function.Supplier

/**
 * [BitrateController] is responsible for controlling the send bitrate to an `Endpoint`. This includes two tasks:
 * 1. Decide how to allocate the available bandwidth between the available streams.
 * 2. Implement the allocation via a packet handling interface.
 *
 * Historically both were implemented in a single class, but they are now split between [BitrateAllocator] (for
 * the allocation) and [BitrateControllerPacketHandler] (for packet handling). This class was introduced as a
 * lightweight shim in order to preserve the previous API.
 *
 */
class BitrateController<T : MediaSourceContainer> @JvmOverloads constructor(
    destinationEndpointId: String,
    eventHandler: EventHandler,
    endpointsSupplier: Supplier<List<T>>,
    diagnosticContext: DiagnosticContext,
    parentLogger: Logger,
    private val clock: Clock = Clock.systemUTC()
) {
    val eventEmitter = EventEmitter<EventHandler>()

    private val bitrateAllocatorEventHandler = BitrateAllocatorEventHandler()
    /**
     * Keep track of the "forwarded" endpoints, i.e. the endpoints for which we are forwarding *some* layer.
     */
    private var forwardedEndpoints: Set<String> = emptySet()

    private val packetHandler: BitrateControllerPacketHandler =
        BitrateControllerPacketHandler(clock, parentLogger, diagnosticContext, eventEmitter)
    private val bitrateAllocator: BitrateAllocator<T> =
        BitrateAllocator(
            destinationEndpointId,
            bitrateAllocatorEventHandler,
            endpointsSupplier,
            parentLogger,
            clock,
            packetHandler
        )

    init {
        eventEmitter.addHandler(eventHandler)
    }

    // Proxy to the allocator
    fun endpointOrderingChanged(conferenceEndpoints: List<String>) =
        bitrateAllocator.endpointOrderingChanged(conferenceEndpoints)
    var lastN: Int
        get() = bitrateAllocator.lastN
        set(value) {
            bitrateAllocator.lastN = value
        }
    fun setVideoConstraints(newVideoConstraintsMap: ImmutableMap<String, VideoConstraints>) =
        bitrateAllocator.setVideoConstraints(newVideoConstraintsMap)
    /**
     * Return the number of endpoints whose streams are currently being forwarded.
     */
    fun numForwardedEndpoints(): Int = forwardedEndpoints.size
    fun getTotalOversendingTime(): Duration = bitrateAllocator.oversendingTimeTracker.totalTimeOn()
    fun isOversending() = bitrateAllocator.oversendingTimeTracker.state
    fun bandwidthChanged(newBandwidthBps: Long) = bitrateAllocator.bandwidthChanged(newBandwidthBps)

    // Proxy to the packet handler
    fun accept(packetInfo: PacketInfo): Boolean = packetHandler.accept(packetInfo)
    fun accept(rtcpSrPacket: RtcpSrPacket?): Boolean {
        // TODO: It is not clear why this is here, and why it isn't in the other accept() method.
        bitrateAllocator.maybeUpdate()

        return packetHandler.accept(rtcpSrPacket)
    }
    fun transformRtcp(rtcpSrPacket: RtcpSrPacket?): Boolean = packetHandler.transformRtcp(rtcpSrPacket)
    fun transformRtp(packetInfo: PacketInfo): Boolean = packetHandler.transformRtp(packetInfo)

    val debugState: JSONObject = JSONObject().apply {
        put("bitrate_allocator", bitrateAllocator.debugState)
        put("packet_handler", packetHandler.debugState)
        put("forwardedEndpoints", forwardedEndpoints.toString())
    }

    fun addPayloadType(payloadType: PayloadType) {
        packetHandler.addPayloadType(payloadType)
        bitrateAllocator.addPayloadType(payloadType)
    }

    /**
     * Get the target and ideal bitrate of the current [Allocation], as well as the list of SSRCs being forwarded.
     */
    fun getStatusSnapshot(): BitrateControllerStatusSnapshot {
        var totalTargetBitrate = 0.bps
        var totalIdealBitrate = 0.bps
        val activeSsrcs = mutableSetOf<Long>()

        val nowMs = clock.instant().toEpochMilli()
        val allocation = bitrateAllocator.allocation
        allocation.allocations.forEach {
            // Note that we access `targetLayer.layer` for the *current* bitrate of the layer as opposed to
            // `targetLayer.bitrate` which is the bitrate at the time of allocation.
            it.targetLayer?.layer?.getBitrate(nowMs)?.let { targetBitrate ->
                totalTargetBitrate += targetBitrate
                it.source?.primarySSRC?.let { primarySsrc -> activeSsrcs.add(primarySsrc) }
            }
            it.idealLayer?.layer?.getBitrate(nowMs)?.let { idealBitrate ->
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

    interface EventHandler {
        fun forwardedEndpointsChanged(forwardedEndpoints: Set<String>)
        fun effectiveVideoConstraintsChanged(
            oldVideoConstraints: ImmutableMap<String, VideoConstraints>,
            newVideoConstraints: ImmutableMap<String, VideoConstraints>
        )
        fun keyframeNeeded(endpointId: String?, ssrc: Long)
        /**
         * This is meant to be internal to BitrateAllocator, but is exposed here temporarily for the purposes of testing.
         */
        fun allocationChanged(allocation: Allocation) { }
    }

    private inner class BitrateAllocatorEventHandler: BitrateAllocator.EventHandler {
        override fun allocationChanged(allocation: Allocation) {
            // Actually implement the allocation (configure the packet filter to forward the chosen target layers).
            packetHandler.allocationChanged(allocation)

            // TODO(george) bring back sending this message on message transport
            //  connect
            val newForwardedEndpoints = allocation.forwardedEndpoints
            if (forwardedEndpoints != newForwardedEndpoints) {
                forwardedEndpoints = newForwardedEndpoints
                eventEmitter.fireEvent { forwardedEndpointsChanged(newForwardedEndpoints) }
            }

            // TODO: this is for testing only. Shold we change the tests to work with [BitrateAllocator] directly?
            eventEmitter.fireEvent { allocationChanged(allocation) }
        }

        override fun effectiveVideoConstraintsChanged(
            oldEffectiveVideoConstraints: ImmutableMap<String, VideoConstraints>,
            newEffectiveVideoConstraints: ImmutableMap<String, VideoConstraints>
        ) {
            // Forward to the outer EventHandler.
            eventEmitter.fireEvent {
                effectiveVideoConstraintsChanged(oldEffectiveVideoConstraints, newEffectiveVideoConstraints)
            }
        }
    }
}

/**
 * Abstracts a source endpoint for the purposes of [BitrateAllocator].
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
