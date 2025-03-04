/*
 * Copyright @ 2019 8x8, Inc
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

import org.jitsi.nlj.rtp.codec.av1.Av1DDPacket
import org.jitsi.nlj.rtp.codec.av1.applyTemplateIdDelta
import org.jitsi.rtp.rtp.header_extensions.toShortString
import org.jitsi.rtp.util.RtpUtils.Companion.applySequenceNumberDelta
import org.jitsi.rtp.util.isOlderThan
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging.TimeSeriesLogger
import java.time.Instant

/**
 * Represents an AV1 DD frame projection. It puts together all the necessary bits
 * and pieces that are useful when projecting an accepted AV1 DD frame. A
 * projection is responsible for rewriting a AV1 DD packet. Instances of this class
 * are thread-safe.
 */
class Av1DDFrameProjection internal constructor(
    /**
     * The diagnostic context for this instance.
     */
    private val diagnosticContext: DiagnosticContext,
    /**
     * The projected [Av1DDFrame].
     */
    val av1Frame: Av1DDFrame?,
    /**
     * The RTP SSRC of the projection (RFC7667, RFC3550).
     */
    val ssrc: Long,
    /**
     * The RTP timestamp of the projection (RFC7667, RFC3550).
     */
    val timestamp: Long,
    /**
     * The sequence number delta for packets of this frame.
     */
    private val sequenceNumberDelta: Int,
    /**
     * The AV1 frame number of the projection.
     */
    val frameNumber: Int,
    /**
     * The template ID delta for this frame.  This applies both to the frame's own template ID, and to
     * the template ID offset in the dependency structure, if present.
     */
    val templateIdDelta: Int,
    /**
     * The decode target indication to set on this frame, if any.
     */
    val dti: Int?,
    /**
     * Whether to add a marker bit to the last packet of this frame.
     * (Note this will not clear already-existing marker bits.)
     */
    val mark: Boolean,
    /**
     * A timestamp of when this instance was created. It's used to calculate
     * RTP timestamps when we switch encodings.
     */
    val created: Instant?
) {

    /**
     * -1 if this projection is still "open" for new, later packets.
     * Projections can be closed when we switch away from their encodings.
     */
    var closedSeq = -1
        private set

    /**
     * Ctor.
     *
     * @param ssrc the SSRC of the destination AV1 frame.
     * @param timestamp The RTP timestamp of the projected frame that this
     * instance refers to (RFC3550).
     * @param sequenceNumberDelta The starting RTP sequence number of the
     * projected frame that this instance refers to (RFC3550).
     */
    internal constructor(
        diagnosticContext: DiagnosticContext,
        ssrc: Long,
        sequenceNumberDelta: Int,
        timestamp: Long,
        frameNumber: Int?,
        templateId: Int?
    ) : this(
        diagnosticContext = diagnosticContext,
        av1Frame = null,
        ssrc = ssrc,
        timestamp = timestamp,
        sequenceNumberDelta = sequenceNumberDelta,
        frameNumber = frameNumber ?: 0,
        templateIdDelta = templateId ?: -1,
        dti = null,
        mark = false,
        created = null
    )

    fun rewriteSeqNo(seq: Int): Int = applySequenceNumberDelta(seq, sequenceNumberDelta)

    fun rewriteTemplateId(id: Int): Int = applyTemplateIdDelta(id, templateIdDelta)

    /**
     * Rewrites an RTP packet.
     *
     * @param pkt the RTP packet to rewrite.
     */
    fun rewriteRtp(pkt: Av1DDPacket) {
        val sequenceNumber = rewriteSeqNo(pkt.sequenceNumber)
        val templateId = rewriteTemplateId(pkt.statelessDescriptor.frameDependencyTemplateId)
        if (timeSeriesLogger.isTraceEnabled) {
            timeSeriesLogger.trace(
                diagnosticContext
                    .makeTimeSeriesPoint("rtp_av1_rewrite")
                    .addField("orig.rtp.ssrc", pkt.ssrc)
                    .addField("orig.rtp.timestamp", pkt.timestamp)
                    .addField("orig.rtp.seq", pkt.sequenceNumber)
                    .addField("orig.av1.framenum", pkt.frameNumber)
                    .addField("orig.av1.dti", pkt.frameInfo?.dti?.toShortString() ?: "-")
                    .addField("orig.av1.templateid", pkt.statelessDescriptor.frameDependencyTemplateId)
                    .addField("proj.rtp.ssrc", ssrc)
                    .addField("proj.rtp.timestamp", timestamp)
                    .addField("proj.rtp.seq", sequenceNumber)
                    .addField("proj.av1.framenum", frameNumber)
                    .addField("proj.av1.dti", dti ?: -1)
                    .addField("proj.av1.templateid", templateId)
                    .addField("proj.rtp.mark", mark)
            )
        }

        // update ssrc, sequence number, timestamp, frameNumber, and templateID
        pkt.ssrc = ssrc
        pkt.timestamp = timestamp
        pkt.sequenceNumber = sequenceNumber
        if (mark && pkt.isEndOfFrame) pkt.isMarked = true

        val descriptor = pkt.descriptor
        if (descriptor != null && (
                frameNumber != pkt.frameNumber || templateId != descriptor.frameDependencyTemplateId ||
                    dti != null
                )
        ) {
            descriptor.frameNumber = frameNumber
            descriptor.frameDependencyTemplateId = templateId
            val structure = descriptor.structure
            check(
                descriptor.newTemplateDependencyStructure == null ||
                    descriptor.newTemplateDependencyStructure === descriptor.structure
            )

            structure.templateIdOffset = rewriteTemplateId(structure.templateIdOffset)
            if (dti != null && (
                    descriptor.newTemplateDependencyStructure == null ||
                        dti != (1 shl structure.decodeTargetCount) - 1
                    )
            ) {
                descriptor.activeDecodeTargetsBitmask = dti
            }

            pkt.descriptor = descriptor
            pkt.reencodeDdExt()
        }
    }

    /**
     * Determines whether a packet can be forwarded as part of this
     * [Av1DDFrameProjection] instance. The check is based on the sequence
     * of the incoming packet and whether or not the [Av1DDFrameProjection]
     * has been "closed" or not.
     *
     * @param rtpPacket the [Av1DDPacket] that will be examined.
     * @return true if the packet can be forwarded as part of this
     * [Av1DDFrameProjection], false otherwise.
     */
    fun accept(rtpPacket: Av1DDPacket): Boolean {
        if (av1Frame?.matchesFrame(rtpPacket) != true) {
            // The packet does not belong to this AV1 picture.
            return false
        }
        synchronized(av1Frame) {
            return if (closedSeq < 0) {
                true
            } else {
                rtpPacket.sequenceNumber isOlderThan closedSeq
            }
        }
    }

    val earliestProjectedSeqNum: Int
        get() {
            if (av1Frame == null) {
                return sequenceNumberDelta
            }
            synchronized(av1Frame) { return rewriteSeqNo(av1Frame.earliestKnownSequenceNumber) }
        }

    val latestProjectedSeqNum: Int
        get() {
            if (av1Frame == null) {
                return sequenceNumberDelta
            }
            synchronized(av1Frame) { return rewriteSeqNo(av1Frame.latestKnownSequenceNumber) }
        }

    /**
     * Prevents the max sequence number of this frame to grow any further.
     */
    fun close() {
        if (av1Frame != null) {
            synchronized(av1Frame) { closedSeq = av1Frame.latestKnownSequenceNumber }
        }
    }

    /**
     * Get the next template ID that would come after the template IDs in this projection's structure
     */
    fun getNextTemplateId(): Int? {
        if (av1Frame == null && templateIdDelta != -1) {
            return templateIdDelta
        }
        return av1Frame?.structure?.let { rewriteTemplateId(it.templateIdOffset + it.templateCount) }
    }

    companion object {
        /**
         * The time series logger for this class.
         */
        private val timeSeriesLogger = TimeSeriesLogger.getTimeSeriesLogger(Av1DDFrameProjection::class.java)
    }
}
