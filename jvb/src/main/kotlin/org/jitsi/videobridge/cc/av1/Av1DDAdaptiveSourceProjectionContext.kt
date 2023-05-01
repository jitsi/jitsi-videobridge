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
import org.jitsi.nlj.RtpLayerDesc
import org.jitsi.nlj.RtpLayerDesc.Companion.getEidFromIndex
import org.jitsi.nlj.format.PayloadType
import org.jitsi.nlj.rtp.codec.av1.Av1DDPacket
import org.jitsi.nlj.rtp.codec.av1.Av1DDRtpLayerDesc
import org.jitsi.rtp.rtcp.RtcpSrPacket
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
import java.time.Instant

class Av1DDAdaptiveSourceProjectionContext(
    private val diagnosticContext: DiagnosticContext,
    private val payloadType: PayloadType,
    rtpState: RtpState,
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

    private var lastAv1FrameProjection = Av1DDFrameProjection(
        diagnosticContext,
        rtpState.ssrc, rtpState.maxSequenceNumber, rtpState.maxTimestamp
    )

    /**
     * The frame number index that started the latest stream resumption.
     * We can't send frames with frame number less than this, because we don't have
     * space in the projected sequence number/frame number counts.
     */
    private var lastFrameNumberIndexResumption = -1

    override fun accept(packetInfo: PacketInfo, incomingIndices: Collection<Int>, targetIndex: Int): Boolean {
        val packet = packetInfo.packet

        if (packet !is Av1DDPacket) {
            logger.warn("Packet is not AV1 DD Packet")
            return false
        }

        /* If insertPacketInMap returns null, this is a very old picture, more than Av1FrameMap.PICTURE_MAP_SIZE old,
           or something is wrong with the stream. */
        val result = insertPacketInMap(packet) ?: return false

        val frame = result.frame

        val incomingEncoding = getEidFromIndex(incomingIndices.first())
        if (incomingIndices.any { getEidFromIndex(it) != incomingEncoding }) {
            logger.warn(
                "Incoming indices have more than one encoding: " +
                    incomingIndices.map { Av1DDRtpLayerDesc.indexString(it) }
            )
            return false
        }

        if (result.isNewFrame) {
            if (packet.isKeyframe && frameIsNewSsrc(frame)) {
                /* If we're not currently projecting this SSRC, check if we've
                 * already decided to drop a subsequent required frame of this SSRC for the DT.
                 * If we have, we can't turn on the encoding starting from this
                 * packet, so treat this frame as though it weren't a keyframe.
                 */
                // TODO
            }
            val receivedTime = packetInfo.receivedTime
            val acceptResult = av1QualityFilter
                .acceptFrame(frame, incomingEncoding, incomingIndices, targetIndex, receivedTime)
            frame.isAccepted = acceptResult.accept && frame.index >= lastFrameNumberIndexResumption
            if (frame.isAccepted) {
                val projection: Av1DDFrameProjection
                try {
                    projection = createProjection(
                        frame = frame, initialPacket = packet, isResumption = acceptResult.isResumption,
                        isReset = result.isReset, mark = acceptResult.mark, receivedTime = receivedTime
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

        val accept = frame.isAccepted && frame.projection?.accept(packet) == true

        if (timeSeriesLogger.isTraceEnabled) {
            val pt = diagnosticContext.makeTimeSeriesPoint("rtp_av1")
                .addField("ssrc", packet.ssrc)
                .addField("timestamp", packet.timestamp)
                .addField("seq", packet.sequenceNumber)
                .addField("frameNumber", packet.frameNumber)
                // TODO add relevant fields from AV1 DD for debugging
                .addField("targetIndex", RtpLayerDesc.indexString(targetIndex))
                .addField("new_frame", result.isNewFrame)
                .addField("accept", accept)
            av1QualityFilter.addDiagnosticContext(pt)
            timeSeriesLogger.trace(pt)
        }

        return accept
    }

    /** Look up a Av1DDFrame for a packet. */
    private fun lookupAv1Frame(av1Packet: Av1DDPacket): Av1DDFrame? =
        av1FrameMaps[av1Packet.ssrc]?.findFrame(av1Packet)

    /**
     * Insert a packet in the appropriate [Av1DDFrameMap].
     */
    private fun insertPacketInMap(av1Packet: Av1DDPacket) =
        av1FrameMaps.getOrPut(av1Packet.ssrc) { Av1DDFrameMap(logger) }.insertPacket(av1Packet)

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

    private fun frameIsNewSsrc(frame: Av1DDFrame): Boolean =
        lastAv1FrameProjection.av1Frame?.matchesSSRC(frame) != true

    /**
     * Create a projection for this frame.
     */
    private fun createProjection(
        frame: Av1DDFrame,
        initialPacket: Av1DDPacket,
        mark: Boolean,
        isResumption: Boolean,
        isReset: Boolean,
        receivedTime: Instant?
    ): Av1DDFrameProjection {
        TODO("Not yet implemented")
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

        val av1Frame: Av1DDFrame = lookupAv1Frame(av1Packet)
            ?: throw RewriteException("Frame not in tracker (aged off?)")

        val av1Projection = av1Frame.projection
            ?: throw RewriteException("Frame does not have projection?")
        /* Shouldn't happen for an accepted packet whose frame is still known? */

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

    override fun getPayloadType(): PayloadType {
        return payloadType
    }

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

        debugState["payloadType"] = payloadType.toString()

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
