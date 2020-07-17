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

import org.jitsi.nlj.codec.vp8.Vp8Utils.Companion.getExtendedPictureIdDelta
import org.jitsi.nlj.rtp.codec.vp9.Vp9Packet
import org.jitsi.nlj.util.ArrayCache
import org.jitsi.rtp.util.RtpUtils
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger

/**
 * A history of recent pictures on a VP9 stream.
 */
class Vp9PictureMap(
    parentLogger: Logger
) {
    /** Cache mapping picture IDs to pictures.  */
    private val pictureHistory = PictureHistory(PICTURE_MAP_SIZE)
    private val logger: Logger = createChildLogger(parentLogger)

    /** Find a picture in the picture map, based on a packet.  */
    @Synchronized
    fun findPicture(packet: Vp9Packet): Vp9Picture? {
        return pictureHistory[packet.pictureId]
    }

    /** Get the current size of the map.  */
    fun size(): Int {
        return pictureHistory.numCached
    }

    /** Check whether this is a large jump from previous state, so the map should be reset.  */
    private fun isLargeJump(packet: Vp9Packet): Boolean {
        val latestPicture: Vp9Picture = pictureHistory.latestPicture
            ?: return false
        val picDelta = getExtendedPictureIdDelta(packet.pictureId, latestPicture.pictureId)
        if (picDelta > PICTURE_MAP_SIZE) {
            return true
        }
        val tsDelta: Long = RtpUtils.getTimestampDiff(packet.timestamp, latestPicture.timestamp)
        if (picDelta < 0) {
            /* if picDelta is negative but timestamp or sequence delta is positive, we've cycled. */
            if (tsDelta > 0) {
                return true
            }
            if (RtpUtils.getSequenceNumberDelta(packet.sequenceNumber, latestPicture.latestKnownSequenceNumber) > 0) {
                return true
            }
        }

        /* If tsDelta is more than twice the picture map size at 1 fps, we've cycled. */
        return tsDelta > PICTURE_MAP_SIZE * 90000 * 2
    }

    /** Insert a packet into the picture map.  Return a PictureInsertionResult
     * describing what happened.
     * @param packet The packet to insert.
     * @return What happened.  null if insertion failed.
     */
    @Synchronized
    fun insertPacket(packet: Vp9Packet): PacketInsertionResult? {
        val pictureId = packet.pictureId
        if (pictureId == -1) {
            /* Picture map indexes by picture ID.  All supported browsers should currently be setting it. */
            /* Log message will have been logged by Vp9Parser in jmt. */
            return null
        }
        if (isLargeJump(packet)) {
            pictureHistory.indexTracker.resetAt(pictureId)
            val picture = Vp9Picture(packet)
            return if (!pictureHistory.insert(pictureId, picture)) {
                null
            } else {
                PacketInsertionResult(picture.frame(packet.spatialLayerIndex)!!, picture, true, isReset = true)
            }
        }
        val picture = pictureHistory[pictureId]
        if (picture != null) {
            if (!picture.matchesPicture(packet)) {
                check(picture.pictureId == pictureId) {
                    "Picture map returned picture with picture ID ${picture.pictureId} "
                        "when asked for picture with picture ID $pictureId"
                }
                logger.warn("Cannot insert packet in picture map: " +
                    with(picture) {
                        "picture with ssrc $ssrc, timestamp $timestamp, " +
                            "and sequence number range $earliestKnownSequenceNumber-$latestKnownSequenceNumber, "
                    } +
                    with(packet) {
                        "and packet $sequenceNumber with ssrc $ssrc, timestamp $timestamp, " +
                            "and sequence number $sequenceNumber"
                    } +
                    " both have picture ID $pictureId")
                return null
            }
            try {
                picture.validateConsistent(packet)
            } catch (e: Exception) {
                logger.warn(e)
            }

            return picture.addPacket(packet)
        }

        val newPicture = Vp9Picture(packet)
        return if (!pictureHistory.insert(pictureId, newPicture)) {
            null
        } else PacketInsertionResult(newPicture.frame(packet.spatialLayerIndex)!!, newPicture, true)
    }

    @Synchronized
    fun nextPicture(picture: Vp9Picture): Vp9Picture? {
        return pictureHistory.findAfter(picture) { true }
    }

    @Synchronized
    fun nextPictureWith(picture: Vp9Picture, pred: (Vp9Picture) -> Boolean): Vp9Picture? {
        return pictureHistory.findAfter(picture, pred)
    }

    @Synchronized
    fun findNextTl0(picture: Vp9Picture): Vp9Picture? {
        return nextPictureWith(picture, Vp9Picture::isTL0)
    }

    @Synchronized
    fun prevPicture(picture: Vp9Picture): Vp9Picture? {
        return pictureHistory.findBefore(picture) { true }
    }

    @Synchronized
    fun prevPictureWith(picture: Vp9Picture, pred: (Vp9Picture) -> Boolean): Vp9Picture? {
        return pictureHistory.findBefore(picture, pred)
    }

    companion object {
        const val PICTURE_MAP_SIZE = 500 /* Matches PacketCache default size. */
    }
}

