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

import org.jitsi.nlj.DebugStateMode
import org.jitsi.nlj.MediaSourceDesc
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.PacketInfo.Companion.enablePayloadVerification
import org.jitsi.nlj.RtpLayerDesc
import org.jitsi.nlj.rtp.VideoRtpPacket
import org.jitsi.rtp.rtcp.RtcpSrPacket
import org.jitsi.utils.event.EventEmitter
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.videobridge.cc.AdaptiveSourceProjection
import org.jitsi.videobridge.cc.RewriteException
import org.json.simple.JSONObject
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Handles packets for a [BitrateController], implementing a specific [BandwidthAllocation] provided via
 * [allocationChanged]. Serves as a bridge between [BitrateController] (which decides which layers are to be forwarded)
 * and [AdaptiveSourceProjection] which processes packets and maintains the state of a forwarded stream.
 *
 * Defines "accept" and "transform" functions for video RTP packets and RTCP Sender Reports.
 */
internal class PacketHandler(
    private val clock: Clock,
    parentLogger: Logger,
    private val diagnosticContext: DiagnosticContext,
    private val eventEmitter: EventEmitter<BitrateController.EventHandler>
) {
    private val logger = createChildLogger(parentLogger)

    /**
     * The time (in ms) when this instance first transformed any media. This allows to ignore the BWE during the early
     * stages of the call.
     *
     * NOTE This is only meant to be as a temporary hack and ideally should be fixed.
     */
    private var firstMedia: Instant? = null

    private val numDroppedPacketsUnknownSsrc = AtomicInteger(0)

    /**
     * The [AdaptiveSourceProjection]s that this instance is managing, keyed
     * by the SSRCs of the associated [MediaSourceDesc].
     */
    private val adaptiveSourceProjectionMap: MutableMap<Long, AdaptiveSourceProjection> = ConcurrentHashMap()

    /**
     * @return true if the packet was transformed successfully, false otherwise.
     */
    fun transformRtp(
        /** Contains the video RTP packet to be transformed */
        packetInfo: PacketInfo
    ): Boolean {
        val videoPacket = packetInfo.packetAs<VideoRtpPacket>()

        if (firstMedia == null) {
            firstMedia = clock.instant()
        }

        val adaptiveSourceProjection = adaptiveSourceProjectionMap[videoPacket.ssrc] ?: return false
        return try {
            adaptiveSourceProjection.rewriteRtp(packetInfo)

            // The rewriteRtp operation must not modify the VP8 payload.
            if (enablePayloadVerification) {
                val expected = packetInfo.payloadVerification
                val actual = videoPacket.payloadVerification
                if ("" != expected && expected != actual) {
                    logger.warn("Payload unexpectedly modified! Expected: $expected, actual: $actual")
                }
            }
            true
        } catch (e: RewriteException) {
            logger.warn("Failed to rewrite a packet.", e)
            false
        }
    }

    /**
     * @return true if a packet should be accepted according to the current configuration.
     */
    fun accept(packetInfo: PacketInfo): Boolean {
        val videoPacket = packetInfo.packetAs<VideoRtpPacket>()
        val adaptiveSourceProjection = adaptiveSourceProjectionMap[videoPacket.ssrc]
        if (adaptiveSourceProjection == null) {
            logger.debug { "Dropping an RTP packet for an unknown SSRC: ${videoPacket.ssrc}" }
            numDroppedPacketsUnknownSsrc.incrementAndGet()
            return false
        }
        return adaptiveSourceProjection.accept(packetInfo)
    }

    /**
     * @return true if [rtcpSrPacket] should be accepted, false otherwise.
     *
     * Filters out packets that match one of the streams that this instance manages, but don't match the target SSRC.
     * Allows packets for streams not managed by this instance.
     */
    fun accept(rtcpSrPacket: RtcpSrPacket): Boolean {
        val adaptiveSourceProjection = adaptiveSourceProjectionMap[rtcpSrPacket.senderSsrc]

        // This is probably for an audio stream. In any case, if it's for a stream which we are not forwarding it
        // will be stripped off at a later stage (in RtcpSrUpdater).
        return adaptiveSourceProjection == null ||
            // We only accept SRs for the SSRC that we're forwarding with.
            adaptiveSourceProjection.targetSsrc == rtcpSrPacket.senderSsrc
    }

    /**
     * @return true if the packet was transformed successfully, false otherwise.
     */
    fun transformRtcp(rtcpSrPacket: RtcpSrPacket): Boolean =
        adaptiveSourceProjectionMap[rtcpSrPacket.senderSsrc]?.rewriteRtcp(rtcpSrPacket) ?: false

    /**
     * Utility method that looks-up or creates the adaptive source projection of
     * a source.
     */
    private fun lookupOrCreateAdaptiveSourceProjection(singleAllocation: SingleAllocation): AdaptiveSourceProjection? {
        val source = singleAllocation.mediaSource
        val endpointID = singleAllocation.endpointId
        if (source == null) {
            return null
        }
        synchronized(adaptiveSourceProjectionMap) {
            adaptiveSourceProjectionMap[source.primarySSRC]?.let { return it }

            if (source.rtpEncodings.isEmpty()) {
                return null
            }

            val adaptiveSourceProjection = AdaptiveSourceProjection(
                diagnosticContext,
                source,
                {
                    eventEmitter.fireEvent { keyframeNeeded(endpointID, source.primarySSRC) }
                },
                logger
            )
            logger.debug { "new source projection for $source" }

            // Route all encodings to the specified bitrate controller.
            source.rtpEncodings.forEach {
                adaptiveSourceProjectionMap[it.primarySSRC] = adaptiveSourceProjection
            }
            return adaptiveSourceProjection
        }
    }

    fun timeSinceFirstMedia(): Duration = firstMedia?.let { Duration.between(it, clock.instant()) } ?: Duration.ZERO

    fun debugState(mode: DebugStateMode): JSONObject = JSONObject().apply {
        this["num_dropped_packets_unknown_ssrc"] = numDroppedPacketsUnknownSsrc.toInt()
        this["adaptive_source_projection_map"] = adaptiveSourceProjectionMap.debugState(mode)
    }

    private fun Map<Long, AdaptiveSourceProjection>.debugState(mode: DebugStateMode) = JSONObject().also {
        forEach { (ssrc, adaptiveSourceProjection) ->
            it[ssrc] = adaptiveSourceProjection.getDebugState(mode)
        }
    }

    /**
     * Signals to this instance that the allocation chosen by the `BitrateAllocator` has changed.
     */
    fun allocationChanged(allocation: BandwidthAllocation) {
        if (allocation.allocations.isEmpty()) {
            adaptiveSourceProjectionMap.values.forEach { it.setTargetIndex(RtpLayerDesc.SUSPENDED_INDEX) }
        } else {
            allocation.allocations.forEach {
                val sourceTargetIdx = it.targetLayer?.index ?: -1

                // Review this.
                val adaptiveSourceProjection = lookupOrCreateAdaptiveSourceProjection(it)
                adaptiveSourceProjection?.setTargetIndex(sourceTargetIdx)
            }
        }
    }
}
