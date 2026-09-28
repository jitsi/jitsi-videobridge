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

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import org.jitsi.nlj.FrameIntervalEstimator.PacketKind
import org.jitsi.rtp.util.RtpUtils

/**
 * Tracks whether one encoding of a video source is currently being sent, judging by the media packets received on
 * it; see [isLive]. Each [RtpEncodingDesc] has a tracker, as its [RtpEncodingDesc.liveness].
 *
 * A simulcast sender turns encodings on and off in two ways: on its own, based on its bandwidth estimate, and in
 * response to the bridge's signaling. The bridge sees neither in step with the media, so the media itself is the only
 * reliable sign of which encodings are being sent.
 *
 * How long an encoding may go without a packet depends on its source's video type; the source sets [timeoutMs]. An
 * encoding sending slowly is allowed longer, based on the estimate of its frame interval which a
 * [FrameIntervalEstimator] makes from its packets' timestamps.
 *
 * A sender which negotiates the Video Layers Allocation RTP header extension says outright which encodings it is
 * sending, with every keyframe and after every change; see [onSignaled]. The VLA header extension is taken as
 * authoritative. An encoding the VLA says it is not sending is not live, whatever was received before, until a new VLA
 * says it is, or media proves otherwise. Media on the encoding is the surer sign, so a packet clears the signal.
 *
 * The receive pipeline records every media packet of the encoding with [onPacketReceived], on its own thread. The
 * bandwidth allocation and projection code of every receiver of the source reads the state from other threads, so
 * that state is volatile.
 */
class EncodingLivenessTracker(timeoutMs: Long = EncodingLivenessConfig.cameraTimeout.toMillis()) {
    /**
     * How long the encoding may go without a media packet and still be live, in milliseconds; see [isLive]. Its own
     * frame interval may allow longer. This depends on the video type of the encoding's source: a screen sharing
     * encoder with static content leaves much longer gaps between frames than a camera encoder does. The source
     * sets this timeout, see [MediaSourceDesc.videoType]; until then it is the camera timeout.
     */
    @Volatile
    var timeoutMs: Long = timeoutMs
        internal set

    /**
     * When the most recent media packet of the encoding was received, in the receive pipeline's clock, or
     * [NEVER_RECEIVED] if none has been.
     */
    @Volatile
    var lastPacketReceivedMs: Long = NEVER_RECEIVED
        private set

    /**
     * When the encoding's current run of frames began, or [NEVER_RECEIVED] if no packet has been received. Used by
     * [hasOutlasted].
     *
     * A run is a stretch of frames without a pause. A new run begins with:
     * - the encoding's first frame;
     * - the first frame after a gap without a packet longer than the liveness allowance of [isLive]. A sender's
     *   signal that the encoding is being sent, which [isLive] also honors, does not count, since runs are about
     *   media;
     * - once the frame interval is known, the first frame after a gap longer than both twice the frame interval and
     *   half of [timeoutMs]. This is a pause even if [isLive] allowed it, as it does for a slow encoding. The
     *   half-timeout floor keeps a frame or two dropped by the encoder from ending a run.
     *
     * Packets recorded without a timestamp, as tests do, show no frames or pauses, so the run begins with the first
     * packet.
     */
    @Volatile
    var flowingSinceMs: Long = NEVER_RECEIVED
        private set

    /**
     * Whether the sender's most recent signal said it is not sending the encoding; see [onSignaled]. Cleared by a
     * signal that it is, or by a media packet of the encoding.
     */
    @Volatile
    var signaledOff: Boolean = false
        private set

    /** When the sender most recently signaled that it is sending the encoding, or [NEVER_RECEIVED]. */
    @Volatile
    private var lastSignaledActiveMs: Long = NEVER_RECEIVED

    /** The estimate of the encoding's frame interval, from its packets' timestamps. */
    private var frames = FrameIntervalEstimator()

