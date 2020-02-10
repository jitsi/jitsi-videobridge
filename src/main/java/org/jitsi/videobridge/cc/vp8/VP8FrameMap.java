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
package org.jitsi.videobridge.cc.vp8;

import org.jetbrains.annotations.*;
import org.jitsi.nlj.codec.vp8.*;
import org.jitsi.nlj.rtp.codec.vp8.*;
import org.jitsi.nlj.util.*;
import org.jitsi.utils.logging2.*;

import java.time.*;
import java.util.function.*;

import static java.lang.Integer.max;
import static java.lang.Integer.min;

/**
 * A history of recent frames on a VP8 stream.
 */
public class VP8FrameMap
{
    /** Cache mapping picture IDs to frames. */
    private final FrameHistory frameHistory = new FrameHistory(FRAME_MAP_SIZE);

    private final Logger logger;

    final static int FRAME_MAP_SIZE = 500; /* Matches PacketCache default size. */

    /**
     * Ctor.
     *
     */
    public VP8FrameMap(
        @NotNull Logger parentLogger)
    {
        this.logger = parentLogger.createChildLogger(VP8FrameMap.class.getName());
    }

    /** Find a frame in the frame map, based on a packet. */
    public synchronized VP8Frame findFrame(@NotNull Vp8Packet packet)
    {
        return frameHistory.get(packet.getPictureId());
    }

    /** Get the current size of the map. */
    public int size()
    {
        return frameHistory.numCached;
    }

    /** Helper function to insert a packet into an existing frame. */
    @NotNull
    @Contract("_, _ -> new")
    private FrameInsertionResult doFrameInsert(@NotNull VP8Frame frame, Vp8Packet packet)
    {
        try
        {
            frame.validateConsistent(packet);
        }
        catch (Exception e)
        {
            logger.warn(e);
        }
        frame.addPacket(packet);
        return new FrameInsertionResult(frame, false);
    }


    /** Insert a packet into the frame map.  Return a FrameInsertionResult
     *  describing what happened.
     * @param packet The packet to insert.
     * @return What happened.  null if insertion failed.
     */
    public synchronized FrameInsertionResult insertPacket(@NotNull Vp8Packet packet)
    {
        int pictureId = packet.getPictureId();

        VP8Frame frame = frameHistory.get(pictureId);
        if (frame != null)
        {
            if (!frame.matchesFrame(packet))
            {
                if (frame.getPictureId() != pictureId)
                {
                    throw new IllegalStateException("Frame map returned frame with picture ID " +
                        frame.getPictureId() +
                        " when asked for frame with picture ID " + pictureId);
                }
                logger.warn("Cannot insert packet in frame map: " +
                    "frame with ssrc " + frame.getSsrc() +
                    ", timestamp " + frame.getTemporalLayer() +
                    ", and sequence number range " + frame.getEarliestKnownSequenceNumber() +
                    "-" + frame.getLatestKnownSequenceNumber() +
                    ", and packet " + packet.getSequenceNumber() +
                    " with ssrc " + packet.getSsrc() +
                    ", timestamp " + packet.getTimestamp() +
                    ", and sequence number " + packet.getTimestamp() +
                    " both have picture ID " + pictureId);
                return null;
            }
            return doFrameInsert(frame, packet);
        }

        frame = new VP8Frame(packet);

        if (!frameHistory.insert(pictureId, frame))
        {
            return null;
        }

        return new FrameInsertionResult(frame, true);
    }

    @Nullable
    public synchronized VP8Frame nextFrame(@NotNull VP8Frame frame)
    {
        return frameHistory.findAfter(frame, (VP8Frame f) -> true );
    }

    @Nullable
    public synchronized VP8Frame nextFrameWith(@NotNull VP8Frame frame, Predicate<VP8Frame> pred)
    {
        return frameHistory.findAfter(frame, pred);
    }

    @Nullable
    public synchronized VP8Frame findNextTl0(@NotNull VP8Frame frame)
    {
        return nextFrameWith(frame, VP8Frame::isTL0);
    }

    @Nullable
    public synchronized VP8Frame findNextAcceptedFrame(@NotNull VP8Frame frame)
    {
        return nextFrameWith(frame, VP8Frame::isAccepted);
    }

    @Nullable
    public synchronized VP8Frame prevFrame(@NotNull VP8Frame frame)
    {
        return frameHistory.findBefore(frame, (VP8Frame f) -> true );
    }

    @Nullable
    public synchronized VP8Frame prevFrameWith(@NotNull VP8Frame frame, Predicate<VP8Frame> pred)
    {
        return frameHistory.findBefore(frame, pred);
    }

    @Nullable
    public synchronized VP8Frame findPrevAcceptedFrame(@NotNull VP8Frame frame)
    {
        return prevFrameWith(frame, VP8Frame::isAccepted);
    }

