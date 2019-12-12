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
import org.jitsi.nlj.format.*;
import org.jitsi.nlj.rtp.codec.vp8.*;
import org.jitsi.nlj.util.*;
import org.jitsi.rtp.util.*;
import org.jitsi.utils.logging.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.utils.logging2.Logger;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/**
 * A history of recent frames on a VP8 stream.
 */
public class VP8FrameMap
{
    private final ConcurrentSkipListMap<Integer, VP8Frame>
        vp8FrameMap = new ConcurrentSkipListMap<>(
        /* This is only a valid Comparator if seq number diffs of all
         * timestamps are within half the number space.  (This property is
         * assured by #cleanupFrameMap.)
         */
        (s1, s2) -> (int) RtpUtils.getSequenceNumberDelta(s1, s2));

    private final Logger logger;

    /**
     * The diagnostic context of this instance.
     */
    private final DiagnosticContext diagnosticContext;

    final static int FRAME_MAP_SIZE = 500; /* Matches PacketCache default size. */

    /**
     * Ctor.
     *
     */
    public VP8FrameMap(
        @NotNull DiagnosticContext diagnosticContext,
        @NotNull Logger parentLogger)
    {
        this.diagnosticContext = diagnosticContext;
        this.logger = parentLogger.createChildLogger(VP8AdaptiveTrackProjectionContext.class.getName());
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
        if (vp8FrameMap.isEmpty())
        {
            return true;
        }

        int latestSeq = vp8FrameMap.lastKey();

        /* If our sequence numbers have jumped by a quarter of the number space, reset the map. */
        int seqJump = RtpUtils.getSequenceNumberDelta(newSeq, latestSeq);
        if (seqJump >= 0x4000 || seqJump <= -0x4000)
        {
            vp8FrameMap.clear();
            return true;
        }

        int threshold = RtpUtils.applySequenceNumberDelta(latestSeq, -FRAME_MAP_SIZE);

        if (RtpUtils.isOlderSequenceNumberThan(newSeq, threshold))
        {
            return false;
        }

        Iterator<Integer> it = vp8FrameMap.keySet().iterator();
        while (it.hasNext())
        {
            Integer key = it.next();
            if (RtpUtils.isOlderSequenceNumberThan(key, threshold))
            {
                it.remove();
            }
            else {
                break;
            }
        }

        return true;
    }


    /** Find a frame in the frame map, based on a packet. */
    public synchronized VP8Frame findFrame(Vp8Packet packet)
    {
        int seq = packet.getSequenceNumber();

        Map.Entry<Integer, VP8Frame> prevFrameEntry = vp8FrameMap.floorEntry(seq);
        Map.Entry<Integer, VP8Frame> nextFrameEntry = vp8FrameMap.ceilingEntry(seq);

        VP8Frame prevFrame =
            prevFrameEntry != null ? prevFrameEntry.getValue() : null;
        VP8Frame nextFrame =
            nextFrameEntry != null ? nextFrameEntry.getValue() : null;

        if (prevFrame != null && prevFrame.matchesFrame(packet))
        {
            return prevFrame;
        }
        if (nextFrame != null && nextFrame.matchesFrame(packet))
        {
            return nextFrame;
        }

        return null;
    }

    /** Get the current size of the map. */
    public int size()
    {
        return vp8FrameMap.size();
    }

    /** Helper function to insert a packet into an existing frame. */
    private FrameInsertionResult doFrameInsert(VP8Frame frame, Vp8Packet packet)
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
    public synchronized FrameInsertionResult insertPacket(Vp8Packet packet)
    {
        int seq = packet.getSequenceNumber();
        if (!cleanupFrameMap(seq))
            return null;

        Map.Entry<Integer, VP8Frame> prevFrameEntry = vp8FrameMap.floorEntry(seq);
        Map.Entry<Integer, VP8Frame> nextFrameEntry = vp8FrameMap.ceilingEntry(seq);

        VP8Frame prevFrame = prevFrameEntry != null ? prevFrameEntry.getValue() : null;
        if (prevFrame != null && prevFrame.matchesFrame(packet))
        {
            return doFrameInsert(prevFrame, packet);
        }

        VP8Frame nextFrame = nextFrameEntry != null ? nextFrameEntry.getValue() : null;
        if (nextFrame != null && nextFrame.matchesFrame(packet))
        {
            return doFrameInsert(nextFrame, packet);
        }

        VP8Frame frame = new VP8Frame(packet);

        vp8FrameMap.put(seq, frame);

        return new FrameInsertionResult(frame, true);
    }

    public synchronized VP8Frame nextFrame(VP8Frame frame)
    {
        Integer k = vp8FrameMap.higherKey(frame.getLatestKnownSequenceNumber());
        if (k == null)
        {
            return null;
        }
        return vp8FrameMap.get(k);
    }

    public synchronized VP8Frame nextFrameWith(VP8Frame frame, Predicate<VP8Frame> pred)
    {
        NavigableSet<Integer> tailSet =
            vp8FrameMap.navigableKeySet().tailSet(frame.getLatestKnownSequenceNumber(), false);

        for (int seq : tailSet)
        {
            VP8Frame f = vp8FrameMap.get(seq);
            if (pred.test(f))
            {
                return f;
            }
        }
        return null;
    }

    public synchronized VP8Frame findNextTl0(VP8Frame frame)
    {
        return nextFrameWith(frame, VP8Frame::isTL0);
    }

    public synchronized VP8Frame prevFrameWith(VP8Frame frame, Predicate<VP8Frame> pred)
    {
        NavigableSet<Integer> revHeadSet =
            vp8FrameMap.navigableKeySet().headSet(frame.getEarliestKnownSequenceNumber(), false).descendingSet();

        for (int seq : revHeadSet)
        {
            VP8Frame f = vp8FrameMap.get(seq);
            if (pred.test(f))
            {
                return f;
            }
        }
        return null;
    }

    public synchronized VP8Frame findPrevAcceptedFrame(VP8Frame frame)
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
