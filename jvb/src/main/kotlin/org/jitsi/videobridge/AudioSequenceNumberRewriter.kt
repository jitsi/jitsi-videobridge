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

import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.rtp.util.RtpUtils
import org.jitsi.rtp.util.isNewerThan
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps the RTP sequence numbers of the audio streams sent to one receiver contiguous when the bridge does not forward
 * some packets to that receiver (for example, when the receiver's audio subscription excludes a source for some time).
 *
 * Without this, the receiver sees a jump in the sequence numbers. After a jump of more than 2^15, the receiver's SRTP
 * index estimation (RFC 3711, section 3.3.1) treats the next packets as old. The receiver can then drop them for up to
 * 2^16 packets.
 *
 * Gaps from packets that were lost before they reached the bridge are kept, except for a loss next to the dropped
 * packets. Timestamps are not changed, so that the receiver keeps the real timing (and the sender reports stay valid).
 *
 * The state of an SSRC is kept for the lifetime of the receiver, also when its source is removed. A sender that is
 * re-created with the same SSRC continues its sequence numbers, and the receiver must continue to get the same
 * offset. The state is small, and the number of audio SSRCs that one receiver sees is limited.
 *
 * [dropped] can be called from any thread. [rewrite] must not be called concurrently (the send queue of the receiver
 * calls it serially).
 */
class AudioSequenceNumberRewriter {
    private class State(
        /** The highest sequence number (after rewriting) of the packets passed to [rewrite]. */
        var lastSequenceNumber: Int
    ) {
        /** The delta to add to the original sequence numbers. */
        var delta = 0

        /**
         * The original sequence number of the packet that closed the last gap, or -1. A later packet that is older
         * than this one would get a sequence number that was already used, so it is dropped.
         */
        var resumeSequenceNumber = -1

        /**
         * The original sequence number of the last packet passed to [dropped] since the last gap was closed, or -1.
         * The next packet that is newer than this one closes the gap. A packet that is not newer was accepted before
         * the drop (and waited in the send queue), so it does not close the gap.
         */
        val lastDropped = AtomicInteger(-1)
    }

    private val states = ConcurrentHashMap<Long, State>()

    /**
     * Notes that the packet from [ssrc] with original sequence number [sequenceNumber] will not be forwarded to the
     * receiver.
     */
    fun dropped(ssrc: Long, sequenceNumber: Int) {
        states[ssrc]?.lastDropped?.set(sequenceNumber)
    }

    /**
     * Changes the sequence number of [packet] (in place), so that the first packet after dropped packets follows the
     * last packet passed to [rewrite]. The new delta then applies to all later packets of the SSRC. The first packet
     * of an SSRC is not changed.
     *
     * @return false if the packet must be dropped, because it arrived late and its sequence number would be one that
     * was already used.
     */
    fun rewrite(packet: RtpPacket): Boolean {
        val sequenceNumber = packet.sequenceNumber
        val state = states[packet.ssrc]
        if (state == null) {
            states[packet.ssrc] = State(sequenceNumber)
            return true
        }

        val lastDropped = state.lastDropped.get()
        if (lastDropped != -1 && sequenceNumber isNewerThan lastDropped) {
            // If [dropped] was called again in the meantime, keep that value, so that the next packet closes the gap.
            state.lastDropped.compareAndSet(lastDropped, -1)
            state.delta = RtpUtils.getSequenceNumberDelta(
                RtpUtils.applySequenceNumberDelta(state.lastSequenceNumber, 1),
                sequenceNumber
            )
            state.resumeSequenceNumber = sequenceNumber
        } else if (state.resumeSequenceNumber != -1) {
            val sinceResume = RtpUtils.getSequenceNumberDelta(sequenceNumber, state.resumeSequenceNumber)
            if (sinceResume in -REORDER_WINDOW until 0) {
                return false
            }
            if (sinceResume !in 0..REORDER_WINDOW) {
                // Too far from the resume point to be a late packet.
                state.resumeSequenceNumber = -1
            }
        }

        val newSequenceNumber = RtpUtils.applySequenceNumberDelta(sequenceNumber, state.delta)
        packet.sequenceNumber = newSequenceNumber
        if (newSequenceNumber isNewerThan state.lastSequenceNumber) {
            state.lastSequenceNumber = newSequenceNumber
        }
        return true
    }

    companion object {
        /**
         * The maximum number of packets by which a packet can arrive late. A packet that is older than the resume
         * point by at most this number is dropped.
         */
        private const val REORDER_WINDOW = 1000
    }
}
