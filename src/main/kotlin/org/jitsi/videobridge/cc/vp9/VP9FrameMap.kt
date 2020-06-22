/*
 * Copyright @ 2019 8x8, Inc
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
package org.jitsi.videobridge.cc.vp9

import org.jetbrains.annotations.Contract
import org.jitsi.nlj.codec.vp8.Vp8Utils.Companion.getExtendedPictureIdDelta
import org.jitsi.nlj.rtp.codec.vp9.Vp9Packet
import org.jitsi.nlj.util.ArrayCache
import org.jitsi.rtp.util.RtpUtils
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger

/**
 * A history of recent frames on a VP9 stream.
 */
class VP9FrameMap(
    parentLogger: Logger
) {
    /** Cache mapping picture IDs to frames.  */
    private val frameHistory = FrameHistory(FRAME_MAP_SIZE)
    private val logger: Logger = createChildLogger(parentLogger)

    /** Find a frame in the frame map, based on a packet.  */
    @Synchronized
    fun findFrame(packet: Vp9Packet): VP9Frame? {
        return frameHistory[packet.pictureId]
    }

    /** Get the current size of the map.  */
    fun size(): Int {
        return frameHistory.numCached
    }

    /** Helper function to insert a packet into an existing frame.  */
    @Contract("_, _ -> new")
    private fun doFrameInsert(frame: VP9Frame, packet: Vp9Packet): FrameInsertionResult {
        try {
            frame.validateConsistent(packet)
        } catch (e: Exception) {
            logger.warn(e)
        }
        frame.addPacket(packet)
        return FrameInsertionResult(frame, false, false)
    }

    /** Check whether this is a large jump from previous state, so the map should be reset.  */
    private fun isLargeJump(packet: Vp9Packet): Boolean {
        val latestFrame: VP9Frame = frameHistory.latestFrame
            ?: return false
        val picDelta = getExtendedPictureIdDelta(packet.pictureId, latestFrame.pictureId)
        if (picDelta > FRAME_MAP_SIZE) {
            return true
        }
        val tsDelta: Long = RtpUtils.getTimestampDiff(packet.timestamp, latestFrame.timestamp)
        if (picDelta < 0) {
            /* if picDelta is negative but timestamp or sequence delta is positive, we've cycled. */
            if (tsDelta > 0) {
                return true
            }
            if (RtpUtils.getSequenceNumberDelta(packet.sequenceNumber, latestFrame.latestKnownSequenceNumber) > 0) {
                return true
            }
        }

        /* If tsDelta is more than twice the frame map size at 1 fps, we've cycled. */
        return tsDelta > FRAME_MAP_SIZE * 90000 * 2
    }

    /** Insert a packet into the frame map.  Return a FrameInsertionResult
     * describing what happened.
     * @param packet The packet to insert.
     * @return What happened.  null if insertion failed.
     */
    @Synchronized
    fun insertPacket(packet: Vp9Packet): FrameInsertionResult? {
        val pictureId = packet.pictureId
        if (pictureId == -1) {
            /* Frame map indexes by picture ID.  All supported browsers should currently be setting it. */
            /* Log message will have been logged by Vp9Parser in jmt. */
            return null
        }
        if (isLargeJump(packet)) {
            frameHistory.indexTracker.resetAt(pictureId)
            val frame = VP9Frame(packet)
            return if (!frameHistory.insert(pictureId, frame)) {
                null
            } else FrameInsertionResult(frame, true, true)
        }
        val frame = frameHistory[pictureId]
        if (frame != null) {
            if (!frame.matchesFrame(packet)) {
                check(frame.pictureId == pictureId) {
                    "Frame map returned frame with picture ID ${frame.pictureId} "
                        "when asked for frame with picture ID $pictureId"
                }
                logger.warn("Cannot insert packet in frame map: " +
                    with(frame) {
                        "frame with ssrc $ssrc, timestamp $timestamp, " +
                            "and sequence number range $earliestKnownSequenceNumber-$latestKnownSequenceNumber, "
                    } +
                    with(packet) {
                        "and packet $sequenceNumber with ssrc $ssrc, timestamp $timestamp, " +
                            "and sequence number $sequenceNumber"
                    } +
                    " both have picture ID $pictureId")
                return null
            }
            return doFrameInsert(frame, packet)
        }

        val newFrame = VP9Frame(packet)
        return if (!frameHistory.insert(pictureId, newFrame)) {
            null
        } else FrameInsertionResult(newFrame, true, false)
    }

    @Synchronized
    fun nextFrame(frame: VP9Frame): VP9Frame? {
        return frameHistory.findAfter(frame) { true }
    }

    @Synchronized
    fun nextFrameWith(frame: VP9Frame, pred: (VP9Frame) -> Boolean): VP9Frame? {
        return frameHistory.findAfter(frame, pred)
    }

    @Synchronized
    fun findNextTl0(frame: VP9Frame): VP9Frame? {
        return nextFrameWith(frame, VP9Frame::isTL0)
    }

    @Synchronized
    fun findNextAcceptedFrame(frame: VP9Frame): VP9Frame? {
        return nextFrameWith(frame, VP9Frame::isAccepted)
    }

    @Synchronized
    fun prevFrame(frame: VP9Frame): VP9Frame? {
        return frameHistory.findBefore(frame) { true }
    }

    @Synchronized
    fun prevFrameWith(frame: VP9Frame, pred: (VP9Frame) -> Boolean): VP9Frame? {
        return frameHistory.findBefore(frame, pred)
    }

    @Synchronized
    fun findPrevAcceptedFrame(frame: VP9Frame): VP9Frame? {
        return prevFrameWith(frame, VP9Frame::isAccepted)
    }

    /**
     * The result of calling [insertPacket]
     */
    class FrameInsertionResult(
        /** The frame corresponding to the packet that was inserted.  */
        val frame: VP9Frame,
        /** Whether inserting the frame created a new frame.  */
        val isNewFrame: Boolean,
        /** Whether inserting the frame caused a reset  */
        val isReset: Boolean
    )

    companion object {
        const val FRAME_MAP_SIZE = 500 /* Matches PacketCache default size. */
    }
}

