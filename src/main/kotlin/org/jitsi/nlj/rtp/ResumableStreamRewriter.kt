/*
 * Copyright @ 2019 - present 8x8, Inc.
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
package org.jitsi.nlj.rtp

import org.jitsi.nlj.util.ArrayCache
import org.jitsi.nlj.util.Rfc3711IndexTracker
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.rtp.util.isNewerThan

/**
 * Rewrites sequence numbers for RTP streams by hiding any gaps caused by
 * dropped packets. Rewriters are not thread-safe. If multiple threads access a
 * rewriter concurrently, it must be synchronized externally.
 *
 * Port of the class in libjitsi.
 *
 * @author Maryam Daneshi
 * @author George Politis
 * @author Boris Grozev
 * @author Jonathan Lennox
 */
class ResumableStreamRewriter(val keepHistory: Boolean = false) {
    /**
     * The sequence number delta between what's been accepted and what's been
     * received, mod 2^16.
     */
    var seqnumDelta = 0
        private set

    private var history: StreamRewriteHistory? = null

    /**
     * The highest sequence number that got accepted, mod 2^16.
     */
    var highestSequenceNumberSent = -1
        private set

    /**
     * Rewrites the sequence number of the given RTP packet hiding any gaps caused by drops.
     *
     * @param accept true if the packet is accepted, false otherwise
     */
    fun rewriteRtp(accept: Boolean, rtpPacket: RtpPacket) {
        val sequenceNumber = rtpPacket.sequenceNumber
        val newSequenceNumber = rewriteSequenceNumber(accept, sequenceNumber)
        if (sequenceNumber != newSequenceNumber) {
            rtpPacket.sequenceNumber = newSequenceNumber
        }
    }

    /**
     * Rewrites the sequence number passed as a parameter, hiding any gaps
     * caused by drops.
     *
     * @param accept true if the packet is accepted, false otherwise
     * @param sequenceNumber the sequence number to rewrite
     * @return a rewritten sequence number that hides any gaps caused by drops.
     */
    fun rewriteSequenceNumber(accept: Boolean, sequenceNumber: Int): Int {
        if (keepHistory && !accept && history == null) {
            /* Don't instantiate history until it's needed; many streams never discard. */
            history = StreamRewriteHistory(highestSequenceNumberSent)
        }

        history?.let {
            return it.rewriteSequenceNumber(accept, sequenceNumber)
        }

        if (accept) {
            // overwrite the sequence number (if needed)
            val newSequenceNumber = (sequenceNumber - seqnumDelta) and 0xffff

            // init or update the highest sent sequence number (if needed)
            if (highestSequenceNumberSent == -1 || newSequenceNumber isNewerThan highestSequenceNumberSent) {
                highestSequenceNumberSent = newSequenceNumber
            }

            return newSequenceNumber
        } else {
            // update the sequence number delta (if needed)
            if (highestSequenceNumberSent != -1) {
                val newDelta = (sequenceNumber - highestSequenceNumberSent) and 0xffff

                if (newDelta isNewerThan seqnumDelta) {
                    seqnumDelta = newDelta
                }
            }

            return sequenceNumber
        }
    }

    val gapsLeft: Int
        get() = history?.gapsLeft ?: 0

    private class RewriteHistoryItem(
        var accept: Boolean?,
        val newIndex: Int
    )

    private class StreamRewriteHistory(highestSeqSent: Int) : ArrayCache<RewriteHistoryItem>(
        MAX_REWRITE_HISTORY,
        /* We don't want to clone objects that get put in the tracker. */
        { it },
        /* Caller should have this object synchronized if needed. */
        synchronize = false
    ) {
        var firstIndex: Int = -1

        var gapsLeft = 0
            private set

        private val rfc3711IndexTracker = Rfc3711IndexTracker()

        private fun fillBetween(start: Int, end: Int, firstNewIndex: Int) {
            if (end <= lastIndex - size + 1)
                return
            val actualStart = if (start <= lastIndex - size) {
                lastIndex - size + 1
            } else {
                start
            }

            var newIndex = firstNewIndex

            for (i in actualStart..end) {
                insertItem(RewriteHistoryItem(null, newIndex), i)
                newIndex++
            }
        }

        fun rewriteSequenceNumber(accept: Boolean, sequenceNumber: Int): Int {
            val index = rfc3711IndexTracker.update(sequenceNumber)

            val newIndex: Int

            when {
                (firstIndex == -1) -> {
                    /* First index seen. */
                    insertItem(RewriteHistoryItem(accept, index), index)

                    firstIndex = index

                    newIndex = index
                }

                (index > lastIndex) -> {
                    /* New.  Roll forward, filling in gap if necessary. */

                    val newestIndex = lastIndex

                    val newest = getContainer(newestIndex)
                        ?: throw IllegalStateException("No newest container found")

                    val newestNewIndex = newest.item!!.newIndex

                    val indexGap = index - newestIndex

                    val newGap = indexGap - if (accept) 0 else 1

                    newIndex = newestNewIndex + newGap

                    insertItem(RewriteHistoryItem(accept, newIndex), index)

                    fillBetween(newestIndex + 1, index - 1, newestNewIndex + 1)
                }

                (index > lastIndex - size && index >= firstIndex) -> {
                    /* In history.  Retrieve. */

                    val container = getContainer(index)
                        ?: throw IllegalStateException("No container found for index $index in history")
                    val item = container.item!!

                    if (item.accept == null) {
                        item.accept = accept
                        if (!accept)
                            gapsLeft++
                    }

                    newIndex = item.newIndex
                }

                (index > lastIndex - size && index < firstIndex) -> {
                    /* Older than previous oldest, but still in range of the map.
                       Project map backwards. */

                    val oldestIndex = Math.max(index - size, firstIndex)
                    val oldest = getContainer(oldestIndex)
                        ?: throw IllegalStateException("No oldest container found")

                    val oldestNewIndex = oldest.item!!.newIndex

                    val indexGap = index - oldestIndex /* Negative */

                    val newGap = indexGap + if (accept) 0 else 1

                    newIndex = oldestNewIndex + newGap

                    insertItem(RewriteHistoryItem(accept, newIndex), index)

                    fillBetween(index + 1, oldestIndex - 1, newIndex + 1)

                    firstIndex = index
                }

                else -> {
                    /* Older than the map. */

                    val oldestIndex = Math.max(index - size, firstIndex)
                    val oldest = getContainer(oldestIndex)
                        ?: throw IllegalStateException("No oldest container found")
                    val oldestDelta = oldestIndex - oldest.item!!.newIndex

                    newIndex = index - oldestDelta
                }
            }

            if (accept) return toSequenceNumber(newIndex)
            return sequenceNumber /* Don't care about sequence numbers for non-accepted packets,
              so make sure rewriteRtp does nothing. */
        }

        init {
            if (highestSeqSent != -1) {
                rewriteSequenceNumber(true, highestSeqSent)
            }
        }

        companion object {
            /**
             * The maximum number of packets to save history for.
             *
             * NOTE rtt + minimum amount
             * XXX this is an uninformed value.
             */
            private const val MAX_REWRITE_HISTORY = 1000

            /** Map an index back to a sequence number. */
            private fun toSequenceNumber(index: Int): Int = index and 0xffff
        }
    }
}
