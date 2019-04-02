/*
 * Copyright @ 2018 Atlassian Pty Ltd
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

//TODO: this and RTPUtils should be merged
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
                else -> { //a < b
                    if (b - a < 0x10000) {
                        -1
                    } else {
                        1
                    }
                }
            }
        }
        fun getPaddedLengthValue(unpaddedSizeBytes: Int): Int {
            return calculateRtcpLengthFieldValue(unpaddedSizeBytes + getNumPaddingBytes(unpaddedSizeBytes))
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

        fun getNumPaddingBytes(dataSizeBytes: Int): Int {
            var paddingBytes = 0
            while ((dataSizeBytes + paddingBytes) % 4 != 0) {
                paddingBytes++
            }
            return paddingBytes
        }
        /**
         * Returns the delta between two RTP sequence numbers, taking into account
         * rollover.  This will return the 'shortest' delta between the two
         * sequence numbers in the form of the number you'd add to b to get a. e.g.:
         * getSequenceNumberDelta(1, 10) -> -9 (10 + -9 = 1)
         * getSequenceNumberDelta(1, 65530) -> 7 (65530 + 7 = 1)
         * @return the delta between two RTP sequence numbers (modulo 2^16).
         */
        fun getSequenceNumberDelta(a: Int, b: Int): Int
        {
            val diff = a - b;
            return when {
                diff < -(1 shl 15) -> diff + (1 shl 16)
                diff > (1 shl 15) -> diff - (1 shl 16)
                else -> diff
            }
        }
    }
}

/**
 * Returns true if the RTP sequence number represented by [this] represents a more recent RTP packet than the one
 * represented by [otherSeqNum]
 */
infix fun Int.isNewerThan(otherSeqNum: Int): Boolean =
    RtpUtils.getSequenceNumberDelta(this, otherSeqNum) > 0

infix fun Int.isOlderThan(otherSeqNum: Int): Boolean =
    RtpUtils.getSequenceNumberDelta(this, otherSeqNum) < 0
