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
import org.jitsi.nlj.rtp.codec.vp8.*;
import org.jitsi.rtp.util.*;
import org.jitsi.utils.logging2.*;

import java.util.*;
import java.util.function.*;

/**
 * A history of recent frames on a VP8 stream.
 */
public class VP8FrameMap
{
    /** Map of timestamps to frames, used to find existing frames. */
    private final HashMap<Long, VP8Frame>
        vp8FrameMap = new HashMap<>();

    /** Map of sequence numbers to frames, used to maintain decode order of frames. */
    private final TreeMap<Integer, VP8Frame>
        vp8OrderedFrameMap = new TreeMap<>(
        /* This is only a valid Comparator if seq number diffs of all
         * timestamps are within half the number space.  (This property is
         * assured by #cleanupFrameMap.)
         */
        RtpUtils::getSequenceNumberDelta);

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

    /** Clean up old entries in the frame map.
     *
     * The frame map is maintained as a sorted map; to clean it up,
     * we walk entries from the oldest (in sequence number ordering), removing anything
     * more than #FRAME_MAP_SIZE older than the newest seq.
     *
     * Because the map is ordered, we can stop searching entries when we reach
     * one that is new enough not to be removed, so this is amortized constant
     * time per frame.
     *
     * Caller should be synchronized.
     *
     * Return false if this sequence is too old.
     * */
    private boolean cleanupFrameMap(int newSeq)
    {
        if (vp8OrderedFrameMap.isEmpty())
        {
            return true;
        }

        int latestSeq = vp8OrderedFrameMap.lastKey();

        /* If our sequence numbers have jumped by a quarter of the number space, reset the map. */
        int seqJump = RtpUtils.getSequenceNumberDelta(newSeq, latestSeq);
        if (seqJump >= 0x4000 || seqJump <= -0x4000)
        {
            vp8FrameMap.clear();
            vp8OrderedFrameMap.clear();
            return true;
        }

        int threshold = RtpUtils.applySequenceNumberDelta(latestSeq, -FRAME_MAP_SIZE);

        if (RtpUtils.isOlderSequenceNumberThan(newSeq, threshold))
        {
            return false;
        }

        Iterator<Map.Entry<Integer, VP8Frame>> it = vp8OrderedFrameMap.entrySet().iterator();
        while (it.hasNext())
        {
            Map.Entry<Integer, VP8Frame> entry = it.next();
            if (RtpUtils.isOlderSequenceNumberThan(entry.getKey(), threshold))
            {
                vp8FrameMap.remove(entry.getValue().getTimestamp());
                it.remove();
            }
            else {
                break;
            }
        }

        return true;
    }


    /** Find a frame in the frame map, based on a packet. */
    public synchronized VP8Frame findFrame(@NotNull Vp8Packet packet)
    {
        long timestamp = packet.getTimestamp();

        return vp8FrameMap.get(timestamp);
    }

    /** Get the current size of the map. */
    public int size()
    {
        return vp8OrderedFrameMap.size();
    }

    /** Helper function to insert a packet into an existing frame. */
    @NotNull
    @Contract("_, _ -> new")
    private FrameInsertionResult doFrameInsert(@NotNull VP8Frame frame, Vp8Packet packet)
    {
        if (!frame.matchesFrameConsistently(packet))
        {
            logger.warn("Packet " + packet.getSequenceNumber() + " is not consistent with frame");
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
        Long timestamp = packet.getTimestamp();

        VP8Frame frame = vp8FrameMap.get(timestamp);
        if (frame != null) {
            return doFrameInsert(frame, packet);
        }

        int seq = packet.getSequenceNumber();
        if (!cleanupFrameMap(seq))
            return null;

        frame = new VP8Frame(packet);

        vp8FrameMap.put(timestamp, frame);
        vp8OrderedFrameMap.put(seq, frame);

        return new FrameInsertionResult(frame, true);
    }

    @Nullable
    public synchronized VP8Frame nextFrame(@NotNull VP8Frame frame)
    {
        Integer k = vp8OrderedFrameMap.higherKey(frame.getLatestKnownSequenceNumber());
        if (k == null)
        {
            return null;
        }
        return vp8OrderedFrameMap.get(k);
    }

    @Nullable
    public synchronized VP8Frame nextFrameWith(@NotNull VP8Frame frame, Predicate<VP8Frame> pred)
    {
        NavigableSet<Integer> tailSet =
            vp8OrderedFrameMap.navigableKeySet().tailSet(frame.getLatestKnownSequenceNumber(), false);

        for (int seq : tailSet)
        {
            VP8Frame f = vp8OrderedFrameMap.get(seq);
            if (pred.test(f))
            {
                return f;
            }
        }
        return null;
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
        Integer k = vp8OrderedFrameMap.lowerKey(frame.getEarliestKnownSequenceNumber());
        if (k == null)
        {
            return null;
        }
        return vp8OrderedFrameMap.get(k);
    }

    @Nullable
    public synchronized VP8Frame prevFrameWith(@NotNull VP8Frame frame, Predicate<VP8Frame> pred)
    {
        NavigableSet<Integer> revHeadSet =
            vp8OrderedFrameMap.navigableKeySet().headSet(frame.getEarliestKnownSequenceNumber(), false).descendingSet();

        for (int seq : revHeadSet)
        {
            VP8Frame f = vp8OrderedFrameMap.get(seq);
            if (pred.test(f))
            {
                return f;
            }
        }
        return null;
    }

    @Nullable
    public synchronized VP8Frame findPrevAcceptedFrame(@NotNull VP8Frame frame)
    {
        return prevFrameWith(frame, VP8Frame::isAccepted);
    }

    /**
     * The result of calling {@link #insertPacket(Vp8Packet).}
     */
    public static class FrameInsertionResult {

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
}
