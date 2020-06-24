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
package org.jitsi.videobridge.cc.vp9

import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.format.PayloadType
import org.jitsi.nlj.rtp.codec.vp9.Vp9Packet
import org.jitsi.rtp.rtcp.RtcpSrPacket
import org.jitsi.rtp.util.RtpUtils.Companion.applyTimestampDelta
import org.jitsi.rtp.util.RtpUtils.Companion.getTimestampDiff
import org.jitsi.rtp.util.isNewerThan
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.videobridge.cc.AdaptiveSourceProjectionContext
import org.jitsi.videobridge.cc.RewriteException
import org.jitsi.videobridge.cc.RtpState
import org.json.simple.JSONArray
import org.json.simple.JSONObject

/**
 * This class represents a projection of a VP9 RTP stream
 * and it is the main entry point for VP9 simulcast/svc RTP/RTCP rewriting. Read
 * svc.md for implementation details. Instances of this class are thread-safe.
 */
class Vp9AdaptiveSourceProjectionContext(
    private val diagnosticContext: DiagnosticContext,
    private val payloadType: PayloadType,
    rtpState: RtpState,
    parentLogger: Logger
) : AdaptiveSourceProjectionContext {
    private val logger: Logger = createChildLogger(parentLogger)

    /**
     * A map that stores the per-encoding VP9 frame maps.
     */
    private val vp9PictureMaps = HashMap<Long, Vp9PictureMap>().withDefault { ssrc: Long? -> Vp9PictureMap(logger) }

    /**
     * The [Vp9QualityFilter] instance that does quality filtering on the
     * incoming frames.
     */
    private val vp9QualityFilter = Vp9QualityFilter(logger)

    private var lastVp9FrameProjection = Vp9FrameProjection(diagnosticContext,
        rtpState.ssrc, rtpState.maxSequenceNumber, rtpState.maxTimestamp)

    @Synchronized
    override fun accept(
        packetInfo: PacketInfo,
        incomingIndex: Int,
        targetIndex: Int
    ): Boolean {
        val packet = packetInfo.packet
        if (packet !is Vp9Packet) {
            logger.warn("Packet is not Vp9 packet")
            return false
        }

        val result = insertPacketInMap(packet)
            ?: return false
            /* Very old frame, more than Vp9FrameMap.FRAME_MAP_SIZE old,
              or something wrong with the stream. */

        val frame = result.frame

        if (result.isNewFrame) {
            if (packet.isKeyframe && frameIsNewSsrc(frame)) {
                /* If we're not currently projecting this SSRC, check if we've
                 * already decided to drop a subsequent TL0 frame of this SSRC.
                 * If we have, we can't turn on the encoding starting from this
                 * packet, so treat this frame as though it weren't a keyframe.
                 */
                /* TODO */
                /* val f: Vp9Frame? = findNextTl0(frame)
                if (f != null && !f.isAccepted) {
                    frame.isKeyframe = false
                } */
            }
            val receivedMs = packetInfo.receivedTime
            var accepted: Boolean = vp9QualityFilter
                .acceptFrame(frame, incomingIndex, targetIndex, receivedMs)
            if (accepted) {
                accepted = checkDecodability(frame)
            }
            frame.isAccepted = accepted
            if (accepted) {
                val projection: Vp9FrameProjection
                try {
                    projection = createProjection(frame, packet, result.isReset,
                        receivedMs)
                } catch (e: Exception) {
                    logger.warn("Failed to create frame projection", e)
                    /* Make sure we don't have an accepted frame without a projection in the map. */
                    frame.isAccepted = false
                    return false
                }
                frame.projection = projection
                if (projection.earliestProjectedSeqNum isNewerThan
                        lastVp9FrameProjection.latestProjectedSeqNum) {
                    lastVp9FrameProjection = projection
                }
            }
        }

        return frame.isAccepted && frame.projection?.accept(packet) == true
    }

    /** Lookup a Vp9Frame for a packet. */
    private fun lookupVp9Frame(vp9Packet: Vp9Packet): Vp9Frame? =
        vp9PictureMaps[vp9Packet.ssrc]?.findPicture(vp9Packet)?.frame(vp9Packet.spatialLayerIndex)

    /**
     * Insert a packet in the appropriate Vp9FrameMap.
     */
    private fun insertPacketInMap(vp9Packet: Vp9Packet) =
        vp9PictureMaps[vp9Packet.ssrc]?.insertPacket(vp9Packet)

    /**
     * Find the previous frame before the given one.
     */
    @Synchronized
    private fun prevPicture(picture: Vp9Picture) =
        vp9PictureMaps[picture.ssrc]?.prevPicture(picture)

    /**
     * Find the next frame after the given one.
     */
    @Synchronized
    private fun nextPicture(picture: Vp9Picture) =
        vp9PictureMaps[picture.ssrc]?.nextPicture(picture)

    /**
     * Find a subsequent Tid==0 frame after the given frame
     * @param frame The frame to query
     * @return A subsequent TL0 frame, or null
     */
    private fun findNextTl0(frame: Vp9Frame) =
        vp9PictureMaps[frame.ssrc]?.findNextTl0(frame)

    private fun frameIsNewSsrc(frame: Vp9Frame): Boolean {
        val lastFrame = lastVp9FrameProjection.vp9Frame

        return lastFrame != null && frame.matchesSSRC(lastFrame)
    }

    /**
     * For a frame that's been accepted by the quality filter, verify that
     * it's decodable given the projection decisions about previous frames
     * (in case the targetIndex has changed).
     */
    private fun checkDecodability(frame: Vp9Frame): Boolean {
        /* TODO - use SS or flexible mode reference list */
        return true
    }

    /**
     * Create a projection for this frame.
     */
    private fun createProjection(
        frame: Vp9Frame,
        initialPacket: Vp9Packet,
        isReset: Boolean,
        receivedMs: Long
    ): Vp9FrameProjection {
        /* TODO - rewriting */
        return Vp9FrameProjection(
            diagnosticContext = diagnosticContext,
            vp9Frame = frame,
            ssrc = lastVp9FrameProjection.ssrc,
            timestamp = frame.timestamp,
            sequenceNumberDelta = 0,
            pictureId = frame.pictureId,
            tl0PICIDX = frame.tl0PICIDX,
            createdMs = receivedMs
        )
    }

    override fun needsKeyframe(): Boolean {
        if (vp9QualityFilter.needsKeyframe()) {
            return true
        }

        return lastVp9FrameProjection.vp9Frame == null
    }

    @Throws(RewriteException::class)
    override fun rewriteRtp(packetInfo: PacketInfo) {
        if (packetInfo.packet !is Vp9Packet) {
            logger.info("Got a non-VP9 packet.")
            throw RewriteException("Non-VP9 packet in VP9 source projection")
        }
        val vp9Packet = packetInfo.packetAs<Vp9Packet>()

        if (vp9Packet.pictureId == -1) {
            /* Should have been routed to generic projection context. */
            logger.info("VP9 packet does not have picture ID, cannot track in frame map.")
            throw RewriteException("VP9 packet without picture ID in VP9 source projection")
        }

        val vp9Frame: Vp9Frame = lookupVp9Frame(vp9Packet)
            ?: throw RewriteException("Frame not in tracker (aged off?)")

        val vp9Projection = vp9Frame.projection
            ?: throw RewriteException("Frame does not have projection?")
            /* Shouldn't happen for an accepted packet whose frame is still known? */

        vp9Projection.rewriteRtp(vp9Packet)
    }

    override fun rewriteRtcp(rtcpSrPacket: RtcpSrPacket): Boolean {
        val lastVp9FrameProjectionCopy: Vp9FrameProjection = lastVp9FrameProjection
        if (rtcpSrPacket.senderSsrc != lastVp9FrameProjectionCopy.vp9Frame?.ssrc) {
            return false
        }

        rtcpSrPacket.senderSsrc = lastVp9FrameProjectionCopy.ssrc

        val srcTs = rtcpSrPacket.senderInfo.rtpTimestamp
        val delta = getTimestampDiff(
            lastVp9FrameProjectionCopy.timestamp,
            lastVp9FrameProjectionCopy.vp9Frame.timestamp)

        val dstTs = applyTimestampDelta(srcTs, delta)

        if (srcTs != dstTs) {
            rtcpSrPacket.senderInfo.rtpTimestamp = dstTs
        }

        return true
    }

    override fun getRtpState() = RtpState(
            lastVp9FrameProjection.ssrc,
            lastVp9FrameProjection.latestProjectedSeqNum,
            lastVp9FrameProjection.timestamp)

    override fun getPayloadType(): PayloadType {
        return payloadType
    }

    @Synchronized
    override fun getDebugState(): JSONObject {
        val debugState = JSONObject()
        debugState["class"] = Vp9AdaptiveSourceProjectionContext::class.java.simpleName

        val mapSizes = JSONArray()
        for ((key, value) in vp9PictureMaps.entries) {
            val sizeInfo = JSONObject()
            sizeInfo["ssrc"] = key
            sizeInfo["size"] = value.size()
            mapSizes.add(sizeInfo)
        }
        debugState["vp9FrameMaps"] = mapSizes
        debugState["vp9QualityFilter"] = vp9QualityFilter.debugState

        debugState["payloadType"] = payloadType.toString()

        return debugState
    }
}
