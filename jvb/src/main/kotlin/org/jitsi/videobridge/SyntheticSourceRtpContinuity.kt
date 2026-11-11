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
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * Keeps the RTP sequence numbers and timestamps of the synthetic audio sources of a conference continuous when the
 * producer of a source (a translator, or the gateway of a voice agent) starts its numbering again. A producer does that
 * on every new connection.
 *
 * The source keeps its SSRC when the producer connects again. Without this, the stream goes back to sequence numbers
 * that were used before. The SRTP replay protection of a receiver then drops the packets (it got these numbers before,
 * or they are too old), and the source is silent until its numbers pass the highest one sent before.
 *
 * The numbers of the producer are not changed until it starts again. After that, the sequence numbers continue from
 * the last one sent. The timestamps continue from the last one by the time that passed since, when the new start is
 * reported or the timestamps go back.
 *
 * A new start is known in two ways:
 * - [producerStarted], when the connection to the producer opens. This is exact.
 * - Numbers that go back, for a producer that starts again on the same connection. This finds a new start when the
 * timestamps go back, or when the chunk numbers go back by less than 2^15. It has two limits. A new start that is
 * found by the chunk numbers alone keeps the timestamps of the producer, so one that starts at the last timestamp
 * repeats it. A new start with later timestamps and chunk numbers that are 2^15 or more behind is not found:
 * receivers then see a loss of that many packets.
 *
 * The state of an SSRC is kept for the lifetime of the conference (only a signaled synthetic source gets one, and it
 * is small), because a source that is removed and added again with the same SSRC must continue from the numbers
 * already sent.
 *
 * The state is local to this bridge. When a source keeps its SSRC but its producer moves to a connect on another bridge
 * (a translator in the single-bridge mode), the receivers still see the numbers start again. Jicofo would have to give
 * the source a new SSRC then, as it does for the source of an agent.
 *
 * For one SSRC, [nextPacket] and [sendingChangeTimestamp] must be called in the order in which the producer sent the
 * media and the sending changes.
 */
class SyntheticSourceRtpContinuity(private val clock: Clock, parentLogger: Logger) {
    private val logger = createChildLogger(parentLogger)

    /** The sequence number (16 bits) and the timestamp (32 bits) of a packet. */
    data class Numbers(val sequenceNumber: Int, val timestamp: Long)

    private val sources = ConcurrentHashMap<Long, Source>()

    /**
     * @param ssrc the SSRC of the synthetic source
     * @param chunk the chunk number of the media from the producer, used as the sequence number (the low 16 bits)
     * @param timestamp the timestamp of the media from the producer. It is not wrapped, and starts again when the
     * producer does.
     * @return the sequence number and the timestamp for the RTP packet
     */
    fun nextPacket(ssrc: Long, chunk: Int, timestamp: Long): Numbers = source(ssrc).nextPacket(chunk, timestamp)

    /**
     * @param ssrc the SSRC of the synthetic source
     * @param timestamp the timestamp of a sending change from the producer, on the timeline of its media
     * @return the timestamp (32 bits) on the timeline of the RTP packets
     */
    fun sendingChangeTimestamp(ssrc: Long, timestamp: Long): Long = source(ssrc).sendingChangeTimestamp(timestamp)

    /**
     * Notes that the producer of the sources with [ssrcs] starts its numbering again, because the connection to it
     * opened. A source that sent nothing yet has nothing to continue from, and keeps the numbers of the producer.
     */
    fun producerStarted(ssrcs: Collection<Long>) = ssrcs.forEach { sources[it]?.producerStarted() }

    private fun source(ssrc: Long) = sources.computeIfAbsent(ssrc) { Source(it) }

    private inner class Source(private val ssrc: Long) {
        /** What to add to the chunk numbers and to the timestamps of the producer. */
        private var sequenceNumberDelta = 0
        private var timestampDelta = 0L

        /** The chunk number and the timestamp that the producer sent last, or null before the first. */
        private var lastChunk: Int? = null
        private var lastTimestamp: Long? = null

        /** The sequence number and the timestamp (not wrapped) given out last, and when. */
        private var lastSequenceNumberOut = -1
        private var lastTimestampOut = 0L
        private var lastOutAt: Instant = clock.instant()

        /** The connection to the producer opened, and nothing came from it since. */
        private var startSignaled = false

        /** The producer started again, and no packet got a sequence number since. */
        private var sequenceNumberPending = false

        @Synchronized
        fun producerStarted() {
            if (lastTimestamp != null) {
                startSignaled = true
            }
        }

        @Synchronized
        fun nextPacket(chunk: Int, timestamp: Long): Numbers {
            val sequenceNumber = chunk and 0xFFFF
            val timestampOut = advance(timestamp)
            // The chunk numbers of a producer only go up (modulo 2^16), so one that does not is the first after a new
            // start. One that is 2^15 or more behind looks like a step forward, and is not found here.
            if (!sequenceNumberPending &&
                lastChunk?.let { RtpUtils.getSequenceNumberDelta(sequenceNumber, it) <= 0 } == true
            ) {
                sequenceNumberPending = true
                logger.info("The producer of SSRC $ssrc started again (its chunk numbers went back).")
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

        @Synchronized
        fun sendingChangeTimestamp(timestamp: Long): Long = advance(timestamp) and 0xFFFF_FFFFL

        private fun advance(timestamp: Long): Long {
            val now = clock.instant()
            // The timestamps of a producer never go back on one connection, so one that does is the first after a new
            // start. The sending change that starts a talk comes before its media, so the sequence numbers continue
            // at the next packet.
            val wentBack = lastTimestamp?.let { timestamp < it } == true
            if (startSignaled || wentBack) {
                val elapsed = Duration.between(lastOutAt, now).toMillis() * SAMPLES_PER_MS
                timestampDelta = lastTimestampOut + max(SAMPLES_PER_FRAME, elapsed) - timestamp
                sequenceNumberPending = true
                logger.info(
                    "The producer of SSRC $ssrc started again " +
                        "(${if (startSignaled) "its connection opened" else "its timestamps went back"}): " +
                        "continuing after sequence number $lastSequenceNumberOut."
                )
                startSignaled = false
            }
            lastTimestamp = timestamp
            lastTimestampOut = timestamp + timestampDelta
            lastOutAt = now
            return lastTimestampOut
        }
    }

    companion object {
        /** Opus in RTP always has a clock rate of 48 kHz. */
        private const val SAMPLES_PER_MS = 48L

        /** The timestamps continue at least one 20 ms frame after the last one. */
        private const val SAMPLES_PER_FRAME = 960L
    }
}
