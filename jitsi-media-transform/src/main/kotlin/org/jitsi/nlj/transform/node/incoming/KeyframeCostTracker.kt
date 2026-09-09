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
package org.jitsi.nlj.transform.node.incoming

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import org.jitsi.nlj.util.Bandwidth
import org.jitsi.nlj.util.BitrateTracker
import org.jitsi.nlj.util.DataSize
import org.jitsi.nlj.util.bits
import org.jitsi.nlj.util.bytes
import org.jitsi.nlj.util.per
import org.jitsi.rtp.util.RtpUtils
import org.jitsi.utils.ms
import org.jitsi.utils.secs
import java.time.Duration
import kotlin.math.roundToLong

/**
 * Measures, for one RTP stream (one SSRC), what a keyframe costs relative to the stream: the mean size of a
 * keyframe, the rate at which keyframe bytes have been arriving, and the stream's total bitrate. Both rates are
 * counted from the same packets over the same window, so they agree regardless of how the codec's layers are
 * described.
 *
 * A frame is all of the packets sharing an RTP timestamp, and it counts as a keyframe if any of its packets is
 * marked as one. Its size is the total size of all of those packets. This is what makes the measurement independent
 * of the codec: VP8 and AV1 mark only the first packet of a keyframe, and VP9 marks only spatial layer 0, while the
 * upper spatial layers of a K-SVC key picture share its timestamp and are as much a part of the keyframe as layer 0.
 *
 * A frame is folded into the measurements once [GRACE_PERIOD] has passed since its most recent packet, so packets
 * reordered across a frame boundary, or recovered by retransmission, still count towards their frame, and a large
 * keyframe paced out over longer than the grace period is still measured whole. A frame with no successor, as on a
 * static source, is folded the same way. Packets of a frame already folded in are ignored.
 *
 * Simulcast encodings do not share a timestamp base, so each SSRC needs its own tracker.
 */
class KeyframeCostTracker(private val alpha: Double = DEFAULT_ALPHA) {
    private class Frame(val timestamp: Long, firstSeenMs: Long) {
        var lastSeenMs = firstSeenMs
        var bytes: Long = 0
        var isKeyframe = false
    }

    /** The frames which may still receive packets, in order of first arrival. */
    private val frames = ArrayList<Frame>()
    private var newestTimestamp: Long = NO_TIMESTAMP

    /** The newest timestamp of any frame folded in so far. Packets at or before it belong to a folded frame. */
    private var lastFoldedTimestamp: Long = NO_TIMESTAMP

    /**
     * When the stream's current period of activity began. Reset when the stream has been idle for a whole window,
     * so that the rates below are measured over the time the stream has actually been sending, and so that a stream
     * which resumes is treated as one which has just started.
     */
    private var activeSinceMs: Long = -1

    private var meanKeyframeBits: Double = 0.0

    private val streamBytes = BitrateTracker(BITRATE_WINDOW, BITRATE_BUCKET)
    private val keyframeBytes = BitrateTracker(BITRATE_WINDOW, BITRATE_BUCKET)

    /** The number of keyframes observed. */
    @Volatile
    var numKeyframes: Int = 0
        private set

    /** The size of the most recently observed keyframe, or 0 if none has been observed. */
    @Volatile
    var lastKeyframeSize: DataSize = 0.bits
        private set

    /**
     * Observe a packet with RTP timestamp [timestamp] and total size [bytes], received at [nowMs]. [isKeyframe] is
     * whether the codec marks this packet as belonging to a keyframe, or false if that is not known.
     */
    @Synchronized
    fun observe(timestamp: Long, bytes: Int, isKeyframe: Boolean, nowMs: Long) {
        if (activeSinceMs < 0 || streamBytes.getAccumulatedSize(nowMs).bits == 0L) {
            // The stream has started, or resumed after being idle for a whole window.
            activeSinceMs = nowMs
        }
        streamBytes.update(bytes.bytes, nowMs)

        var frame = frames.firstOrNull { it.timestamp == timestamp }
        if (frame == null) {
            if (newestTimestamp != NO_TIMESTAMP && RtpUtils.isOlderTimestampThan(timestamp, newestTimestamp)) {
                if (RtpUtils.getTimestampDiff(newestTimestamp, timestamp) >= LARGE_JUMP_TICKS) {
                    // Too far back to be reordering: the timeline has restarted. Start over from this packet.
                    frames.forEach { fold(it, nowMs) }
                    frames.clear()
                    newestTimestamp = NO_TIMESTAMP
                    lastFoldedTimestamp = NO_TIMESTAMP
                } else if (lastFoldedTimestamp != NO_TIMESTAMP &&
                    !RtpUtils.isNewerTimestampThan(timestamp, lastFoldedTimestamp)
                ) {
                    // A late packet of a frame which has already been folded in. Ignore it.
                    return
                }
            }
            frame = Frame(timestamp, nowMs)
            frames.add(frame)
            if (newestTimestamp == NO_TIMESTAMP || RtpUtils.isNewerTimestampThan(timestamp, newestTimestamp)) {
                newestTimestamp = timestamp
            }
        }
        frame.lastSeenMs = nowMs
        frame.bytes += bytes
        if (isKeyframe) {
            frame.isKeyframe = true
        }

        foldExpired(nowMs)
    }

    /** Fold in every frame which has not received a packet for [GRACE_PERIOD], and any excess beyond [MAX_FRAMES]. */
    private fun foldExpired(nowMs: Long) {
        val iterator = frames.iterator()
        while (iterator.hasNext()) {
            val f = iterator.next()
            if (nowMs - f.lastSeenMs >= GRACE_PERIOD_MS || frames.size > MAX_FRAMES) {
                fold(f, nowMs)
                iterator.remove()
            }
        }
    }

