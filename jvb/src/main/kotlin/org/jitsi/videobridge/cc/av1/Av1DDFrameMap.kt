package org.jitsi.videobridge.cc.av1

import org.jitsi.nlj.rtp.codec.av1.Av1DDPacket
import org.jitsi.nlj.util.ArrayCache
import org.jitsi.nlj.util.RtpSequenceIndexTracker
import org.jitsi.rtp.util.RtpUtils
import org.jitsi.rtp.util.isNewerThan
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import kotlin.math.max
import kotlin.math.min

/**
 * A history of recent frames on a Av1 stream.
 */
class Av1DDFrameMap(
    parentLogger: Logger
) {
    /** Cache mapping frame IDs to frames.  */
    private val frameHistory = FrameHistory(FRAME_MAP_SIZE)
    private val logger: Logger = createChildLogger(parentLogger)

    /** Find a frame in the frame map, based on a packet.  */
    @Synchronized
    fun findFrame(packet: Av1DDPacket): Av1DDFrame? {
        return frameHistory[packet.frameNumber]
    }

    /** Get the current size of the map.  */
    fun size(): Int {
        return frameHistory.numCached
    }

    /** Check whether this is a large jump from previous state, so the map should be reset.  */
    private fun isLargeJump(packet: Av1DDPacket): Boolean {
        val latestFrame: Av1DDFrame = frameHistory.latestFrame ?: return false
        val picDelta = RtpUtils.getSequenceNumberDelta(packet.frameNumber, latestFrame.frameNumber)
        if (picDelta > FRAME_MAP_SIZE) {
            return true
        }
        val tsDelta: Long = RtpUtils.getTimestampDiff(packet.timestamp, latestFrame.timestamp)
        if (picDelta < 0) {
            /* if picDelta is negative but timestamp or sequence delta is positive, we've cycled. */
            if (tsDelta > 0) {
                return true
            }
            if (packet.sequenceNumber isNewerThan latestFrame.latestKnownSequenceNumber) {
                return true
            }
        }

        /* If tsDelta is more than twice the frame map size at 1 fps, we've cycled. */
        return tsDelta > FRAME_MAP_SIZE * 90000 * 2
    }

    /** Insert a packet into the frame map.  Return a frameInsertionResult
     * describing what happened.
     * @param packet The packet to insert.
     * @return What happened.  null if insertion failed.
     */
    @Synchronized
    fun insertPacket(packet: Av1DDPacket): PacketInsertionResult? {
        val frameNumber = packet.frameNumber

        if (isLargeJump(packet)) {
            frameHistory.indexTracker.resetAt(frameNumber)
            val frame = frameHistory.insert(packet) ?: return null

            return PacketInsertionResult(frame, true, isReset = true)
        }
        val frame = frameHistory[frameNumber]
        if (frame != null) {
            if (!frame.matchesFrame(packet)) {
                check(frame.frameNumber == frameNumber) {
                    "frame map returned frame with frame number ${frame.frameNumber} "
                    "when asked for frame with frame ID $frameNumber"
                }
                logger.warn(
                    "Cannot insert packet in frame map: " +
                        with(frame) {
                            "frame with ssrc $ssrc, timestamp $timestamp, " +
                                "and sequence number range $earliestKnownSequenceNumber-$latestKnownSequenceNumber, "
                        } +
                        with(packet) {
                            "and packet $sequenceNumber with ssrc $ssrc, timestamp $timestamp, " +
                                "and sequence number $sequenceNumber"
                        } +
                        " both have frame ID $frameNumber"
                )
                return null
            }
            try {
                frame.validateConsistency(packet)
            } catch (e: Exception) {
                logger.warn(e)
            }

            frame.addPacket(packet)
            return PacketInsertionResult(frame, isNewFrame = false)
        }

        val newframe = frameHistory.insert(packet) ?: return null

        return PacketInsertionResult(newframe, true)
    }

    /** Insert a frame. Only used for unit testing. */
    @Synchronized
    internal fun insertFrame(frame: Av1DDFrame) {
        frameHistory.insert(frame)
    }

    @Synchronized
    fun getIndex(frameIndex: Long) = frameHistory.getIndex(frameIndex)

    @Synchronized
    fun nextFrame(frame: Av1DDFrame): Av1DDFrame? {
        return frameHistory.findAfter(frame) { true }
    }

    @Synchronized
    fun nextFrameWith(frame: Av1DDFrame, pred: (Av1DDFrame) -> Boolean): Av1DDFrame? {
        return frameHistory.findAfter(frame, pred)
    }

    @Synchronized
    fun prevFrame(frame: Av1DDFrame): Av1DDFrame? {
        return frameHistory.findBefore(frame) { true }
    }

    @Synchronized
    fun prevFrameWith(frame: Av1DDFrame, pred: (Av1DDFrame) -> Boolean): Av1DDFrame? {
        return frameHistory.findBefore(frame, pred)
    }

    fun findPrevAcceptedFrame(frame: Av1DDFrame): Av1DDFrame? {
        return prevFrameWith(frame) { it.isAccepted }
    }

    fun findNextAcceptedFrame(frame: Av1DDFrame): Av1DDFrame? {
        return nextFrameWith(frame) { it.isAccepted }
    }

    companion object {
        const val FRAME_MAP_SIZE = 500 // Matches PacketCache default size.
    }
}