internal class PictureHistory
constructor(size: Int) : ArrayCache<Vp9Picture>(
    size,
    cloneItem = { k -> k },
    synchronize = false
) {
    var numCached = 0
    var firstIndex = -1
    var indexTracker = PictureIdIndexTracker()

    /**
     * Gets a picture with a given VP9 picture ID from the cache.
     */
    operator fun get(pictureId: Int): Vp9Picture? {
        val index = indexTracker.interpret(pictureId)
        return getIndex(index)
    }

    /**
     * Gets a picture with a given VP9 picture ID index from the cache.
     */
    private fun getIndex(index: Int): Vp9Picture? {
        if (index <= lastIndex - size) {
            /* We don't want to remember old pictures even if they're still
               tracked; their neighboring pictures may have been evicted,
               so findBefore / findAfter will return bogus data. */
            return null
        }
        val c = getContainer(index) ?: return null
        return c.item
    }

    /** Get the latest picture in the tracker.  */
    val latestPicture: Vp9Picture?
        get() = getIndex(lastIndex)

    fun insert(pictureId: Int, picture: Vp9Picture): Boolean {
        val index = indexTracker.update(pictureId)
        val ret = super.insertItem(picture, index)
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
    override fun discardItem(item: Vp9Picture) {
        numCached--
    }

    fun findBefore(picture: Vp9Picture, pred: (Vp9Picture) -> Boolean): Vp9Picture? {
        val lastIndex = lastIndex
        if (lastIndex == -1) {
            return null
        }
        val index = indexTracker.interpret(picture.pictureId)
        val searchStartIndex = Integer.min(index - 1, lastIndex)
        val searchEndIndex = Integer.max(lastIndex - size, firstIndex - 1)
        return doFind(pred, searchStartIndex, searchEndIndex, -1)
    }

    fun findAfter(picture: Vp9Picture, pred: (Vp9Picture) -> Boolean): Vp9Picture? {
        val lastIndex = lastIndex
        if (lastIndex == -1) {
            return null
        }
        val index = indexTracker.interpret(picture.pictureId)
        if (index >= lastIndex) {
            return null
        }
        val searchStartIndex = Integer.max(index + 1, Integer.max(lastIndex - size + 1, firstIndex))
        return doFind(pred, searchStartIndex, lastIndex + 1, 1)
    }

    private fun doFind(pred: (Vp9Picture) -> Boolean, startIndex: Int, endIndex: Int, increment: Int): Vp9Picture? {
        var index = startIndex
        while (index != endIndex) {
            val picture = getIndex(index)
            if (picture != null && pred(picture)) {
                return picture
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
