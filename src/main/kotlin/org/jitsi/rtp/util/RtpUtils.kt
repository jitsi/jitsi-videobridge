/*
 * Copyright @ 2018 - Present, 8x8 Inc
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
package org.jitsi.rtp.util

import org.jitsi.utils.TimeUtils

class RtpUtils {
    companion object {
        /**
         * A {@link Comparator} implementation for unsigned 16-bit {@link Integer}s.
         * Compares {@code a} and {@code b} inside the [0, 2^16] ring;
         * {@code a} is considered smaller than {@code b} if it takes a smaller
         * number to reach from {@code a} to {@code b} than the other way round.
         *
         * IMPORTANT: This is a valid {@link Comparator} implementation only when
         * used for subsets of [0, 2^16) which don't span more than 2^15 elements.
         *
         * E.g. it works for: [0, 2^15-1] and ([50000, 2^16) u [0, 10000])
         * Doesn't work for: [0, 2^15] and ([0, 2^15-1] u {2^16-1}) and [0, 2^16)
         */
        val rtpSeqNumComparator = Comparator<Int> { a, b ->
            when {
                a == b -> 0
                a > b -> {
                    if (a - b < 0x10000) {
                        1
                    } else {
                        -1
                    }
                }
                else -> { // a < b
                    if (b - a < 0x10000) {
                        -1
                    } else {
                        1
                    }
                }
            }
        }

        /**
         * [sizeBytes] MUST including padding (i.e. it should be 32-bit word aligned)
         */
        fun calculateRtcpLengthFieldValue(sizeBytes: Int): Int {
            if (sizeBytes % 4 != 0) {
                throw Exception("Invalid RTCP size value")
            }
            return (sizeBytes / 4) - 1
        }

        /**
         * Get the number of bytes needed to pad [dataSizeBytes] bytes to a 4-byte word boundary.
         */
        fun getNumPaddingBytes(dataSizeBytes: Int): Int =
            when (dataSizeBytes % 4) {
                0 -> 0
                1 -> 3
                2 -> 2
                3 -> 1
                else -> 0 // The above is exhaustive.
            }

        /**
         * Returns the delta between two RTP sequence numbers, taking into account
         * rollover.  This will return the 'shortest' delta between the two
         * sequence numbers in the form of the number you'd add to b to get a. e.g.:
         * getSequenceNumberDelta(1, 10) -> -9 (10 + -9 = 1)
         * getSequenceNumberDelta(1, 65530) -> 7 (65530 + 7 = 1)
         * @return the delta between two RTP sequence numbers (modulo 2^16).
         */
        @JvmStatic
        fun getSequenceNumberDelta(a: Int, b: Int): Int {
            val diff = a - b
            return when {
                diff < -(1 shl 15) -> diff + (1 shl 16)
                diff > (1 shl 15) -> diff - (1 shl 16)
                else -> diff
            }
        }

        @JvmStatic
        fun isNewerSequenceNumberThan(a: Int, b: Int): Boolean =
            getSequenceNumberDelta(a, b) > 0

        @JvmStatic
        fun isOlderSequenceNumberThan(a: Int, b: Int): Boolean =
            getSequenceNumberDelta(a, b) < 0

        @JvmStatic
        fun isNewerTimestampThan(a: Long, b: Long): Boolean =
            getTimestampDiff(a, b) > 0

        @JvmStatic
        fun isOlderTimestampThan(a: Long, b: Long): Boolean =
            getTimestampDiff(a, b) < 0

        /**
         * Returns the difference between two RTP timestamps.
         * @return the difference between two RTP timestamps.
         */
        @JvmStatic
        fun getTimestampDiff(a: Long, b: Long): Long {
            var diff = a - b
            if (diff < -0x8000_0000L) {
                diff += 0x1_0000_0000L
            } else if (diff > 0x8000_0000L) {
                diff -= 0x1_0000_0000L
            }

            return diff
        }

        /**
         * Returns a sequence of Ints from olderSeqNum (exclusive) to newerSeqNum (exclusive),
         * taking rollover into account
         */
        fun sequenceNumbersBetween(olderSeqNum: Int, newerSeqNum: Int): Sequence<Int> {
            var currSeqNum = olderSeqNum
            return generateSequence {
                currSeqNum = (currSeqNum + 1) % 0x1_0000
                if (currSeqNum == newerSeqNum) {
                    null
                } else {
                    currSeqNum
                }
            }
        }

        /**
         * Given [timestampMs] (a timestamp in milliseconds), convert it to an NTP timestamp represented
         * as a pair of ints: the first one being the most significant word and the second being the least
         * significant word.
         */
        @JvmStatic
        fun millisToNtpTimestamp(timestampMs: Long): Long = TimeUtils.toNtpTime(timestampMs)

        @JvmStatic
        fun convertRtpTimestampToMs(rtpTimestamp: Int, ticksPerSecond: Int): Long {
            return ((rtpTimestamp / (ticksPerSecond.toDouble())) * 1000).toLong()
        }
    }
}

fun Int.isPadding(): Boolean = this.toByte().isPadding()
fun Byte.isPadding(): Boolean = this == 0x00.toByte()

/**
 * Returns true if the RTP sequence number represented by [this] represents a more recent RTP packet than the one
 * represented by [otherSeqNum]
 */
infix fun Int.isNewerThan(otherSeqNum: Int): Boolean =
    RtpUtils.isNewerSequenceNumberThan(this, otherSeqNum)

infix fun Int.isOlderThan(otherSeqNum: Int): Boolean =
    RtpUtils.isOlderSequenceNumberThan(this, otherSeqNum)

infix fun Long.isNewerTimestampThan(otherTimestamp: Long): Boolean =
    RtpUtils.isNewerTimestampThan(this, otherTimestamp)

infix fun Long.isOlderTimestampThan(otherTimestamp: Long): Boolean =
    RtpUtils.isOlderTimestampThan(this, otherTimestamp)

/**
 * Returns true if getting to [otherSeqNum] from the current sequence number involves wrapping around
 */
infix fun Int.rolledOverTo(otherSeqNum: Int): Boolean =
    /**
     * If, according to [isOlderThan], [this] is older than [otherSeqNum] and
     * yet [otherSeqNum] is less than [this], then we wrapped around to get from [this] to
     * [otherSeqNum]
     */
    this isOlderThan otherSeqNum && otherSeqNum < this

/**
 * Returns true if [this] is sequentially after [otherSeqNum], according to the rules of RTP sequence
 * numbers
 */
infix fun Int.isNextAfter(otherSeqNum: Int): Boolean = RtpUtils.getSequenceNumberDelta(this, otherSeqNum) == 1

/**
 * Return the amount of packets between the RTP sequence number represented by [this] and the [otherSeqNum].  NOTE:
 * [this] must represent an older RTP sequence number than [otherSeqNum] (TODO: validate/enforce that)
 */
infix fun Int.numPacketsTo(otherSeqNum: Int): Int = -RtpUtils.getSequenceNumberDelta(this, otherSeqNum) - 1
