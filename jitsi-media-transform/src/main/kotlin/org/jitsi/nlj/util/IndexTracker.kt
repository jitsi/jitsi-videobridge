/*
 * Copyright @ 2018 - present 8x8, Inc.
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

package org.jitsi.nlj.util

/**
 * Index tracker inspired by the RFC3711 RTP sequence number index tracker.
 */

/** A tracker suitable for the VP8/VP9 15-bit PictureID field. */
class PictureIdIndexTracker : IntIndexTracker(15)

/** A tracker suitable for RTP 16-bit sequence numbers. */
class RtpSequenceIndexTracker : IntIndexTracker(16)

/** A tracker suitable for RTP 32-bit timestamps. */
class RtpTimestampIndexTracker : LongIndexTracker(32)

open class IntIndexTracker(val bits: Int) : IndexTracker<Int>() {
    init {
        require(bits in 1..31) { "bits must be between 1 and 31" }
    }
    override fun addRollover(seqNum: Int, roc: Long) = (1 shl bits) * roc + seqNum
    override fun rollsOver(a: Int, b: Int): Boolean = isOlderThan(a, b) && b < a
    override fun isOlderThan(a: Int, b: Int): Boolean = delta(a, b) < 0
    override fun toLong(t: Int): Long = t.toLong()
    override fun isValid(t: Int): Boolean = t >= 0 && t < (1 shl bits)

    private fun delta(a: Int, b: Int): Int {
        val diff = a - b
        return when {
            diff < -(1 shl (bits - 1)) -> diff + (1 shl bits)
            diff > (1 shl (bits - 1)) -> diff - (1 shl bits)
            else -> diff
        }
    }
}

open class LongIndexTracker(val bits: Int) : IndexTracker<Long>() {
    init {
        require(bits in 1..63) { "bits must be between 1 and 63" }
    }
    override fun addRollover(seqNum: Long, roc: Long) = (1L shl bits) * roc + seqNum
    override fun rollsOver(a: Long, b: Long): Boolean = isOlderThan(a, b) && b < a
    override fun isOlderThan(a: Long, b: Long): Boolean = delta(a, b) < 0
    override fun toLong(t: Long): Long = t
    override fun isValid(t: Long): Boolean = t >= 0L && t < (1L shl bits)

    private fun delta(a: Long, b: Long): Long {
        val diff = a - b
        return when {
            diff < -(1L shl (bits - 1)) -> diff + (1L shl bits)
            diff > (1L shl (bits - 1)) -> diff - (1L shl bits)
            else -> diff
        }
    }
}

sealed class IndexTracker<T> {
    private var roc: Long = 0L

    private var highestSeqNumReceived: T? = null

    internal abstract fun addRollover(seqNum: T, roc: Long): Long
    internal abstract fun rollsOver(a: T, b: T): Boolean
    internal abstract fun isOlderThan(a: T, b: T): Boolean
    internal abstract fun toLong(t: T): Long
    internal abstract fun isValid(t: T): Boolean
    private fun isNewerThan(a: T, b: T): Boolean = !isOlderThan(a, b) && a != b
    private fun validate(t: T): T = if (!isValid(t)) {
        throw IllegalArgumentException("Invalid sequence number: $t")
    } else {
        t
    }

    /**
     * return the index (as defined by RFC3711 at https://tools.ietf.org/html/rfc3711#section-3.3.1)
     * for the given seqNum, updating our ROC if we roll over.
     * NOTE that this method must be called for all 'received' sequence numbers so that it may keep
     * its rollover counter accurate
     */
    fun update(seqNum: T): Long = getIndex(validate(seqNum), true)

    /**
     * Interprets an RTP sequence number in the context of the highest sequence number received. Returns the index
     * which corresponds to the packet, but does not update the ROC.
     */
    fun interpret(seqNum: T): Long = getIndex(validate(seqNum), false)

    /**
     * return the index (as defined by RFC3711 at https://tools.ietf.org/html/rfc3711#section-3.3.1)
     * for the given [seqNum]. If [updateRoc] is [true] and we've rolled over, updates our ROC.
     */
    private fun getIndex(seqNum: T, updateRoc: Boolean): Long {
        val highestSeqNumReceived = this.highestSeqNumReceived
        if (highestSeqNumReceived == null) {
            if (updateRoc) {
                this.highestSeqNumReceived = seqNum
            }
            return toLong(seqNum)
        }

        val v = when {
            rollsOver(seqNum, highestSeqNumReceived) -> {
                // Seq num was from the previous roc value
                roc - 1
            }
            rollsOver(highestSeqNumReceived, seqNum) -> {
                // We've rolled over, so update the roc in place if updateRoc
                // is set, otherwise return the right value (our current roc
                // + 1)
                if (updateRoc) {
                    ++roc
                } else {
                    roc + 1
                }
            }
            else -> roc
        }

        if (updateRoc && isNewerThan(seqNum, highestSeqNumReceived)) {
            this.highestSeqNumReceived = seqNum
        }

        return addRollover(seqNum, v)
    }

    /** Force this sequence number to be interpreted as the new highest, regardless of its rollover state. */
    fun resetAt(seq: T) {
        validate(seq)
        val highestSeqNumReceived = this.highestSeqNumReceived
        if (highestSeqNumReceived == null || isOlderThan(seq, highestSeqNumReceived)) {
            roc++
            this.highestSeqNumReceived = seq
        }
        getIndex(seq, true)
    }

    fun debugState(): String {
        return "{roc=$roc, highestSeqNumReceived=$highestSeqNumReceived}"
    }
}
