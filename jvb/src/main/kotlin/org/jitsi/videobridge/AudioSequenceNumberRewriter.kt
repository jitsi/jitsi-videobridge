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

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.rtp.util.RtpUtils
import org.jitsi.rtp.util.isNewerThan
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Keeps the RTP sequence numbers of the audio streams sent to one receiver contiguous when the bridge does not forward
 * some packets to that receiver (for example, when the receiver's audio subscription excludes a source for some time).
 *
 * Without this, the receiver sees a jump in the sequence numbers. After a jump of more than 2^15, the receiver's SRTP
 * index estimation (RFC 3711, section 3.3.1) treats the next packets as old. The receiver can then drop them for up to
 * 2^16 packets.
 *
 * The decision to forward or drop a packet ([accepted], [dropped]) and the rewrite ([rewrite]) happen on different
 * threads, with the send queue of the receiver between them. So the first packet that is accepted after a drop is
 * recorded as a resume point when it is accepted. [rewrite] closes the gap when it gets to that packet. A packet that
 * was accepted before the drop, and still waits in the send queue, does not close the gap.
 *
 * Gaps from packets that were lost before they reached the bridge are kept, except for a loss next to the dropped
 * packets. Timestamps are not changed, so that the receiver keeps the real timing (and the sender reports stay valid).
 *
 * The state of an SSRC is kept when its source is removed, because a sender (or a relayed endpoint) that is re-created
 * with the same SSRC continues its sequence numbers. The receiver must then continue to get the same offset. The
 * number of SSRCs is limited to [MAX_SSRCS]: when the limit is reached, the state of the SSRC that was used least
 * recently is removed.
 *
 * [accepted] and [dropped] can be called from any thread, but for one SSRC they must be called serially (in the
 * order of the packets). [rewrite] must not be called concurrently (the send queue of the receiver calls it serially).
 */
class AudioSequenceNumberRewriter {
    private class State {
        /** The highest sequence number sent (after rewriting), or -1 before the first packet. */
        var highestSequenceNumberSent = -1

        /** The delta to add to the original sequence numbers. */
        var delta = 0

        /**
         * The original sequence number of the packet that closed the last gap, or -1. A later packet that is older
         * than this one by at most [REORDER_WINDOW] would get a sequence number that was already used, so it is
         * dropped.
         */
        var resumeSequenceNumber = -1

        /** The number of packets in a row that were dropped because they were older than [resumeSequenceNumber]. */
        var lateDropsInRow = 0

        /** Whether the last packet of this SSRC passed to [accepted] or [dropped] was dropped. */
        val dropping = AtomicBoolean(false)

        /**
         * The original sequence numbers of the packets that were accepted first after a drop, and that [rewrite] has
         * not got to yet.
         */
        val resumePoints = ConcurrentLinkedQueue<Int>()

        /** For the eviction of the least recently used state. */
        @Volatile
        var lastUsed = 0L
    }

    private val states = ConcurrentHashMap<Long, State>()

    private val gapsClosed = AtomicLong()
    private val latePacketsDropped = AtomicLong()

    /**
     * Notes that the packet from [ssrc] with original sequence number [sequenceNumber] will be forwarded to the
     * receiver.
     */
    fun accepted(ssrc: Long, sequenceNumber: Int) {
        // Create the state here, and not in [rewrite]: a drop can start while the first packet waits in the send
        // queue. Only a packet of a signaled source is accepted, so unsignaled SSRCs do not get a state.
        val state = getOrCreate(ssrc)
        if (state.dropping.compareAndSet(true, false)) {
            state.resumePoints.add(sequenceNumber)
            // The send queue is short, so only a few resume points can wait at a time.
            while (state.resumePoints.size > MAX_RESUME_POINTS) {
                state.resumePoints.poll()
            }
        }
    }

    /**
     * Notes that a packet from [ssrc] will not be forwarded to the receiver. Drops before the first accepted packet of
     * an SSRC do not leave a gap for the receiver, so they are not recorded.
     */
    fun dropped(ssrc: Long) {
        states[ssrc]?.dropping?.set(true)
    }

    /**
     * Changes the sequence number of [packet] (in place). The packet at a resume point gets the sequence number after
     * the highest one sent, and the new delta then applies to all later packets of the SSRC. The first packet of an
     * SSRC is not changed.
     *
     * @return false if the packet must be dropped, because it arrived late and its sequence number would be one that
     * was already used.
     */
    fun rewrite(packet: RtpPacket): Boolean {
        val sequenceNumber = packet.sequenceNumber
        val state = getOrCreate(packet.ssrc)
        val resume = takeResumePoint(state, sequenceNumber)

        if (state.highestSequenceNumberSent == -1) {
            // Packets dropped before the first packet do not leave a gap for the receiver.
            state.highestSequenceNumberSent = sequenceNumber
            return true
        }

        if (resume) {
            state.delta = RtpUtils.getSequenceNumberDelta(
                RtpUtils.applySequenceNumberDelta(state.highestSequenceNumberSent, 1),
                sequenceNumber
            )
            state.resumeSequenceNumber = sequenceNumber
            state.lateDropsInRow = 0
            gapsClosed.incrementAndGet()
        } else if (state.resumeSequenceNumber != -1) {
            val sinceResume = RtpUtils.getSequenceNumberDelta(sequenceNumber, state.resumeSequenceNumber)
            if (sinceResume in -REORDER_WINDOW until 0) {
                if (++state.lateDropsInRow <= MAX_LATE_DROPS_IN_ROW) {
                    latePacketsDropped.incrementAndGet()
                    return false
                }
                // Too many in a row to be late packets. For example, a sender that started again with a new sequence
                // number.
                state.resumeSequenceNumber = -1
            } else {
                state.lateDropsInRow = 0
                if (sinceResume !in 0..REORDER_WINDOW) {
                    // Too far from the resume point for a late packet to follow.
                    state.resumeSequenceNumber = -1
                }
            }
        }

        val newSequenceNumber = RtpUtils.applySequenceNumberDelta(sequenceNumber, state.delta)
        packet.sequenceNumber = newSequenceNumber
        if (newSequenceNumber isNewerThan state.highestSequenceNumberSent) {
            state.highestSequenceNumberSent = newSequenceNumber
        }
        return true
    }

    /**
     * Removes the resume points up to [sequenceNumber] from [state]. A resume point that is older than
     * [sequenceNumber] belongs to a packet that was lost after it was accepted, so the gap closes at this packet.
     *
     * @return whether a resume point was removed, that is whether this packet closes a gap.
     */
    private fun takeResumePoint(state: State, sequenceNumber: Int): Boolean {
        var found = false
        while (true) {
            val point = state.resumePoints.peek() ?: break
            // Resume points and the packets that [rewrite] gets are close in time, so the comparison does not wrap.
            if (point != sequenceNumber && point isNewerThan sequenceNumber) {
                break
            }
            state.resumePoints.poll()
            found = true
        }
        return found
    }

    private fun getOrCreate(ssrc: Long): State {
        var state = states[ssrc]
        if (state == null) {
            if (states.size >= MAX_SSRCS) {
                states.entries.minByOrNull { it.value.lastUsed }?.let { states.remove(it.key, it.value) }
            }
            state = states.computeIfAbsent(ssrc) { State() }
        }
        state.lastUsed = System.nanoTime()
        return state
    }

    fun debugState(): ObjectNode = JsonNodeFactory.instance.objectNode().apply {
        put("ssrcs", states.size)
        put("gaps_closed", gapsClosed.get())
        put("late_packets_dropped", latePacketsDropped.get())
    }

    companion object {
        /** The maximum number of SSRCs to keep state for. */
        const val MAX_SSRCS = 1024

        /**
         * The maximum number of packets by which a packet can arrive late. A packet that is older than the resume
         * point by at most this number is dropped. A packet that is further away is forwarded.
         */
        private const val REORDER_WINDOW = 1000

        /**
         * The maximum number of late packets in a row to drop. After that, the resume point is cleared, and the
         * packets are forwarded.
         */
        private const val MAX_LATE_DROPS_IN_ROW = 10

        /** The maximum number of resume points that can wait for [rewrite]. */
        private const val MAX_RESUME_POINTS = 16
    }
}