    /**
     * The result of calling {@link #insertPacket(Vp8Packet).}
     */
    public static class FrameInsertionResult
    {

        /** The frame corresponding to the packet that was inserted. */
        private VP8Frame frame;

        /** Whether inserting the frame created a new frame. */
        private boolean newFrame;

        /** Construct a FrameInsertionResult. */
        private FrameInsertionResult(VP8Frame frame, boolean newFrame)
        {
            this.frame = frame;
            this.newFrame = newFrame;
        }

        /** Get the frame corresponding to the packet that was inserted. */
        public VP8Frame getFrame()
        {
            return frame;
        }

        /** Get whether inserting the frame created a new frame. */
        public boolean isNewFrame()
        {
            return newFrame;
        }
    }

    private static class FrameHistory extends ArrayCache<VP8Frame>
    {
        FrameHistory(int size)
        {
            super(size, (k) -> k, false, Clock.systemUTC());
        }

        int numCached = 0;
        int firstIndex = -1;

        PictureIdIndexTracker indexTracker = new PictureIdIndexTracker();

        /**
         * Gets a packet with a given VP8 picture ID from the cache.
         */
        public VP8Frame get(int pictureId)
        {
            int index = indexTracker.interpret(pictureId);
            return getIndex(index);
        }

        /**
         * Gets a packet with a given VP8 picture ID index from the cache.
         */
        private VP8Frame getIndex(int index)
        {
            if (index <= getLastIndex() - getSize())
            {
                /* We don't want to remember old frames even if they're still
                   tracked; their neighboring frames may have been evicted,
                   so findBefore / findAfter will return bogus data. */
                return null;
            }
            ArrayCache<VP8Frame>.Container c = getContainer(index);
            if (c == null)
            {
                return null;
            }
            return c.getItem();
        }

        public boolean insert(int pictureId, VP8Frame frame)
        {
            int index = indexTracker.update(pictureId);
            boolean ret = super.insertItem(frame, index);
            if (ret)
            {
                numCached++;
                if (firstIndex == -1 || index < firstIndex)
                {
                    firstIndex = index;
                }
            }
            return ret;
        }

        /**
         * Called when an item in the cache is replaced/discarded.
         */
        @Override
        protected void discardItem(VP8Frame frame)
        {
            numCached--;
        }

        public VP8Frame findBefore(VP8Frame frame, Predicate<VP8Frame> pred)
        {
            int lastIndex = getLastIndex();
            if (lastIndex == -1)
            {
                return null;
            }

            int index = indexTracker.interpret(frame.getPictureId());

            int searchStartIndex = min(index - 1, lastIndex);
            int searchEndIndex = max(lastIndex - getSize(), firstIndex - 1);

            return doFind(pred, searchStartIndex, searchEndIndex, -1);
        }

        public VP8Frame findAfter(VP8Frame frame, Predicate<VP8Frame> pred)
        {
            int lastIndex = getLastIndex();
            if (lastIndex == -1)
            {
                return null;
            }

            int index = indexTracker.interpret(frame.getPictureId());

            if (index >= lastIndex)
            {
                return null;
            }

            int searchStartIndex = max(index + 1, max(lastIndex - getSize(), firstIndex));

            return doFind(pred, searchStartIndex, lastIndex + 1, 1);
        }

        private VP8Frame doFind(Predicate<VP8Frame> pred, int startIndex, int endIndex, int increment)
        {
            for (int index = startIndex; index != endIndex; index += increment)
            {
                VP8Frame frame = getIndex(index);
                if (frame != null && pred.test(frame))
                {
                    return frame;
                }
            }
            return null;
        }

        /** Like Rfc3711IndexTracker, but for picture IDs (so with a rollover
         * of 0x8000).
         */
        private static class PictureIdIndexTracker
        {
            private int roc = 0;
            private int highestSeqNumReceived = -1;

            private int getIndex(int seqNum, boolean updateRoc)
            {
                if (highestSeqNumReceived == -1)
                {
                    if (updateRoc)
                    {
                        highestSeqNumReceived = seqNum;
                    }
                    return seqNum;
                }

                int delta = Vp8Utils.getExtendedPictureIdDelta(seqNum, highestSeqNumReceived);

                int v;

                if (delta < 0 && highestSeqNumReceived < seqNum)
                {
                    v = roc - 1;
                }
                else if (delta > 0 && seqNum < highestSeqNumReceived)
                {
                    v = roc + 1;
                    if (updateRoc)
                        roc = v;
                }
                else
                {
                    v = roc;
                }

                if (updateRoc && delta > 0)
                {
                    highestSeqNumReceived = seqNum;
                }

                return 0x8000 * v + seqNum;
            }

            public int update(int seq)
            {
                return getIndex(seq, true);
            }

            public int interpret(int seq)
            {
                return getIndex(seq, false);
            }
        }
    }
}
