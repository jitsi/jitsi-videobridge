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

package org.jitsi.rtp.rtp

import org.jitsi.rtp.util.RtpUtils

/**
 * An inline class representing an RTP sequence number.  The class operates just like
 * an Int but takes rollover into account for all operations.
 *
 * This constructor assumes that the value is already coerced. It MUST NOT be used outside this class, but it can not
 * be marked private. Use [Int.toRtpSequenceNumber] to create instances.
 */
@Suppress("NON_PUBLIC_PRIMARY_CONSTRUCTOR_OF_INLINE_CLASS")
inline class RtpSequenceNumber internal constructor(val value: Int) : Comparable<RtpSequenceNumber> {
    // These are intentionally not implemented, because using them as operators leads to inconsistent results. The
    // following code:
    // var n1 = RtpSequenceNumber(65535)
    // var n2 = RtpSequenceNumber(65535)
    // ++n1
    // n2++
    // System.err.println("n1=$n1, n2=$n2")
    //
    // Produces this result:
    // n1=RtpSequenceNumber(value=65536) n2=RtpSequenceNumber(value=0)
    //
    // Using "+= 1" yields the expected result. Note that if the same code above is in a "should" block, the result is
    // different (as expected). So if/when this is brought back, it should be tested outside a regular "should" block!
    //
    // operator fun inc(): RtpSequenceNumber = plus(1)
    // operator fun dec(): RtpSequenceNumber = minus(1)

    operator fun plus(num: Int): RtpSequenceNumber = (value + num).toRtpSequenceNumber()
    operator fun plus(seqNum: RtpSequenceNumber): RtpSequenceNumber = (value + seqNum.value).toRtpSequenceNumber()

    operator fun minus(num: Int): RtpSequenceNumber = plus(-num)
    operator fun minus(seqNum: RtpSequenceNumber): RtpSequenceNumber = plus(-seqNum.value)

    override operator fun compareTo(other: RtpSequenceNumber): Int =
        RtpUtils.getSequenceNumberDelta(value, other.value)

    operator fun rangeTo(other: RtpSequenceNumber) = RtpSequenceNumberProgression(this, other)

    companion object {
        val INVALID = RtpSequenceNumber(-1)
    }
}

fun Int.toRtpSequenceNumber() = RtpSequenceNumber(this and 0xffff)

// Copied mostly from IntProgression.
// NOTE(brian): technically this should probably inherit from ClosedRange, but
// the inheritance causes issues with boxing and the inline types.  See
// https://youtrack.jetbrains.com/issue/KT-30716 and the bug linked there.
class RtpSequenceNumberProgression(
    val start: RtpSequenceNumber,
    val endInclusive: RtpSequenceNumber,
    val step: Int = 1
) : Iterable<RtpSequenceNumber> /*, ClosedRange<RtpSequenceNumber> */ {

    override fun iterator(): Iterator<RtpSequenceNumber> =
        RtpSequenceNumberProgressionIterator(start, endInclusive, step)

    companion object {
        fun fromClosedRange(rangeStart: RtpSequenceNumber, rangeEnd: RtpSequenceNumber, step: Int): RtpSequenceNumberProgression =
            RtpSequenceNumberProgression(rangeStart, rangeEnd, step)
    }
}

// Copied mostly from IntProgressionIterator
class RtpSequenceNumberProgressionIterator(
    first: RtpSequenceNumber,
    last: RtpSequenceNumber,
    val step: Int
) : Iterator<RtpSequenceNumber> {
    private val finalElement = last
    private var hasNext: Boolean = if (step > 0) first <= last else first >= last
    private var next = if (hasNext) first else finalElement

    override fun hasNext(): Boolean = hasNext

    override fun next(): RtpSequenceNumber = nextSeqNum()

    fun nextSeqNum(): RtpSequenceNumber {
        val value = next
        if (value == finalElement) {
            if (!hasNext) throw kotlin.NoSuchElementException()
            hasNext = false
        } else {
            next += step
        }
        return value
    }
}

infix fun RtpSequenceNumber.downTo(to: RtpSequenceNumber): RtpSequenceNumberProgression =
    RtpSequenceNumberProgression.fromClosedRange(this, to, -1)
