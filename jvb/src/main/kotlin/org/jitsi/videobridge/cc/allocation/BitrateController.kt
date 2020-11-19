package org.jitsi.videobridge.cc.allocation

import com.google.common.collect.ImmutableMap
import org.jitsi.nlj.MediaSourceDesc
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.format.PayloadType
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
    clock: Clock = Clock.systemUTC()
) {
    val eventEmitter = EventEmitter<EventHandler>()

    private val packetHandler: BitrateControllerPacketHandler =
        BitrateControllerPacketHandler(clock, parentLogger, diagnosticContext, eventEmitter)
    private val bitrateAllocator: BitrateAllocator<T> =
        BitrateAllocator(
            destinationEndpointId,
            eventHandler,
            endpointsSupplier,
            diagnosticContext,
            parentLogger,
            clock,
            packetHandler
        )

    init {
        eventEmitter.addHandler(eventHandler)
    }

    // Proxy to the allocator
    fun getStatusSnapshot(): BitrateControllerStatusSnapshot = bitrateAllocator.statusSnapshot
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
    fun numForwardedEndpoints(): Int = bitrateAllocator.numForwardedEndpoints()
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
    }

    fun addPayloadType(payloadType: PayloadType) {
        packetHandler.addPayloadType(payloadType)
        bitrateAllocator.addPayloadType(payloadType)
    }

    interface EventHandler {
        fun forwardedEndpointsChanged(forwardedEndpoints: Collection<String>)
        fun effectiveVideoConstraintsChanged(
            oldVideoConstraints: ImmutableMap<String, VideoConstraints>,
            newVideoConstraints: ImmutableMap<String, VideoConstraints>
        )
        fun keyframeNeeded(endpointId: String?, ssrc: Long)
        /**
         * This is meant to be internal to BitrateAllocator, but is exposed here temporarily for the purposes of testing.
         */
        fun allocationChanged(allocation: List<SingleSourceAllocation>) { }
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
