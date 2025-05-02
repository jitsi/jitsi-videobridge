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
package org.jitsi.videobridge.cc.av1

import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.RtpLayerDesc.Companion.getEidFromIndex
import org.jitsi.nlj.rtp.codec.av1.Av1DDPacket
import org.jitsi.nlj.rtp.codec.av1.Av1DDRtpLayerDesc
import org.jitsi.nlj.rtp.codec.av1.Av1DDRtpLayerDesc.Companion.getDtFromIndex
import org.jitsi.nlj.rtp.codec.av1.getTemplateIdDelta
import org.jitsi.rtp.rtcp.RtcpSrPacket
import org.jitsi.rtp.rtp.header_extensions.DTI
import org.jitsi.rtp.rtp.header_extensions.toShortString
import org.jitsi.rtp.util.RtpUtils
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
import java.time.Duration
import java.time.Instant

class Av1DDAdaptiveSourceProjectionContext(
    private val diagnosticContext: DiagnosticContext,
    rtpState: RtpState,
    persistentState: Any?,
    parentLogger: Logger
) : AdaptiveSourceProjectionContext {
    private val logger: Logger = createChildLogger(parentLogger)

    /**
     * A map that stores the per-encoding AV1 frame maps.
     */
    private val av1FrameMaps = HashMap<Long, Av1DDFrameMap>()

    /**
     * The [Av1DDQualityFilter] instance that does quality filtering on the
     * incoming pictures, to choose encodings and layers to forward.
     */
    private val av1QualityFilter = Av1DDQualityFilter(av1FrameMaps, logger)

    init {
        require(persistentState is Av1PersistentState?)
    }

    private var lastAv1FrameProjection = Av1DDFrameProjection(
        diagnosticContext,
        rtpState.ssrc,
        rtpState.maxSequenceNumber,
        rtpState.maxTimestamp,
        (persistentState as? Av1PersistentState)?.frameNumber,
        (persistentState as? Av1PersistentState)?.templateId
    )

    /**
     * The frame number index that started the latest stream resumption.
     * We can't send frames with frame number less than this, because we don't have
     * space in the projected sequence number/frame number counts.
     */
    private var lastFrameNumberIndexResumption = -1L

    override fun accept(packetInfo: PacketInfo, targetIndex: Int): Boolean {
        val packet = packetInfo.packet

        if (packet !is Av1DDPacket) {
            logger.warn("Packet is not AV1 DD Packet")
            return false
        }
        val incomingEncoding = packet.encodingId

        /* If insertPacketInMap returns null, this is a very old picture, more than Av1FrameMap.PICTURE_MAP_SIZE old,
           or something is wrong with the stream. */
        val result = insertPacketInMap(packet) ?: return false

        val frame = result.frame

        if (result.isNewFrame) {
            if (packet.isKeyframe && frameIsNewSsrc(frame)) {
                /* If we're not currently projecting this SSRC, check if we've
                 * already decided to drop a subsequent required frame of this SSRC for the DT.
                 * If we have, we can't turn on the encoding starting from this
                 * packet, so treat this frame as though it weren't a keyframe.
                 * Note that this may mean re-analyzing the packets with a now-available template dependency structure.
                 */
                if (haveSubsequentNonAcceptedChain(frame, incomingEncoding, targetIndex)) {
                    frame.isKeyframe = false
                }
            }
            val receivedTime = packetInfo.receivedTime
            val acceptResult = av1QualityFilter
                .acceptFrame(frame, incomingEncoding, targetIndex, receivedTime)
            frame.isAccepted = acceptResult.accept && frameIsProjectable(frame)
            if (frame.isAccepted) {
                val projection: Av1DDFrameProjection
                try {
                    projection = createProjection(
                        frame = frame,
                        initialPacket = packet,
                        isResumption = acceptResult.isResumption,
                        isReset = result.isReset,
                        mark = acceptResult.mark,
                        newDt = acceptResult.newDt,
                        receivedTime = receivedTime
                    )
                } catch (e: Exception) {
                    logger.warn("Failed to create frame projection", e)
                    /* Make sure we don't have an accepted frame without a projection in the map. */
                    frame.isAccepted = false
                    return false
                }
                frame.projection = projection
                if (projection.earliestProjectedSeqNum isNewerThan lastAv1FrameProjection.latestProjectedSeqNum) {
                    lastAv1FrameProjection = projection
                }
            }
        }

        val accept = frame.isAccepted &&
            if (frame.projection?.accept(packet) == true) {
                true
            } else {
                if (frame.projection != null && frame.projection?.closedSeq != -1) {
                    logger.debug(
                        "Not accepting $packet: frame projection is closed at ${frame.projection?.closedSeq}"
                    )
                } else if (frame.projection == null) {
                    logger.warn("Not accepting $packet: frame has no projection, even though QF accepted it")
                } else {
                    logger.warn("Not accepting $packet, even though frame projection is not closed")
                }
                false
            }

        if (timeSeriesLogger.isTraceEnabled) {
            val pt = diagnosticContext.makeTimeSeriesPoint("rtp_av1")
                .addField("ssrc", packet.ssrc)
                .addField("timestamp", packet.timestamp)
                .addField("seq", packet.sequenceNumber)
                .addField("frameNumber", packet.frameNumber)
                .addField("templateId", packet.statelessDescriptor.frameDependencyTemplateId)
                .addField("hasStructure", packet.descriptor?.newTemplateDependencyStructure != null)
                .addField("spatialLayer", packet.frameInfo?.spatialId)
                .addField("temporalLayer", packet.frameInfo?.temporalId)
                .addField("dti", packet.frameInfo?.dti?.toShortString())
                .addField("hasInterPictureDependency", packet.frameInfo?.hasInterPictureDependency())
                // TODO add more relevant fields from AV1 DD for debugging
                .addField("targetIndex", Av1DDRtpLayerDesc.indexString(targetIndex))
                .addField("new_frame", result.isNewFrame)
                .addField("accept", accept)
                .addField("payload_length", packet.payloadLength)
                .addField("packet_length", packet.length)
            av1QualityFilter.addDiagnosticContext(pt)
            timeSeriesLogger.trace(pt)
        }

        return accept
    }

    /** Look up an Av1DDFrame for a packet. */
    private fun lookupAv1Frame(av1Packet: Av1DDPacket): Av1DDFrame? = av1FrameMaps[av1Packet.ssrc]?.findFrame(av1Packet)

    /**
     * Insert a packet in the appropriate [Av1DDFrameMap].
     */
    private fun insertPacketInMap(av1Packet: Av1DDPacket) =
        av1FrameMaps.getOrPut(av1Packet.ssrc) { Av1DDFrameMap(logger) }.insertPacket(av1Packet)

    private fun haveSubsequentNonAcceptedChain(frame: Av1DDFrame, incomingEncoding: Int, targetIndex: Int): Boolean {
        val map = av1FrameMaps[frame.ssrc] ?: return false
        val structure = frame.structure ?: return false
        val dtsToCheck = if (incomingEncoding == getEidFromIndex(targetIndex)) {
            setOf(getDtFromIndex(targetIndex))
        } else {
            frame.frameInfo?.dtisPresent ?: emptySet()
        }
        val chainsToCheck = dtsToCheck.mapNotNull { structure.decodeTargetProtectedBy.getOrNull(it) }.toSet()
        return map.nextFrameWith(frame) {
            if (it.isAccepted) return@nextFrameWith false
            if (it.frameInfo == null) {
                it.updateParse(structure, logger)
            }
            return@nextFrameWith chainsToCheck.any { chainIdx ->
                it.partOfActiveChain(chainIdx)
            }
        } != null
    }

    private fun Av1DDFrame.partOfActiveChain(chainIdx: Int): Boolean {
        val structure = structure ?: return false
        val frameInfo = frameInfo ?: return false
        for (i in structure.decodeTargetProtectedBy.indices) {
            if (structure.decodeTargetProtectedBy[i] != chainIdx) {
                continue
            }
            if (frameInfo.dti[i] == DTI.NOT_PRESENT || frameInfo.dti[i] == DTI.DISCARDABLE) {
                return false
            }
        }
        return true
    }

    /**
     * Calculate the projected sequence number gap between two frames (of the same encoding),
     * allowing collapsing for unaccepted frames.
     */
    private fun seqGap(frame1: Av1DDFrame, frame2: Av1DDFrame): Int {
        var seqGap = RtpUtils.getSequenceNumberDelta(
            frame2.earliestKnownSequenceNumber,
            frame1.latestKnownSequenceNumber
        )
        if (!frame1.isAccepted && !frame2.isAccepted && frame2.isImmediatelyAfter(frame1)) {
            /* If neither frame is being projected, and they have consecutive
               frame numbers, we don't need to leave any gap. */
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

    private fun frameIsNewSsrc(frame: Av1DDFrame): Boolean = lastAv1FrameProjection.av1Frame?.matchesSSRC(frame) != true

    private fun frameIsProjectable(frame: Av1DDFrame): Boolean =
        frameIsNewSsrc(frame) || frame.index >= lastFrameNumberIndexResumption

    /**
     * Find the previous frame before the given one.
     */
    @Synchronized
    private fun prevFrame(frame: Av1DDFrame) = av1FrameMaps[frame.ssrc]?.prevFrame(frame)

    /**
     * Find the next frame after the given one.
     */
    @Synchronized
    private fun nextFrame(frame: Av1DDFrame) = av1FrameMaps[frame.ssrc]?.nextFrame(frame)

    /**
     * Find the previous accepted frame before the given one.
     */
    private fun findPrevAcceptedFrame(frame: Av1DDFrame) = av1FrameMaps[frame.ssrc]?.findPrevAcceptedFrame(frame)

    /**
     * Find the next accepted frame after the given one.
     */
    private fun findNextAcceptedFrame(frame: Av1DDFrame) = av1FrameMaps[frame.ssrc]?.findNextAcceptedFrame(frame)

    /**
     * Create a projection for this frame.
     */
    private fun createProjection(
        frame: Av1DDFrame,
        initialPacket: Av1DDPacket,
        mark: Boolean,
        isResumption: Boolean,
        isReset: Boolean,
        newDt: Int?,
        receivedTime: Instant?
    ): Av1DDFrameProjection {
        if (frameIsNewSsrc(frame)) {
            return createEncodingSwitchProjection(frame, initialPacket, mark, newDt, receivedTime)
        } else if (isResumption) {
            return createResumptionProjection(frame, initialPacket, mark, newDt, receivedTime)
        } else if (isReset) {
            return createResetProjection(frame, initialPacket, mark, newDt, receivedTime)
        }

        return createInEncodingProjection(frame, initialPacket, mark, newDt, receivedTime)
    }

    /**
     * Create a projection for the first frame after an encoding switch.
     */
    private fun createEncodingSwitchProjection(
        frame: Av1DDFrame,
        initialPacket: Av1DDPacket,
        mark: Boolean,
        newDt: Int?,
        receivedTime: Instant?
    ): Av1DDFrameProjection {
        // We can only switch on packets that carry a scalability structure, which is the first packet of a keyframe
        assert(frame.isKeyframe)
        assert(initialPacket.isStartOfFrame)
        lastFrameNumberIndexResumption = frame.index

        var projectedSeqGap = 1

        if (lastAv1FrameProjection.av1Frame?.seenEndOfFrame == false) {
            /* Leave a gap to signal to the decoder that the previously routed
               frame was incomplete. */
            projectedSeqGap++

            /* Make sure subsequent packets of the previous projection won't
               overlap the new one.  (This means the gap, above, will never be
               filled in.)
             */
            lastAv1FrameProjection.close()
        }

        val projectedSeq =
            RtpUtils.applySequenceNumberDelta(lastAv1FrameProjection.latestProjectedSeqNum, projectedSeqGap)

        // this is a simulcast switch. The typical incremental value =
        // 90kHz / 30 = 90,000Hz / 30 = 3000 per frame or per 33ms
        val tsDelta = lastAv1FrameProjection.created?.let { created ->
            receivedTime?.let {
                3000 * Duration.between(created, receivedTime).dividedBy(33).seconds.coerceAtLeast(1L)
            }
        } ?: 3000
        val projectedTs = RtpUtils.applyTimestampDelta(lastAv1FrameProjection.timestamp, tsDelta)

        val frameNumber: Int
        val templateIdDelta: Int
        val nextTemplateId = lastAv1FrameProjection.getNextTemplateId()
        if (nextTemplateId != null) {
            frameNumber = RtpUtils.applySequenceNumberDelta(
                lastAv1FrameProjection.frameNumber,
                1
            )
            val structure = frame.structure
            check(structure != null)
            templateIdDelta = getTemplateIdDelta(nextTemplateId, structure.templateIdOffset)
        } else {
            frameNumber = frame.frameNumber
            templateIdDelta = 0
        }

        return Av1DDFrameProjection(
            diagnosticContext = diagnosticContext,
            av1Frame = frame,
            ssrc = lastAv1FrameProjection.ssrc,
            timestamp = projectedTs,
            sequenceNumberDelta = RtpUtils.getSequenceNumberDelta(projectedSeq, initialPacket.sequenceNumber),
            frameNumber = frameNumber,
            templateIdDelta = templateIdDelta,
            dti = newDt?.let { frame.structure?.getDtBitmaskForDt(it) },
            mark = mark,
            created = receivedTime
        )
    }

    /**
     * Create a projection for the first frame after a resumption, i.e. when a source is turned back on.
     */
    private fun createResumptionProjection(
        frame: Av1DDFrame,
        initialPacket: Av1DDPacket,
        mark: Boolean,
        newDt: Int?,
        receivedTime: Instant?
    ): Av1DDFrameProjection {
        lastFrameNumberIndexResumption = frame.index

        /* These must be non-null because we don't execute this function unless
            frameIsNewSsrc has returned false.
         */
        val lastFrame = prevFrame(frame)!!
        val lastProjectedFrame = lastAv1FrameProjection.av1Frame!!

        /* Project timestamps linearly. */
        val tsDelta = RtpUtils.getTimestampDiff(
            lastAv1FrameProjection.timestamp,
            lastProjectedFrame.timestamp
        )
        val projectedTs = RtpUtils.applyTimestampDelta(frame.timestamp, tsDelta)

        /* Increment frameNumber by 1 from the last projected frame. */
        val projectedFrameNumber = RtpUtils.applySequenceNumberDelta(lastAv1FrameProjection.frameNumber, 1)

        /** If this packet has a template structure, rewrite it to follow after the pre-resumption structure.
         * (We could check to see if the structure is unchanged, but that's an unnecessary optimization.)
         */
        val templateIdDelta = if (frame.structure != null) {
            val nextTemplateId = lastAv1FrameProjection.getNextTemplateId()

            if (nextTemplateId != null) {
                val structure = frame.structure
                check(structure != null)
                getTemplateIdDelta(nextTemplateId, structure.templateIdOffset)
            } else {
                0
            }
        } else {
            0
        }

        /* Increment sequence numbers based on the last projected frame, but leave a gap
         * for packet reordering in case this isn't the first packet of the keyframe.
         */
        val seqGap = RtpUtils.getSequenceNumberDelta(initialPacket.sequenceNumber, lastFrame.latestKnownSequenceNumber)
        val newSeq = RtpUtils.applySequenceNumberDelta(lastAv1FrameProjection.latestProjectedSeqNum, seqGap)
        val seqDelta = RtpUtils.getSequenceNumberDelta(newSeq, initialPacket.sequenceNumber)

        return Av1DDFrameProjection(
            diagnosticContext = diagnosticContext,
            av1Frame = frame,
            ssrc = lastAv1FrameProjection.ssrc,
            timestamp = projectedTs,
            sequenceNumberDelta = seqDelta,
            frameNumber = projectedFrameNumber,
            templateIdDelta = templateIdDelta,
            dti = newDt?.let { frame.structure?.getDtBitmaskForDt(it) },
            mark = mark,
            created = receivedTime
        )
    }

    /**
     * Create a projection for the first frame after a frame reset, i.e. after a large gap in sequence numbers.
     */
    private fun createResetProjection(
        frame: Av1DDFrame,
        initialPacket: Av1DDPacket,
        mark: Boolean,
        newDt: Int?,
        receivedTime: Instant?
    ): Av1DDFrameProjection {
        /* This must be non-null because we don't execute this function unless
            frameIsNewSsrc has returned false.
         */
        val lastFrame = lastAv1FrameProjection.av1Frame!!

        /* Apply the latest projected frame's projections out, linearly. */
        val seqDelta = RtpUtils.getSequenceNumberDelta(
            lastAv1FrameProjection.latestProjectedSeqNum,
            lastFrame.latestKnownSequenceNumber
        )
        val tsDelta = RtpUtils.getTimestampDiff(
            lastAv1FrameProjection.timestamp,
            lastFrame.timestamp
        )
        val frameNumberDelta = RtpUtils.applySequenceNumberDelta(
            lastAv1FrameProjection.frameNumber,
            lastFrame.frameNumber
        )

        val projectedTs = RtpUtils.applyTimestampDelta(frame.timestamp, tsDelta)
        val projectedFrameNumber = RtpUtils.applySequenceNumberDelta(frame.frameNumber, frameNumberDelta)

        /** If this packet has a template structure, rewrite it to follow after the pre-reset structure.
         * (We could check to see if the structure is unchanged, but that's an unnecessary optimization.)
         */
        val templateIdDelta = if (frame.structure != null) {
            val nextTemplateId = lastAv1FrameProjection.getNextTemplateId()

            if (nextTemplateId != null) {
                val structure = frame.structure
                check(structure != null)
                getTemplateIdDelta(nextTemplateId, structure.templateIdOffset)
            } else {
                0
            }
        } else {
            0
        }
        return Av1DDFrameProjection(
            diagnosticContext = diagnosticContext,
            av1Frame = frame,
            ssrc = lastAv1FrameProjection.ssrc,
            timestamp = projectedTs,
            sequenceNumberDelta = seqDelta,
            frameNumber = projectedFrameNumber,
            templateIdDelta = templateIdDelta,
            dti = newDt?.let { frame.structure?.getDtBitmaskForDt(it) },
            mark = mark,
            created = receivedTime
        )
    }

    /**
     * Create a frame projection for the normal case, i.e. as part of the same encoding as the
     * previously-projected frame.
     */
    private fun createInEncodingProjection(
        frame: Av1DDFrame,
        initialPacket: Av1DDPacket,
        mark: Boolean,
        newDt: Int?,
        receivedTime: Instant?
    ): Av1DDFrameProjection {
        val prevFrame = findPrevAcceptedFrame(frame)
        if (prevFrame != null) {
            return createInEncodingProjection(frame, prevFrame, initialPacket, mark, newDt, receivedTime)
        }

        /* prev frame has rolled off beginning of frame map, try next frame */
        val nextFrame = findNextAcceptedFrame(frame)
        if (nextFrame != null) {
            return createInEncodingProjection(frame, nextFrame, initialPacket, mark, newDt, receivedTime)
        }

        /* Neither previous or next is found. Very big frame? Use previous projected.
           (This must be valid because we don't execute this function unless
           frameIsNewSsrc has returned false.)
         */
        return createInEncodingProjection(
            frame,
            lastAv1FrameProjection.av1Frame!!,
            initialPacket,
            mark,
            newDt,
            receivedTime
        )
    }

    /**
     * Create a frame projection for the normal case, i.e. as part of the same encoding as the
     * previously-projected frame, based on a specific chosen previously-projected frame.
     */
    private fun createInEncodingProjection(
        frame: Av1DDFrame,
        refFrame: Av1DDFrame,
        initialPacket: Av1DDPacket,
        mark: Boolean,
        newDt: Int?,
        receivedTime: Instant?
    ): Av1DDFrameProjection {
        val tsGap = RtpUtils.getTimestampDiff(frame.timestamp, refFrame.timestamp)
        val frameNumGap = RtpUtils.getSequenceNumberDelta(frame.frameNumber, refFrame.frameNumber)
        var seqGap = 0

        var f1 = refFrame
        var f2: Av1DDFrame?
        val refSeq: Int
        if (frameNumGap > 0) {
            /* refFrame is earlier than frame in decode order. */
            do {
                f2 = nextFrame(f1)
                checkNotNull(f2) {
                    "No next frame found after frame with frame number ${f1.frameNumber}, " +
                        "even though refFrame ${refFrame.frameNumber} is before " +
                        "frame ${frame.frameNumber}!"
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
                    "No previous frame found before frame with frame number ${f1.frameNumber}, " +
                        "even though refFrame ${refFrame.frameNumber} is after " +
                        "frame ${frame.frameNumber}!"
                }
                seqGap += -seqGap(f2, f1)
                f1 = f2
            } while (f2 !== frame)
            refSeq = refFrame.projection!!.earliestProjectedSeqNum
        }

        val projectedSeq = RtpUtils.applySequenceNumberDelta(refSeq, seqGap)
        val projectedTs = RtpUtils.applyTimestampDelta(refFrame.projection!!.timestamp, tsGap)
        val projectedFrameNumber = RtpUtils.applySequenceNumberDelta(refFrame.projection!!.frameNumber, frameNumGap)

        return Av1DDFrameProjection(
            diagnosticContext = diagnosticContext,
            av1Frame = frame,
            ssrc = lastAv1FrameProjection.ssrc,
            timestamp = projectedTs,
            sequenceNumberDelta = RtpUtils.getSequenceNumberDelta(projectedSeq, initialPacket.sequenceNumber),
            frameNumber = projectedFrameNumber,
            templateIdDelta = lastAv1FrameProjection.templateIdDelta,
            dti = newDt?.let { frame.structure?.getDtBitmaskForDt(it) },
            mark = mark,
            created = receivedTime
        )
    }

    override fun needsKeyframe(): Boolean {
        if (av1QualityFilter.needsKeyframe) {
            return true
        }

        return lastAv1FrameProjection.av1Frame == null
    }

    override fun rewriteRtp(packetInfo: PacketInfo) {
        if (packetInfo.packet !is Av1DDPacket) {
            logger.info("Got a non-AV1 DD packet.")
            throw RewriteException("Non-AV1 DD packet in AV1 DD source projection")
        }
        val av1Packet = packetInfo.packetAs<Av1DDPacket>()

        val av1Frame = lookupAv1Frame(av1Packet)
            ?: throw RewriteException("Frame not in tracker (aged off?)")

        val av1Projection = av1Frame.projection
            ?: throw RewriteException("Frame does not have projection?")
        /* Shouldn't happen for an accepted packet whose frame is still known? */

        logger.trace { "Rewriting packet with structure ${System.identityHashCode(av1Packet.descriptor?.structure)}" }
        av1Projection.rewriteRtp(av1Packet)
    }

    override fun rewriteRtcp(rtcpSrPacket: RtcpSrPacket): Boolean {
        val lastAv1FrameProjectionCopy: Av1DDFrameProjection = lastAv1FrameProjection
        if (rtcpSrPacket.senderSsrc != lastAv1FrameProjectionCopy.av1Frame?.ssrc) {
            return false
        }

        rtcpSrPacket.senderSsrc = lastAv1FrameProjectionCopy.ssrc

        val srcTs = rtcpSrPacket.senderInfo.rtpTimestamp
        val delta = RtpUtils.getTimestampDiff(
            lastAv1FrameProjectionCopy.timestamp,
            lastAv1FrameProjectionCopy.av1Frame.timestamp
        )

        val dstTs = RtpUtils.applyTimestampDelta(srcTs, delta)

        if (srcTs != dstTs) {
            rtcpSrPacket.senderInfo.rtpTimestamp = dstTs
        }

        return true
    }

    override fun getRtpState() = RtpState(
        lastAv1FrameProjection.ssrc,
        lastAv1FrameProjection.latestProjectedSeqNum,
        lastAv1FrameProjection.timestamp
    )

    override fun getPersistentState(): Any = Av1PersistentState(
        lastAv1FrameProjection.frameNumber,
        lastAv1FrameProjection.getNextTemplateId() ?: 0
    )

    override fun getDebugState(): JSONObject {
        val debugState = JSONObject()
        debugState["class"] = Av1DDAdaptiveSourceProjectionContext::class.java.simpleName

        val mapSizes = JSONArray()
        for ((key, value) in av1FrameMaps.entries) {
            val sizeInfo = JSONObject()
            sizeInfo["ssrc"] = key
            sizeInfo["size"] = value.size()
            mapSizes.add(sizeInfo)
        }
        debugState["av1FrameMaps"] = mapSizes
        debugState["av1QualityFilter"] = av1QualityFilter.debugState

        return debugState
    }

    companion object {
        /**
         * The time series logger for this class.
         */
        private val timeSeriesLogger =
            TimeSeriesLogger.getTimeSeriesLogger(Av1DDAdaptiveSourceProjectionContext::class.java)
    }
}

data class Av1PersistentState(
    val frameNumber: Int,
    val templateId: Int
)
