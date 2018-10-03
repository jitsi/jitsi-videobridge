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
package org.jitsi.nlj.util

import org.jitsi.rtp.util.RtpUtils
import org.jitsi.util.RTPUtils


/**
 * Returns true if getting to [otherSeqNum] from the current sequence number involves wrapping around
 */
infix fun Int.rolledOverTo(otherSeqNum: Int): Boolean {
    /**
     * If, according to [RTPUtils.isOlderSequenceNumberThan], [this] is older than [otherSeqNum] and
     * yet [otherSeqNum] is less than [this], then we wrapped around to get from [this] to
     * [otherSeqNum]
     */
    return RTPUtils.isOlderSequenceNumberThan(this, otherSeqNum) && otherSeqNum < this
}

/**
 * Returns true if [this] is sequentially after [otherSeqNum], according to the rules of RTP sequence
 * numbers
 */
infix fun Int.isNextAfter(otherSeqNum: Int): Boolean = RTPUtils.getSequenceNumberDelta(this, otherSeqNum) == -1

/**
 * Returns true if the RTP sequence number represented by [this] represents a more recent RTP packet than the one
 * represented by [otherSeqNum]
 */
infix fun Int.isNewerThan(otherSeqNum: Int): Boolean = RTPUtils.isOlderSequenceNumberThan(otherSeqNum, this)

/**
 * Return the amount of packets between the RTP sequence number represented by [this] and the [otherSeqNum].  NOTE:
 * [this] must represent an older RTP sequence number than [otherSeqNum] (TODO: validate/enforce that)
 */
infix fun Int.numPacketsTo(otherSeqNum: Int): Int = -RTPUtils.getSequenceNumberDelta(this, otherSeqNum) - 1