internal class FrameHistory
constructor(size: Int) : ArrayCache<VP9Frame>(
    size,
    cloneItem = { k -> k },
    synchronize = false
) {
    var numCached = 0
    var firstIndex = -1
    var indexTracker = PictureIdIndexTracker()

    /**
     * Gets a frame with a given VP9 picture ID from the cache.
     */
    operator fun get(pictureId: Int): VP9Frame? {
        val index = indexTracker.interpret(pictureId)
        return getIndex(index)
    }

    /**
     * Gets a frame with a given VP9 picture ID index from the cache.
     */
    private fun getIndex(index: Int): VP9Frame? {
        if (index <= lastIndex - size) {
            /* We don't want to remember old frames even if they're still
               tracked; their neighboring frames may have been evicted,
               so findBefore / findAfter will return bogus data. */
            return null
        }
        val c = getContainer(index) ?: return null
        return c.item
    }

    /** Get the latest frame in the tracker.  */
    val latestFrame: VP9Frame?
        get() = getIndex(lastIndex)

    fun insert(pictureId: Int, frame: VP9Frame): Boolean {
        val index = indexTracker.update(pictureId)
        val ret = super.insertItem(frame, index)
        if (ret) {
            numCached++
            if (firstIndex == -1 || index < firstIndex) {
                firstIndex = index
            }
        }
        return ret
    }

    /**
     * Called when an item in the cache is replaced/discarded.
     */
    override fun discardItem(item: VP9Frame) {
        numCached--
    }

    fun findBefore(frame: VP9Frame, pred: (VP9Frame) -> Boolean): VP9Frame? {
        val lastIndex = lastIndex
        if (lastIndex == -1) {
            return null
        }
        val index = indexTracker.interpret(frame.pictureId)
        val searchStartIndex = Integer.min(index - 1, lastIndex)
        val searchEndIndex = Integer.max(lastIndex - size, firstIndex - 1)
        return doFind(pred, searchStartIndex, searchEndIndex, -1)
    }

    fun findAfter(frame: VP9Frame, pred: (VP9Frame) -> Boolean): VP9Frame? {
        val lastIndex = lastIndex
        if (lastIndex == -1) {
            return null
        }
        val index = indexTracker.interpret(frame.pictureId)
        if (index >= lastIndex) {
            return null
        }
        val searchStartIndex = Integer.max(index + 1, Integer.max(lastIndex - size + 1, firstIndex))
        return doFind(pred, searchStartIndex, lastIndex + 1, 1)
    }

    private fun doFind(pred: (VP9Frame) -> Boolean, startIndex: Int, endIndex: Int, increment: Int): VP9Frame? {
        var index = startIndex
        while (index != endIndex) {
            val frame = getIndex(index)
            if (frame != null && pred(frame)) {
                return frame
            }
            index += increment
        }
        return null
    }

    /** Like Rfc3711IndexTracker, but for picture IDs (so with a rollover
     * of 0x8000).
     */
    class PictureIdIndexTracker {
        private var roc = 0
        private var highestSeqNumReceived = -1
        private fun getIndex(seqNum: Int, updateRoc: Boolean): Int {
            if (highestSeqNumReceived == -1) {
                if (updateRoc) {
                    highestSeqNumReceived = seqNum
                }
                return seqNum
            }
            val delta = getExtendedPictureIdDelta(seqNum, highestSeqNumReceived)
            val v: Int
            if (delta < 0 && highestSeqNumReceived < seqNum) {
                v = roc - 1
            } else if (delta > 0 && seqNum < highestSeqNumReceived) {
                v = roc + 1
                if (updateRoc) roc = v
            } else {
                v = roc
            }
            if (updateRoc && delta > 0) {
                highestSeqNumReceived = seqNum
            }
            return 0x8000 * v + seqNum
        }

        fun update(seq: Int): Int {
            return getIndex(seq, true)
        }

        fun interpret(seq: Int): Int {
            return getIndex(seq, false)
        }

        /** Force this sequence to be interpreted as the new highest, regardless
         * of its rollover state.
         */
        fun resetAt(seq: Int) {
            val delta = getExtendedPictureIdDelta(seq, highestSeqNumReceived)
            if (delta < 0) {
                roc++
                highestSeqNumReceived = seq
            }
            getIndex(seq, true)
        }
    }
}
