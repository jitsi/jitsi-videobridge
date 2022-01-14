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
package org.jitsi.videobridge.cc.vp9

import org.jitsi.nlj.rtp.codec.vp9.Vp9Packet
import org.jitsi.rtp.util.RtpUtils.Companion.applySequenceNumberDelta
import org.jitsi.rtp.util.isOlderThan
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging.TimeSeriesLogger
import java.time.Instant

/**
 * Represents a VP9 frame projection. It puts together all the necessary bits
 * and pieces that are useful when projecting an accepted VP9 frame. A
 * projection is responsible for rewriting a VP9 packet. Instances of this class
 * are thread-safe.
 */
class Vp9FrameProjection
/**
 * Ctor.
 *
 * @param vp9Frame The [Vp9Frame] that's projected.
 * @param ssrc The RTP SSRC of the projected frame that this instance refers
 * to (RFC3550).
 * @param timestamp The RTP timestamp of the projected frame that this
 * instance refers to (RFC3550).
 * @param sequenceNumberDelta The starting RTP sequence number of the
 * projected frame that this instance refers to (RFC3550).
 * @param extendedPictureId The VP9 extended picture ID of the projected VP9
 * frame that this instance refers to.
 * @param tl0PICIDX The VP9 TL0PICIDX of the projected VP9 frame that this
 * instance refers to.
 */
internal constructor(
    /**
     * The diagnostic context for this instance.
     */
    private val diagnosticContext: DiagnosticContext,
    /**
     * The projected [Vp9Frame].
     */
    val vp9Frame: Vp9Frame?,
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
     * The VP9 picture ID of the projection.
     */
    val pictureId: Int,
    /**
     * The VP9 TL0PICIDX of the projection.
     */
    val tl0PICIDX: Int,
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
    private var closedSeq = -1

    /**
     * Ctor.
     *
     * @param ssrc the SSRC of the destination VP9 picture.
     * @param timestamp The RTP timestamp of the projected frame that this
     * instance refers to (RFC3550).
     * @param sequenceNumberDelta The starting RTP sequence number of the
     * projected frame that this instance refers to (RFC3550).
     */
    internal constructor(
        diagnosticContext: DiagnosticContext,
        ssrc: Long,
        sequenceNumberDelta: Int,
        timestamp: Long
    ) : this(
        diagnosticContext = diagnosticContext,
        vp9Frame = null,
        ssrc = ssrc,
        timestamp = timestamp,
        sequenceNumberDelta = sequenceNumberDelta,
        pictureId = 0,
        tl0PICIDX = 0,
        mark = false,
        created = null
    )

    fun rewriteSeqNo(seq: Int): Int {
        return applySequenceNumberDelta(seq, sequenceNumberDelta)
    }

    /**
     * Rewrites an RTP packet.
     *
     * @param pkt the RTP packet to rewrite.
     */
    fun rewriteRtp(pkt: Vp9Packet) {
        val sequenceNumber = rewriteSeqNo(pkt.sequenceNumber)
        if (timeSeriesLogger.isTraceEnabled) {
            timeSeriesLogger.trace(
                diagnosticContext
                    .makeTimeSeriesPoint("rtp_vp9_rewrite")
                    .addField("orig.rtp.ssrc", pkt.ssrc)
                    .addField("orig.rtp.timestamp", pkt.timestamp)
                    .addField("orig.rtp.seq", pkt.sequenceNumber)
                    .addField("orig.vp9.pictureid", pkt.pictureId)
                    .addField("orig.vp9.tl0picidx", pkt.TL0PICIDX)
                    .addField("proj.rtp.ssrc", ssrc)
                    .addField("proj.rtp.timestamp", timestamp)
                    .addField("proj.rtp.seq", sequenceNumber)
                    .addField("proj.vp9.pictureid", pictureId)
                    .addField("proj.vp9.tl0picidx", tl0PICIDX)
                    .addField("proj.rtp.mark", mark)
            )
        }

        // update ssrc, sequence number, timestamp, pictureId and tl0picidx
        pkt.ssrc = ssrc
        pkt.timestamp = timestamp
        pkt.sequenceNumber = sequenceNumber
        if (pkt.hasTL0PICIDX) {
            pkt.TL0PICIDX = tl0PICIDX
        }
        pkt.pictureId = pictureId
        if (mark && pkt.isEndOfFrame) pkt.isMarked = true
    }

    /**
     * Determines whether a packet can be forwarded as part of this
     * [Vp9FrameProjection] instance. The check is based on the sequence
     * of the incoming packet and whether or not the [Vp9FrameProjection]
     * has been "closed" or not.
     *
     * @param rtpPacket the [Vp9Packet] that will be examined.
     * @return true if the packet can be forwarded as part of this
     * [Vp9FrameProjection], false otherwise.
     */
    fun accept(rtpPacket: Vp9Packet): Boolean {
        if (vp9Frame?.matchesFrame(rtpPacket) != true) {
            // The packet does not belong to this VP9 picture.
            return false
        }
        synchronized(vp9Frame) {
            return if (closedSeq < 0) {
                true
            } else rtpPacket.sequenceNumber isOlderThan closedSeq
        }
    }

    val earliestProjectedSeqNum: Int
        get() {
            if (vp9Frame == null) {
                return sequenceNumberDelta
            }
            synchronized(vp9Frame) { return rewriteSeqNo(vp9Frame.earliestKnownSequenceNumber) }
        }

    val latestProjectedSeqNum: Int
        get() {
            if (vp9Frame == null) {
                return sequenceNumberDelta
            }
            synchronized(vp9Frame) { return rewriteSeqNo(vp9Frame.latestKnownSequenceNumber) }
        }

    /**
     * Prevents the max sequence number of this frame to grow any further.
     */
    fun close() {
        if (vp9Frame != null) {
            synchronized(vp9Frame) { closedSeq = vp9Frame.latestKnownSequenceNumber }
        }
    }

    companion object {
        /**
         * The time series logger for this class.
         */
        private val timeSeriesLogger = TimeSeriesLogger.getTimeSeriesLogger(Vp9FrameProjection::class.java)
    }
}
