/*
 * Copyright @ 2026 - present 8x8, Inc.
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
package org.jitsi.nlj

import org.jitsi.rtp.util.RtpUtils

/**
 * Estimates the interval between the frames of one video encoding, based on the times its packets arrive; see
 * [frameIntervalMs]. [EncodingLivenessTracker] uses it to allow an encoding which is sending slowly a longer gap
 * between packets.
 *
 * Packets are distinguished by their RTP sequence numbers and timestamps. A packet with the timestamp of the current
 * frame belongs to that frame. Otherwise, a packet with a higher sequence number than the current frame's first packet
 * starts a new frame. Sequence numbers increase in the order sent, while timestamps need not, since a codec with
 * frame reordering sends frames out of display order. A packet with an older sequence number is a reordered or
 * retransmitted packet of an older frame, unless it is too far behind for that. In that case the sender has
 * restarted its sequence numbers, and the packet starts a frame from which the stream resumes.
 *
 * For each packet, the caller asks [classify] what the packet is. For the first packet of a frame it then calls
 * [startFrame]. The two steps are separate so that the caller can read the state as it is before the new frame,
 * such as the time since the previous frame started; see [msSinceFrameStart].
 *
 * Used on the receive pipeline's thread. Only [frameIntervalMs] is read from other threads, so only it is volatile.
 */
class FrameIntervalEstimator {
    /** What a packet is, by its RTP sequence number and timestamp, relative to the frames seen so far. */
    enum class PacketKind {
        /** Another packet of the current frame: a packet with the current frame's timestamp. */
        SAME_FRAME,

        /** The first packet of the first frame, or of a frame newer than the current frame, by sequence number. */
        NEW_FRAME,

        /**
         * A packet of an older frame, by sequence number up to [MAX_STRAGGLER_SEQUENCE_DELTA] behind the current
         * frame's first packet: a reordered or retransmitted packet. It says nothing about the frames being sent now.
         */
        STRAGGLER,

        /**
         * A packet further behind the current frame than a straggler could be. The sender has restarted its
         * sequence numbers, so the packet starts a new frame, from which the stream resumes.
         */
        RESTART
    }

    /** The RTP timestamp of the current frame, or [NO_TIMESTAMP] before the first. */
    private var lastFrameTimestamp: Long = NO_TIMESTAMP

    /** The RTP sequence number of the current frame's first packet; meaningful once [lastFrameTimestamp] is. */
    private var lastFrameSequenceNumber: Int = 0

    /** When the current frame started arriving, or [NOT_STARTED] before the first. */
    private var lastFrameStartMs: Long = NOT_STARTED

    /** The two most recent intervals between frame starts, learned or not, or 0. */
    private var lastIntervalMs: Long = 0
    private var intervalBeforeLastMs: Long = 0

    /**
     * The smoothed interval between the starts of consecutive frames, in milliseconds, or 0 until an interval has
     * been learned. Volatile so that a reader on another thread sees a whole value. Only the receive pipeline's
     * thread writes it, so its read-modify-write is not a race.
     */
    @Volatile
    var frameIntervalMs: Double = 0.0
        private set

    /**
     * The kind of the packet with RTP sequence number [sequenceNumber] and timestamp [rtpTimestamp]; see
     * [PacketKind]. Changes nothing.
     */
    fun classify(sequenceNumber: Int, rtpTimestamp: Long): PacketKind {
        if (lastFrameTimestamp == NO_TIMESTAMP) {
            return PacketKind.NEW_FRAME
        }
        if (rtpTimestamp == lastFrameTimestamp) {
            return PacketKind.SAME_FRAME
        }
        val delta = RtpUtils.getSequenceNumberDelta(sequenceNumber, lastFrameSequenceNumber)
        return when {
            delta > 0 -> PacketKind.NEW_FRAME
            delta >= -MAX_STRAGGLER_SEQUENCE_DELTA -> PacketKind.STRAGGLER
            else -> PacketKind.RESTART
        }
    }

    /**
     * Whether [rtpTimestamp] is ahead of the current frame's timestamp by more than [ms] milliseconds. This
     * distinguishes a sender's restart of its sequence numbers, to within [MAX_STRAGGLER_SEQUENCE_DELTA] of the old
     * sequence numbers, from a retransmission; see [EncodingLivenessTracker.onPacketReceived]. A retransmission carries
     * an older timestamp, or, with frame reordering, a newer timestamp by at most the reorder depth of a few frames. A
     * restart which continues the timestamp clock after the encoding stopped is ahead by the duration of the whole
     * stop, which is longer than the encoding's liveness allowance; the caller passes that allowance as [ms].
     */
    fun isAheadOfCurrentFrameBy(rtpTimestamp: Long, ms: Long): Boolean = lastFrameTimestamp != NO_TIMESTAMP &&
        RtpUtils.getTimestampDiff(rtpTimestamp, lastFrameTimestamp) > ms * VIDEO_RTP_CLOCK_RATE / 1000