    /**
     * The highest RTP sequence number received, or [NO_SEQUENCE_NUMBER]. Only a packet newer than every packet
     * received so far counts toward liveness. A reordered or retransmitted packet, even a packet of the current
     * frame, was sent earlier than the newest packet, so it says nothing about whether the encoding is being sent
     * now.
     */
    private var highestSequenceNumber: Int = NO_SEQUENCE_NUMBER

    /**
     * The smoothed interval between the starts of consecutive frames of the encoding, in milliseconds, or 0 until
     * an interval has been learned; see [FrameIntervalEstimator]. An encoder sending slowly is still sending,
     * whether it was adapted down to a few frames per second or its content is static, so [isLive] scales its
     * timeout with this.
     */
    val frameIntervalMs: Double
        get() = frames.frameIntervalMs

    /**
     * Records that a media packet of the encoding was received at [nowMs], without its RTP timestamp. The packet
     * counts toward liveness, but not toward frames or the frame interval. This method is for tests which only need
     * an encoding marked live at a given time; the receive pipeline always records packets with their timestamps.
     */
    fun onPacketReceived(nowMs: Long) = onPacketReceived(nowMs, 0, NO_TIMESTAMP)

    /**
     * Records that a media packet of the encoding was received at [nowMs].
     *
     * [sequenceNumber] and [rtpTimestamp] are the packet's RTP sequence number and timestamp, which identify its frame.
     * The timestamp determines which frame the packet belongs to. The sequence number indicates whether that frame is
     * newer than the frames seen, since timestamps need not increase in the order sent. The receive pipeline always
     * passes them. Only tests pass [NO_TIMESTAMP], through the other [onPacketReceived]; in that case the packet only
     * counts toward liveness.
     *
     * What the packet is depends on the two parameters, see [FrameIntervalEstimator.classify]:
     * - A reordered or retransmitted packet, of an older frame or of the current frame, is ignored entirely, even for
     *   liveness. It indicates nothing about whether the encoding is being sent now. There is an exception. Suppose the
     *   encoding is not live, and a packet arrives which is part of an older frame by sequence number, but whose
     *   timestamp is ahead of the current frame's by more than the encoding's liveness allowance. Then the sender has
     *   restarted its sequence numbers just behind the old ones, and the stream resumes from the packet. A
     *   retransmission is never that far ahead: its timestamp is older, or, with frame reordering, newer by at most a
     *   few frames. The same holds regardless of the timestamp once the encoding has been quiet for [RESTART_QUIET_MS].
     *   No retransmission arrives that late. After an outage that long the sequence numbers may have wrapped to
     *   appear older, and after several hours so may the timestamps.
     * - The first packet of a frame may begin a new run of frames, see [flowingSinceMs]. This includes the packet
     *   from which the stream resumes after its sequence numbers restarted. It also starts the frame in
     *   the estimator, which learns no interval longer than [LEARNABLE_GAP_TIMEOUTS] times [timeoutMs]; see
     *   [FrameIntervalEstimator.startFrame].
     * - Every packet but a straggler counts toward liveness.
     */
    fun onPacketReceived(nowMs: Long, sequenceNumber: Int, rtpTimestamp: Long) {
        if (rtpTimestamp == NO_TIMESTAMP) {
            if (lastPacketReceivedMs == NEVER_RECEIVED) {
                flowingSinceMs = nowMs
            }
        } else {
            var kind = frames.classify(sequenceNumber, rtpTimestamp)
            if (kind == PacketKind.STRAGGLER) {
                val restarted = nowMs - lastPacketReceivedMs > RESTART_QUIET_MS ||
                    frames.isAheadOfCurrentFrameBy(rtpTimestamp, allowedGapMs())
                if (isLive(nowMs) || !restarted) {
                    return
                }
                kind = PacketKind.RESTART
            }
            if (kind == PacketKind.SAME_FRAME &&
                !RtpUtils.isNewerSequenceNumberThan(sequenceNumber, highestSequenceNumber)
            ) {
                return
            }
            highestSequenceNumber = sequenceNumber
            if (kind != PacketKind.SAME_FRAME) {
                if (startsRun(nowMs)) {
                    flowingSinceMs = nowMs
                }
                frames.startFrame(
                    nowMs,
                    sequenceNumber,
                    rtpTimestamp,
                    restart = kind == PacketKind.RESTART,
                    maxIntervalMs = LEARNABLE_GAP_TIMEOUTS * timeoutMs
                )
            }
        }
        lastPacketReceivedMs = nowMs
        if (signaledOff) {
            signaledOff = false
        }
    }

