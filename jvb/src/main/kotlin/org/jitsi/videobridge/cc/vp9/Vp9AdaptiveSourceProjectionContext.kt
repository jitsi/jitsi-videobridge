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
import org.jitsi.nlj.RtpLayerDesc.Companion.indexString
import org.jitsi.nlj.codec.vpx.VpxUtils.Companion.applyExtendedPictureIdDelta
import org.jitsi.nlj.codec.vpx.VpxUtils.Companion.applyTl0PicIdxDelta
import org.jitsi.nlj.codec.vpx.VpxUtils.Companion.getExtendedPictureIdDelta
import org.jitsi.nlj.codec.vpx.VpxUtils.Companion.getTl0PicIdxDelta
import org.jitsi.nlj.format.PayloadType
import org.jitsi.nlj.rtp.codec.vp9.Vp9Packet
import org.jitsi.rtp.rtcp.RtcpSrPacket
import org.jitsi.rtp.util.RtpUtils.Companion.applySequenceNumberDelta
import org.jitsi.rtp.util.RtpUtils.Companion.applyTimestampDelta
import org.jitsi.rtp.util.RtpUtils.Companion.getSequenceNumberDelta
import org.jitsi.rtp.util.RtpUtils.Companion.getTimestampDiff
import org.jitsi.rtp.util.isNewerThan
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging.TimeSeriesLogger
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
     * A map that stores the per-encoding VP9 picture maps.
     */
    private val vp9PictureMaps = HashMap<Long, Vp9PictureMap>()

    /**
     * The [Vp9QualityFilter] instance that does quality filtering on the
     * incoming pictures, to choose encodings and layers to forward.
     */
    private val vp9QualityFilter = Vp9QualityFilter(logger)

    private var lastVp9FrameProjection = Vp9FrameProjection(
        diagnosticContext,
        rtpState.ssrc, rtpState.maxSequenceNumber, rtpState.maxTimestamp
    )

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

        /* If insertPacketInMap returns null, this is a very old picture, more than Vp9PictureMap.PICTURE_MAP_SIZE old,
           or something is wrong with the stream. */
        val result = insertPacketInMap(packet) ?: return false

        val frame = result.frame

        if (result.isNewFrame) {
            if (packet.isKeyframe && frameIsNewSsrc(frame)) {
                /* If we're not currently projecting this SSRC, check if we've
                 * already decided to drop a subsequent base TL0 frame of this SSRC.
                 * If we have, we can't turn on the encoding starting from this
                 * packet, so treat this frame as though it weren't a keyframe.
                 */
                val f: Vp9Frame? = findNextBaseTl0(frame)
                if (f != null && !f.isAccepted) {
                    frame.isKeyframe = false
                }
            }
            val receivedMs = packetInfo.receivedTime
            val acceptResult = vp9QualityFilter
                .acceptFrame(frame, incomingIndex, targetIndex, receivedMs)
            frame.isAccepted = acceptResult.accept
            if (frame.isAccepted) {
                val projection: Vp9FrameProjection
                try {
                    projection = createProjection(
                        frame = frame, initialPacket = packet,
                        isReset = result.isReset, mark = acceptResult.mark, receivedMs = receivedMs
                    )
                } catch (e: Exception) {
                    logger.warn("Failed to create frame projection", e)
                    /* Make sure we don't have an accepted frame without a projection in the map. */
                    frame.isAccepted = false
                    return false
                }
                frame.projection = projection
                if (projection.earliestProjectedSeqNum isNewerThan lastVp9FrameProjection.latestProjectedSeqNum) {
                    lastVp9FrameProjection = projection
                }
            }
        }

        val accept = frame.isAccepted && frame.projection?.accept(packet) == true

        if (timeSeriesLogger.isTraceEnabled) {
            val pt = diagnosticContext.makeTimeSeriesPoint("rtp_vp9")
                .addField("ssrc", packet.ssrc)
                .addField("timestamp", packet.timestamp)
                .addField("seq", packet.sequenceNumber)
                .addField("pictureId", packet.pictureId)
                .addField("index", indexString(incomingIndex))
                .addField("isInterPicturePredicted", packet.isInterPicturePredicted)
                .addField("usesInterLayerDependency", packet.usesInterLayerDependency)
                .addField("isUpperLevelReference", packet.isUpperLevelReference)
                .addField("targetIndex", indexString(targetIndex))
                .addField("new_frame", result.isNewFrame)
                .addField("accept", accept)
            vp9QualityFilter.addDiagnosticContext(pt)
            timeSeriesLogger.trace(pt)
        }

        return accept
    }

    /** Look up a Vp9Frame for a packet. */
    private fun lookupVp9Frame(vp9Packet: Vp9Packet): Vp9Frame? =
        vp9PictureMaps[vp9Packet.ssrc]?.findPicture(vp9Packet)?.frame(vp9Packet)

    /**
     * Insert a packet in the appropriate Vp9FrameMap.
     */
    private fun insertPacketInMap(vp9Packet: Vp9Packet) =
        vp9PictureMaps.getOrPut(vp9Packet.ssrc) { Vp9PictureMap(logger) }.insertPacket(vp9Packet)

    /**
     * Calculate the projected sequence number gap between two frames (of the same encoding),
     * allowing collapsing for unaccepted frames.
     */
    private fun seqGap(frame1: Vp9Frame, frame2: Vp9Frame): Int {
        var seqGap = getSequenceNumberDelta(
            frame2.earliestKnownSequenceNumber,
            frame1.latestKnownSequenceNumber
        )
        if (!frame1.isAccepted && !frame2.isAccepted && frame2.isImmediatelyAfter(frame1)) {
            /* If neither frame is being projected, and they have consecutive
               picture IDs, we don't need to leave any gap. */
            seqGap = 0
        } else {
            /* If the earlier frame wasn't projected, and we haven't seen its
             * final packet, we know it has to consume at least one more sequence number. */
            if (!frame1.isAccepted && !frame1.seenEndOfFrame && seqGap > 1) {
                seqGap--
            }
            /* Similarly, if the later frame wasn't projected and we haven't seen
             * its first packet. */
            if (!frame2.isAccepted && !frame2.seenStartOfFrame && seqGap > 1) {
                seqGap--
            }
            /* If the frame wasn't accepted, it has to have consumed at least one sequence number,
             * which we can collapse out. */
            if (!frame1.isAccepted && seqGap > 0) {
                seqGap--
            }
        }
        return seqGap
    }

    private fun frameIsNewSsrc(frame: Vp9Frame): Boolean =
        lastVp9FrameProjection.vp9Frame?.matchesSSRC(frame) != true

    /**
     * Find the previous frame before the given one.
     */
    @Synchronized
    private fun prevFrame(frame: Vp9Frame) =
        vp9PictureMaps.get(frame.ssrc)?.prevFrame(frame)

    /**
     * Find the next frame after the given one.
     */
    @Synchronized
    private fun nextFrame(frame: Vp9Frame) =
        vp9PictureMaps.get(frame.ssrc)?.nextFrame(frame)

    /**
     * Find the previous accepted frame before the given one.
     */
    private fun findPrevAcceptedFrame(frame: Vp9Frame) =
        vp9PictureMaps.get(frame.ssrc)?.findPrevAcceptedFrame(frame)

    /**
     * Find the next accepted frame after the given one.
     */
    private fun findNextAcceptedFrame(frame: Vp9Frame) =
        vp9PictureMaps.get(frame.ssrc)?.findNextAcceptedFrame(frame)

    /**
     * Find a subsequent base-layer TL0 frame after the given frame
     * @param frame The frame to query
     * @return A subsequent base-layer TL0 frame, or null
     */
    private fun findNextBaseTl0(frame: Vp9Frame) =
        vp9PictureMaps.get(frame.ssrc)?.findNextBaseTl0(frame)

    /**
     * Create a projection for this frame.
     */
    private fun createProjection(
        frame: Vp9Frame,
        initialPacket: Vp9Packet,
        mark: Boolean,
        isReset: Boolean,
        receivedMs: Long
    ): Vp9FrameProjection {
        if (frameIsNewSsrc(frame)) {
            return createEncodingSwitchProjection(frame, initialPacket, mark, receivedMs)
        } else if (isReset) {
            return createResetProjection(frame, initialPacket, mark, receivedMs)
        }

        return createInEncodingProjection(frame, initialPacket, mark, receivedMs)
    }

    /**
     * Create an projection for the first frame after an encoding switch.
     */
    private fun createEncodingSwitchProjection(
        frame: Vp9Frame,
        initialPacket: Vp9Packet,
        mark: Boolean,
        receivedMs: Long
    ): Vp9FrameProjection {
        assert(frame.isKeyframe)

        var projectedSeqGap = if (!initialPacket.isStartOfFrame) {
            val f = prevFrame(frame)
            if (f != null) {
                /* Leave enough of a gap to fill in the earlier packets of the keyframe */
                seqGap(f, frame)
            } else {
                /* If this is the first packet we've seen on this encoding, and it's not the start of a frame,
                 * we have a problem - we don't know where this packet might fall in the frame.
                 * Guess a reasonably-sized guess for the number of packets that might have been dropped, and hope
                 * we don't end up reusing sequence numbers.
                 */
                16
            }
        } else {
            1
        }

        if (lastVp9FrameProjection.vp9Frame?.seenEndOfFrame == false) {
            /* Leave a gap to signal to the decoder that the previously routed
               frame was incomplete. */
            projectedSeqGap++

            /* Make sure subsequent packets of the previous projection won't
               overlap the new one.  (This means the gap, above, will never be
               filled in.)
             */
            lastVp9FrameProjection.close()
        }

        val projectedSeq = applySequenceNumberDelta(lastVp9FrameProjection.latestProjectedSeqNum, projectedSeqGap)

        // this is a simulcast switch. The typical incremental value =
        // 90kHz / 30 = 90,000Hz / 30 = 3000 per frame or per 33ms
        val tsDelta: Long
        tsDelta = if (lastVp9FrameProjection.createdMs != 0L) {
            (3000 * Math.max(1, (receivedMs - lastVp9FrameProjection.createdMs) / 33)).toLong()
        } else {
            3000
        }
        val projectedTs = applyTimestampDelta(lastVp9FrameProjection.timestamp, tsDelta)

        val picId: Int
        val tl0PicIdx: Int
        if (lastVp9FrameProjection.vp9Frame != null) {
            picId = applyExtendedPictureIdDelta(
                lastVp9FrameProjection.pictureId,
                1
            )
            tl0PicIdx = applyTl0PicIdxDelta(
                lastVp9FrameProjection.tl0PICIDX,
                1
            )
        } else {
            picId = frame.pictureId
            tl0PicIdx = frame.tl0PICIDX
        }

        return Vp9FrameProjection(
            diagnosticContext = diagnosticContext,
            vp9Frame = frame,
            ssrc = lastVp9FrameProjection.ssrc,
            timestamp = projectedTs,
            sequenceNumberDelta = getSequenceNumberDelta(projectedSeq, initialPacket.sequenceNumber),
            pictureId = picId,
            tl0PICIDX = tl0PicIdx,
            mark = mark,
            createdMs = receivedMs
        )
    }

    /**
     * Create a projection for the first frame after a frame reset, i.e. after a large gap in sequence numbers.
     */
    private fun createResetProjection(
        frame: Vp9Frame,
        initialPacket: Vp9Packet,
        mark: Boolean,
        receivedMs: Long
    ): Vp9FrameProjection {
        /* This must be non-null because we don't execute this function unless
            frameIsNewSsrc has returned false.
        */
        val lastFrame = lastVp9FrameProjection.vp9Frame!!

        /* Apply the latest projected frame's projections out, linearly. */
        val seqDelta = getSequenceNumberDelta(
            lastVp9FrameProjection.latestProjectedSeqNum,
            lastFrame.latestKnownSequenceNumber
        )
        val tsDelta = getTimestampDiff(
            lastVp9FrameProjection.timestamp,
            lastFrame.timestamp
        )
        val picIdDelta = getExtendedPictureIdDelta(
            lastVp9FrameProjection.pictureId,
            lastFrame.pictureId
        )
        val tl0PicIdxDelta = getTl0PicIdxDelta(
            lastVp9FrameProjection.tl0PICIDX,
            lastFrame.tl0PICIDX
        )

        val projectedTs = applyTimestampDelta(frame.timestamp, tsDelta)
        val projectedPicId = applyExtendedPictureIdDelta(frame.pictureId, picIdDelta)
        val projectedTl0PicIdx = applyTl0PicIdxDelta(frame.tl0PICIDX, tl0PicIdxDelta)

        return Vp9FrameProjection(
            diagnosticContext,
            frame, lastVp9FrameProjection.ssrc, projectedTs,
            seqDelta,
            projectedPicId, projectedTl0PicIdx, mark, receivedMs
        )
    }

    /**
     * Create a frame projection for the normal case, i.e. as part of the same encoding as the
     * previously-projected frame.
     */
    private fun createInEncodingProjection(
        frame: Vp9Frame,
        initialPacket: Vp9Packet,
        mark: Boolean,
        receivedMs: Long
    ): Vp9FrameProjection {
        val prevFrame = findPrevAcceptedFrame(frame)
        if (prevFrame != null) {
            return createInEncodingProjection(frame, prevFrame, initialPacket, mark, receivedMs)
        }

        /* prev frame has rolled off beginning of frame map, try next frame */
        val nextFrame = findNextAcceptedFrame(frame)
        if (nextFrame != null) {
            return createInEncodingProjection(frame, nextFrame, initialPacket, mark, receivedMs)
        }

        /* Neither previous or next is found. Very big frame? Use previous projected.
           (This must be valid because we don't execute this function unless
           frameIsNewSsrc has returned false.)
         */
        return createInEncodingProjection(
            frame, lastVp9FrameProjection.vp9Frame!!,
            initialPacket, mark, receivedMs
        )
    }

    /**
     * Create a frame projection for the normal case, i.e. as part of the same encoding as the
     * previously-projected frame, based on a specific chosen previously-projected frame.
     */
    private fun createInEncodingProjection(
        frame: Vp9Frame,
        refFrame: Vp9Frame,
        initialPacket: Vp9Packet,
        mark: Boolean,
        receivedMs: Long
    ): Vp9FrameProjection {
        val tsGap = getTimestampDiff(frame.timestamp, refFrame.timestamp)
        val tl0Gap = getTl0PicIdxDelta(frame.tl0PICIDX, refFrame.tl0PICIDX)
        val picGap = getExtendedPictureIdDelta(frame.pictureId, refFrame.pictureId)
        val layerGap = frame.spatialLayer - refFrame.spatialLayer
        var seqGap = 0

        var f1 = refFrame
        var f2: Vp9Frame?
        val refSeq: Int
        if (picGap > 0 || (picGap == 0 && layerGap > 0)) {
            /* refFrame is earlier than frame in decode order. */
            do {
                f2 = nextFrame(f1)
                checkNotNull(f2) {
                    "No next frame found after frame with picId ${f1.pictureId} layer ${f1.spatialLayer}, " +
                        "even though refFrame ${refFrame.pictureId}/${refFrame.spatialLayer} is before " +
                        "frame ${frame.pictureId}/${frame.spatialLayer}!"
                }
                seqGap += seqGap(f1, f2)
                f1 = f2
            } while (f2 !== frame)
            /* refFrame is a projected frame, so it has a projection. */
            refSeq = refFrame.projection!!.latestProjectedSeqNum
        } else {
            /* refFrame is later than frame in decode order. */
            do {
                f2 = prevFrame(f1)
                checkNotNull(f2) {
                    "No previous frame found before frame with picId ${f1.pictureId} layer ${f1.spatialLayer}, " +
                        "even though refFrame ${refFrame.pictureId}/${refFrame.spatialLayer} is after " +
                        "frame ${frame.pictureId}/${frame.spatialLayer}!"
                }
                seqGap += -seqGap(f2, f1)
                f1 = f2
            } while (f2 !== frame)
            refSeq = refFrame.projection!!.earliestProjectedSeqNum
        }

        val projectedSeq = applySequenceNumberDelta(refSeq, seqGap)
        val projectedTs = applyTimestampDelta(refFrame.projection!!.timestamp, tsGap)
        val projectedPicId = applyExtendedPictureIdDelta(refFrame.projection!!.pictureId, picGap)
        val projectedTl0PicIdx = applyTl0PicIdxDelta(refFrame.projection!!.tl0PICIDX, tl0Gap)

        return Vp9FrameProjection(
            diagnosticContext = diagnosticContext,
            vp9Frame = frame,
            ssrc = lastVp9FrameProjection.ssrc,
            timestamp = projectedTs,
            sequenceNumberDelta = getSequenceNumberDelta(projectedSeq, initialPacket.sequenceNumber),
            pictureId = projectedPicId,
            tl0PICIDX = projectedTl0PicIdx,
            mark = mark,
            createdMs = receivedMs
        )
    }

    override fun needsKeyframe(): Boolean {
        if (vp9QualityFilter.needsKeyframe) {
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
            lastVp9FrameProjectionCopy.vp9Frame.timestamp
        )

        val dstTs = applyTimestampDelta(srcTs, delta)

        if (srcTs != dstTs) {
            rtcpSrPacket.senderInfo.rtpTimestamp = dstTs
        }

        return true
    }

    override fun getRtpState() = RtpState(
        lastVp9FrameProjection.ssrc,
        lastVp9FrameProjection.latestProjectedSeqNum,
        lastVp9FrameProjection.timestamp
    )

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

    companion object {
        /**
         * The time series logger for this class.
         */
        private val timeSeriesLogger =
            TimeSeriesLogger.getTimeSeriesLogger(Vp9AdaptiveSourceProjectionContext::class.java)
    }
}
