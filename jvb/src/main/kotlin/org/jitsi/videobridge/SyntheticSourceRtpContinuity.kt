/*
 * Copyright @ 2026 - Present, 8x8, Inc.
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
package org.jitsi.videobridge

import org.jitsi.rtp.util.RtpUtils
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.math.max

/**
 * Keeps the RTP sequence numbers and timestamps of a synthetic audio source continuous when the peer that sends its
 * media (a translator, or the gateway of a voice agent) starts its numbering again. A peer does that on every new
 * connection.
 *
 * The source keeps its SSRC when the peer connects again. Without this, the stream goes back to sequence numbers that
 * were used before. The SRTP replay protection of a receiver then drops the packets (it got these numbers before, or
 * they are too old), and the source is silent until its numbers pass the highest one sent before.
 *
 * The numbers of the peer are not changed until it starts again. After that, the sequence numbers continue from the
 * last one sent, and the timestamps continue from the last one by the time that passed since.
 *
 * [packet] and [timestamp] must be called in the order in which the peer sent the media and the sending changes.
 */
class SyntheticSourceRtpContinuity(private val clock: Clock) {
    /** The sequence number (16 bits) and the timestamp (32 bits) of a packet. */
    data class Numbers(val sequenceNumber: Int, val timestamp: Long)

    /** What to add to the chunk numbers and to the timestamps of the peer. */
    private var sequenceNumberDelta = 0
    private var timestampDelta = 0L

    /** The chunk number and the timestamp that the peer sent last, or null before the first. */
    private var lastChunk: Int? = null
    private var lastTimestamp: Long? = null

    /** The sequence number and the timestamp (not wrapped) given out last, and when. */
    private var lastSequenceNumberOut = -1
    private var lastTimestampOut = 0L
    private var lastOutAt: Instant = clock.instant()

    /** The peer started again, and no packet got a sequence number since. */
    private var sequenceNumberPending = false

    /**
     * @param chunk the chunk number of the media from the peer, used as the sequence number (the low 16 bits)
     * @param timestamp the timestamp of the media from the peer, monotonic and not wrapped
     * @return the sequence number and the timestamp for the RTP packet
     */
    @Synchronized
    fun packet(chunk: Int, timestamp: Long): Numbers {
        val sequenceNumber = chunk and 0xFFFF
        val timestampOut = advance(timestamp)
        // The chunk numbers of a peer only go up (modulo 2^16): one that does not is the first after a new start.
        // A new start that comes more than 2^15 chunks after the last one looks like a step forward here, and
        // is found by its timestamp (see advance).
        if (lastChunk?.let { RtpUtils.getSequenceNumberDelta(sequenceNumber, it) <= 0 } == true) {
            sequenceNumberPending = true
        }
        if (sequenceNumberPending) {
            sequenceNumberDelta = RtpUtils.getSequenceNumberDelta(
                RtpUtils.applySequenceNumberDelta(lastSequenceNumberOut, 1),
                sequenceNumber
            )
            sequenceNumberPending = false
        }
        lastChunk = sequenceNumber
        lastSequenceNumberOut = RtpUtils.applySequenceNumberDelta(sequenceNumber, sequenceNumberDelta)
        return Numbers(lastSequenceNumberOut, timestampOut and 0xFFFF_FFFFL)
    }

    /**
     * @param timestamp the timestamp of a sending change from the peer, on the timeline of its media
     * @return the timestamp (32 bits) on the timeline of the RTP packets
     */
    @Synchronized
    fun timestamp(timestamp: Long): Long = advance(timestamp) and 0xFFFF_FFFFL

    private fun advance(timestamp: Long): Long {
        val now = clock.instant()
        // The timestamps of a peer never go back, so one that does is the first after a new start. The sending change
        // that starts a talk comes before its media, so the sequence numbers continue at the next packet.
        if (lastTimestamp?.let { timestamp < it } == true) {
            val elapsed = Duration.between(lastOutAt, now).toMillis() * SAMPLES_PER_MS
            timestampDelta = lastTimestampOut + max(SAMPLES_PER_FRAME, elapsed) - timestamp
            sequenceNumberPending = true
        }
        lastTimestamp = timestamp
        lastTimestampOut = timestamp + timestampDelta
        lastOutAt = now
        return lastTimestampOut
    }

    companion object {
        /** Opus in RTP always has a clock rate of 48 kHz. */
        private const val SAMPLES_PER_MS = 48L

        /** The timestamps continue at least one 20 ms frame after the last one. */
        private const val SAMPLES_PER_FRAME = 960L
    }
}