    /**
     * Whether a packet with RTP sequence number [sequenceNumber] and timestamp [rtpTimestamp] is part of an older frame
     * than the current frame. Such a packet is reordered or retransmitted; its arrival indicates nothing about the
     * encoding currently, and neither does anything it carries. This is used by nodes after the node which records the
     * encoding's packets, to indicate whether a header extension is stale. A late packet of the current frame is not
     * part of an older frame, and what it carries is current. The packet that has just been recorded is part of the
     * current frame by definition.
     */
    fun isOfOlderFrame(sequenceNumber: Int, rtpTimestamp: Long): Boolean =
        frames.classify(sequenceNumber, rtpTimestamp) == PacketKind.STRAGGLER

    /**
     * Records that at [nowMs] the sender signaled whether it is sending the encoding ([active]). The Video Layers
     * Allocation header extension signals this for every encoding of a source. A signal that the encoding is being
     * encoded counts like a packet for [isLive]; a signal that it is not being encoded makes the encoding not live
     * immediately. The encoding stays not live until the next signal that it is live, or until one of its media packets
     * arrives.
     */
    fun onSignaled(active: Boolean, nowMs: Long) {
        if (active) {
            lastSignaledActiveMs = nowMs
            signaledOff = false
        } else {
            signaledOff = true
        }
    }

    /**
     * Whether a frame starting at [nowMs] begins a new run of frames; see [flowingSinceMs]. This is judged by the gap
     * since the previous frame and the frame interval known so far, before the estimator learns from this frame.
     */
    private fun startsRun(nowMs: Long): Boolean {
        val intervalMs = frames.frameIntervalMs
        return lastPacketReceivedMs == NEVER_RECEIVED ||
            nowMs - lastPacketReceivedMs > allowedGapMs() ||
            (intervalMs > 0.0 && frames.msSinceFrameStart(nowMs) > maxOf(2 * intervalMs, timeoutMs / 2.0))
    }

    /** How long the encoding may go without a packet and still be live. */
    private fun allowedGapMs(): Long {
        val intervalsMs = (LIVENESS_FRAME_INTERVALS * frameIntervalMs).toLong()
        return maxOf(timeoutMs, minOf(intervalsMs, MAX_ALLOWANCE_TIMEOUTS * timeoutMs))
    }

    /**
     * Whether the encoding is being sent, judged by whether one of its media packets was received recently. Recently
     * means within [timeoutMs] of [nowMs], or within [LIVENESS_FRAME_INTERVALS] of its own smoothed frame interval,
     * whichever is longer, but never more than [MAX_ALLOWANCE_TIMEOUTS] timeouts. This reflects a change within a
     * few frame intervals, whereas the layers' bitrate measurements take several seconds. A signal from the sender
     * that it is sending the encoding counts like a packet. A signal that it is not sending it overrides
     * everything; see [onSignaled].
     */
    fun isLive(nowMs: Long): Boolean {
        if (signaledOff) {
            return false
        }
        val last = maxOf(lastPacketReceivedMs, lastSignaledActiveMs)
        if (last == NEVER_RECEIVED) {
            return false
        }
        return nowMs - last <= allowedGapMs()
    }