internal class FrameHistory(size: Int) :
    ArrayCache<Av1DDFrame>(
        size,
        cloneItem = { k -> k },
        synchronize = false
    ) {
    var numCached = 0
    var firstIndex = -1L
    var indexTracker = RtpSequenceIndexTracker()

    /**
     * Gets a frame with a given frame number from the cache.
     */
    operator fun get(frameNumber: Int): Av1DDFrame? {
        val index = indexTracker.interpret(frameNumber)
        return getIndex(index)
    }

    /**
     * Gets a frame with a given frame number index from the cache.
     */
    fun getIndex(index: Long): Av1DDFrame? {
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
    val latestFrame: Av1DDFrame?
        get() = getIndex(lastIndex)

    fun insert(frame: Av1DDFrame): Boolean {
        val ret = super.insertItem(frame, frame.index)
        if (ret) {
            numCached++
            if (firstIndex == -1L || frame.index < firstIndex) {
                firstIndex = frame.index
            }
        }
        return ret
    }

    fun insert(packet: Av1DDPacket): Av1DDFrame? {
        val index = indexTracker.update(packet.frameNumber)
        val frame = Av1DDFrame(packet, index)
        return if (insert(frame)) frame else null
    }

    /**
     * Called when an item in the cache is replaced/discarded.
     */
    override fun discardItem(item: Av1DDFrame) {
        numCached--
    }

    fun findBefore(frame: Av1DDFrame, pred: (Av1DDFrame) -> Boolean): Av1DDFrame? {
        val lastIndex = lastIndex
        if (lastIndex == -1L) {
            return null
        }
        val index = frame.index
        val searchStartIndex = min(index - 1, lastIndex)
        val searchEndIndex = max(lastIndex - size, firstIndex - 1)
        return doFind(pred, searchStartIndex, searchEndIndex, -1)
    }

    fun findAfter(frame: Av1DDFrame, pred: (Av1DDFrame) -> Boolean): Av1DDFrame? {
        val lastIndex = lastIndex
        if (lastIndex == -1L) {
            return null
        }
        val index = frame.index
        if (index >= lastIndex) {
            return null
        }
        val searchStartIndex = max(index + 1, max(lastIndex - size + 1, firstIndex))
        return doFind(pred, searchStartIndex, lastIndex + 1, 1)
    }

    private fun doFind(pred: (Av1DDFrame) -> Boolean, startIndex: Long, endIndex: Long, increment: Int): Av1DDFrame? {
        require((increment > 0 && startIndex <= endIndex) || (increment < 0 && startIndex >= endIndex)) {
            "Values of startIndex=$startIndex, endIndex=$endIndex, and increment=$increment " +
                "could lead to infinite loop"
        }
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
}

/**
 * The result of calling [insertPacket]
 */
class PacketInsertionResult(
    /** The frame corresponding to the packet that was inserted. */
    val frame: Av1DDFrame,
    /** Whether inserting the packet created a new frame.  */
    val isNewFrame: Boolean,
    /** Whether inserting the packet caused a reset  */
    val isReset: Boolean = false
)