    private fun fold(frame: Frame, nowMs: Long) {
        if (frame.isKeyframe && frame.bytes > 0) {
            val bits = frame.bytes * 8.0
            meanKeyframeBits = if (numKeyframes == 0) bits else meanKeyframeBits * (1 - alpha) + bits * alpha
            lastKeyframeSize = frame.bytes.bytes
            numKeyframes++
            keyframeBytes.update(frame.bytes.bytes, nowMs)
        }
        if (lastFoldedTimestamp == NO_TIMESTAMP ||
            RtpUtils.isNewerTimestampThan(frame.timestamp, lastFoldedTimestamp)
        ) {
            lastFoldedTimestamp = frame.timestamp
        }
    }

    /**
     * The time over which the rates are measured: how long the stream has been active, up to [BITRATE_WINDOW], and
     * no less than [WARM_UP], so that the first packets of a stream, typically a keyframe, are not read as a rate
     * the stream will not sustain.
     */
    private fun measurementWindow(nowMs: Long): Duration =
        Duration.ofMillis((nowMs - activeSinceMs).coerceIn(WARM_UP_MS, BITRATE_WINDOW_MS))

    /**
     * An exponentially weighted moving average of the size of a keyframe, or null if none has been observed. Folds in
     * any frame which has expired by [nowMs] first, so a keyframe with no successor is counted once it is complete.
     */
    @Synchronized
    fun getMeanKeyframeSize(nowMs: Long): DataSize? {
        foldExpired(nowMs)
        return if (numKeyframes > 0) meanKeyframeBits.roundToLong().bits else null
    }

    /**
     * Whether the stream has been active for at least [WARM_UP] at [nowMs], so that its bitrate is meaningful. Until
     * then the bitrate is dominated by whichever packets happened to arrive first, typically a keyframe.
     */
    @Synchronized
    fun isWarm(nowMs: Long): Boolean = activeSinceMs >= 0 && nowMs - activeSinceMs >= WARM_UP_MS

    /** The stream's bitrate at [nowMs], over the last [BITRATE_WINDOW] or since the stream became active. */
    @Synchronized
    fun getStreamBitrate(nowMs: Long): Bandwidth = if (activeSinceMs < 0) {
        0.bits.per(
            BITRATE_WINDOW
        )
    } else {
        streamBytes.getAccumulatedSize(nowMs).per(measurementWindow(nowMs))
    }

    /** The rate at which keyframe bytes have arrived at [nowMs], over the same window as [getStreamBitrate]. */
    @Synchronized
    fun getKeyframeBitrate(nowMs: Long): Bandwidth {
        foldExpired(nowMs)
        return if (activeSinceMs < 0) {
            0.bits.per(BITRATE_WINDOW)
        } else {
            keyframeBytes.getAccumulatedSize(nowMs).per(measurementWindow(nowMs))
        }
    }

    /** The calculated values, and the raw state of the frames still in flight. */
    @Synchronized
    fun debugState(nowMs: Long): ObjectNode = JsonNodeFactory.instance.objectNode().apply {
        foldExpired(nowMs)
        put("mean_keyframe_bits", meanKeyframeBits)
        put("last_keyframe_bits", lastKeyframeSize.bits)
        put("num_keyframes", numKeyframes)
        put("keyframe_bitrate_bps", getKeyframeBitrate(nowMs).bps)
        put("stream_bitrate_bps", getStreamBitrate(nowMs).bps)
        put("active_ms", if (activeSinceMs < 0) 0L else nowMs - activeSinceMs)
        put("warm", isWarm(nowMs))
        put("frames_in_flight", frames.size)
        put("last_folded_frame_timestamp", lastFoldedTimestamp)
        put("newest_frame_timestamp", newestTimestamp)
        put("newest_frame_bytes", frames.firstOrNull { it.timestamp == newestTimestamp }?.bytes ?: 0L)
        put("newest_frame_is_keyframe", frames.firstOrNull { it.timestamp == newestTimestamp }?.isKeyframe ?: false)
    }

    companion object {
        const val DEFAULT_ALPHA = 0.25
        private const val NO_TIMESTAMP = -1L

        /**
         * The window over which both the stream's bitrate and the keyframe bitrate are measured. Long enough to
         * hold several keyframes at the intervals the source-wide limit allows, and for one keyframe in flight to
         * barely move the stream's bitrate.
         */
        val BITRATE_WINDOW = 10.secs
        private val BITRATE_WINDOW_MS = BITRATE_WINDOW.toMillis()
        val BITRATE_BUCKET = 500.ms

        /** How long a frame stays open for further packets after its most recent one. */
        val GRACE_PERIOD = 250.ms
        private val GRACE_PERIOD_MS = GRACE_PERIOD.toMillis()

        /** A bound on the frames kept in flight, in case a stream's frame rate is much higher than expected. */
        private const val MAX_FRAMES = 32

        /** How long a stream must have been active before its bitrate is used. */
        val WARM_UP = 1.secs
        private val WARM_UP_MS = WARM_UP.toMillis()

        /**
         * A backwards timestamp jump at least this large (1 second at 90 kHz) is a restarted timeline rather than a
         * reordered packet: a packet reordered by more than [GRACE_PERIOD] would be ignored anyway.
         */
        private const val LARGE_JUMP_TICKS = 90_000L
    }
}