    /**
     * Whether this encoding has kept being sent while the encoding [other] tracks stopped. That is, this encoding
     * is live, and either its current run of frames began before [other]'s last packet, or the run began longer ago
     * than [other] may go without a packet (see [isLive]).
     *
     * Two situations make this encoding live while [other] is not, and only one of them means the sender turned the
     * other encoding off. In that situation this encoding kept flowing without a break while the other went quiet, so
     * its current run began before the other's last packet. In the other situation the whole source stalled and is
     * resuming. Every encoding went quiet, and this encoding's first frames after the stall arrive before the other
     * encoding's do, so the other looks stopped too. But this encoding's run began only now, after the other's last
     * packet, so it has not outlasted the other, and the other is given its allowance to resume. If this encoding
     * then flows for longer than that allowance and the other still has not resumed, the other is off after all;
     * that is the second condition. A sender's signal that it is not sending [other]'s encoding settles the question
     * outright; see [onSignaled].
     */
    fun hasOutlasted(other: EncodingLivenessTracker, nowMs: Long): Boolean {
        if (!isLive(nowMs)) {
            return false
        }
        if (other.signaledOff) {
            return true
        }
        val since = flowingSinceMs
        if (since == NEVER_RECEIVED) {
            /* Live without any packet, which only the sender's signal can cause: it has not sent anything yet. */
            return false
        }
        return since <= other.lastPacketReceivedMs || nowMs - since > other.allowedGapMs()
    }

    /**
     * The state for debugging, as of [nowMs]: whether the encoding is live, when its last packet arrived, and when
     * its current run of frames began. It also holds its frame interval and the frame rate that implies.
     */
    fun debugState(nowMs: Long): ObjectNode = JsonNodeFactory.instance.objectNode().apply {
        put("live", isLive(nowMs))
        put("signaled_off", signaledOff)
        put("timeout_ms", timeoutMs)
        put("last_packet_received_ms", lastPacketReceivedMs)
        put("flowing_since_ms", flowingSinceMs)
        val intervalMs = frameIntervalMs
        put("frame_interval_ms", intervalMs)
        put("frame_rate", if (intervalMs > 0.0) 1000.0 / intervalMs else 0.0)
    }

    /** A tracker in the same state as this tracker, for a copy of the encoding. */
    fun copy() = EncodingLivenessTracker(timeoutMs).also {
        it.lastPacketReceivedMs = lastPacketReceivedMs
        it.highestSequenceNumber = highestSequenceNumber
        it.flowingSinceMs = flowingSinceMs
        it.signaledOff = signaledOff
        it.lastSignaledActiveMs = lastSignaledActiveMs
        it.frames = frames.copy()
    }

    companion object {
        /** The value of [lastPacketReceivedMs] before any packet has been received. */
        const val NEVER_RECEIVED = -1L

        /**
         * The value of [highestSequenceNumber] before any packet has been received; a real sequence number is never
         * negative.
         */
        private const val NO_SEQUENCE_NUMBER = -1

        /**
         * Passed to [onPacketReceived] for a packet recorded without its RTP timestamp, which only tests do. A real RTP
         * timestamp is never negative, so this value can't be mistaken for a real timestamp.
         */
        const val NO_TIMESTAMP = -1L

        /** How many of its own frame intervals an encoding may go without a packet and still be live. */
        const val LIVENESS_FRAME_INTERVALS = 4

        /** The longest gap between frames, in liveness timeouts, still learned as a frame interval. */
        const val LEARNABLE_GAP_TIMEOUTS = 3

        /**
         * The longest an encoding may go without a packet and still be live, in liveness timeouts, regardless of its
         * frame interval. Twice the longest learnable interval, so the slowest encoding still learned is allowed two
         * of its intervals. A stopped encoding is not kept live for [LIVENESS_FRAME_INTERVALS] of them.
         */
        const val MAX_ALLOWANCE_TIMEOUTS = 2 * LEARNABLE_GAP_TIMEOUTS

        /**
         * How long an encoding must have been quiet for any of its packets to resume it, whatever the packet's
         * sequence number and timestamp say; see [onPacketReceived]. Longer than any retransmission takes: the bridge
         * gives up on a packet after [org.jitsi.nlj.rtcp.RetransmissionRequester] has asked for it ten times, 150 ms
         * apart, and a sender keeps about a second of packets for retransmission. The rule applies only once the
         * encoding is not live, so this need not exceed the liveness allowance. Short, so that a sender which
         * restarts with a random sequence number and timestamp is not hidden for long if they happen to look older.
         */
        const val RESTART_QUIET_MS = 5_000L
    }
}