    /** How long before [nowMs] the current frame started arriving, or 0 before the first frame. */
    fun msSinceFrameStart(nowMs: Long): Long = if (lastFrameStartMs == NOT_STARTED) 0 else nowMs - lastFrameStartMs

    /**
     * Starts a frame whose first packet has RTP sequence number [sequenceNumber] and timestamp [rtpTimestamp], at
     * [nowMs]. Called for a packet which [classify] found to begin a frame: a [PacketKind.NEW_FRAME], or a
     * [PacketKind.RESTART] if [restart].
     *
     * The time since the previous frame started is an interval between frames, from which [frameIntervalMs] learns:
     * - An interval longer than [maxIntervalMs] is never learned. It is a pause, such as the sender turning the
     *   encoding off for a while, and ignoring it keeps the estimate bounded.
     * - The first interval, and any interval no longer than twice the current estimate, are learned immediately.
     * - A longer interval is learned only if the two before it were at least half as long. So a drop to a slower
     *   frame rate is learned within a few frames, while one or two pauses don't stretch the estimate.
     *
     * Nothing is learned from a restart: the interval across it may be a pause of any length.
     */
    fun startFrame(nowMs: Long, sequenceNumber: Int, rtpTimestamp: Long, restart: Boolean, maxIntervalMs: Long) {
        if (!restart && lastFrameStartMs != NOT_STARTED) {
            val interval = nowMs - lastFrameStartMs
            val learnable = interval in 1..maxIntervalMs &&
                (
                    frameIntervalMs == 0.0 ||
                        interval <= 2 * frameIntervalMs ||
                        (lastIntervalMs >= interval / 2 && intervalBeforeLastMs >= interval / 2)
                    )
            if (learnable) {
                val previous = frameIntervalMs
                frameIntervalMs = if (previous == 0.0) {
                    interval.toDouble()
                } else {
                    previous + FRAME_INTERVAL_ALPHA * (interval - previous)
                }
            }
            intervalBeforeLastMs = lastIntervalMs
            lastIntervalMs = interval
        }
        lastFrameTimestamp = rtpTimestamp
        lastFrameSequenceNumber = sequenceNumber
        lastFrameStartMs = nowMs
    }

    /** An estimator in the same state as this estimator. */
    fun copy() = FrameIntervalEstimator().also {
        it.lastFrameTimestamp = lastFrameTimestamp
        it.lastFrameSequenceNumber = lastFrameSequenceNumber
        it.lastFrameStartMs = lastFrameStartMs
        it.lastIntervalMs = lastIntervalMs
        it.intervalBeforeLastMs = intervalBeforeLastMs
        it.frameIntervalMs = frameIntervalMs
    }

    companion object {
        /** The value of [lastFrameTimestamp] before any frame. An RTP timestamp is unsigned, so never negative. */
        private const val NO_TIMESTAMP = -1L

        /** The value of [lastFrameStartMs] before any frame. A clock time is never negative. */
        private const val NOT_STARTED = -1L

        /**
         * The RTP clock rate this class assumes, that of every video payload format. Only video encodings are
         * estimated. Estimating audio would need this estimator to follow each stream's own clock rate instead, such as
         * 48 kHz for Opus.
         */
        private const val VIDEO_RTP_CLOCK_RATE = 90_000L

        /**
         * How far behind the current frame's first packet, in RTP sequence numbers, a reordered or retransmitted
         * packet's can be. A sender keeps about a second of packets for retransmission, which is a few hundred at
         * the highest bitrates the bridge sees, so this is generous. A packet further behind is a
         * [PacketKind.RESTART]. A sender may restart its sequence numbers to within this difference of the old sequence
         * numbers. Its stream is picked up when the new sequence numbers pass the old sequence numbers. It is
         * picked up immediately if the encoding is no longer live, see [isAheadOfCurrentFrameBy].
         */
        const val MAX_STRAGGLER_SEQUENCE_DELTA = 4096

        /** The weight of the most recent interval in [frameIntervalMs]. */
        private const val FRAME_INTERVAL_ALPHA = 0.25
    }
}
