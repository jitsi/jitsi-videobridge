/*
 * Copyright @ 2015 Atlassian Pty Ltd
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
package org.jitsi.videobridge.rewriting;

import java.util.*;

import org.jitsi.util.*;

import net.sf.fmj.media.rtp.*;
import net.sf.fmj.media.rtp.util.*;

/**
 * Rewrites SSRCs and sequence numbers of all SSRCs of a given
 * <tt>VideoChannel</tt>.
 */
public class SsrcRewriter
{
    private static final Logger logger = Logger.getLogger(SsrcRewriter.class);

    private static final long REWRITE_SSRC_NO_REWRITE_SSRC = -1;

    /**
     * An object that maps <tt>Integer</tt>s representing SSRCs to
     * <tt>PeerSsrcEngine</tt>.
     */
    private Map<Integer, PeerSsrcEngine> peerSsrcEngines
        = new HashMap<Integer, PeerSsrcEngine>();

    /**
     * The sequence number is 16 bits it increments by one for each RTP data
     * packet sent, and may be used by the receiver to detect packet loss
     * and to restore packet sequence.
     */
    private int currentRewriteSeqnumBase;

    /**
     * 32 bits the SSRC field identifies the synchronization source.
     */
    private long currentRewriteSsrc = REWRITE_SSRC_NO_REWRITE_SSRC;

    /**
     * Rewrites SSRCs and sequence numbers of a given SSRC of a given peer.
     */
    class PeerSsrcEngine
    {
        /**
         * This field is used only for debugging purposes, it is not technically
         * needed.
         */
        private int ssrc;

        private PeerSsrcEngine.Interval currentInterval;

        /**
         * Rewrites SSRCs and sequence numbers of a given sequence number
         * interval of a given SSRC of a given peer.
         */
        class Interval
        {
            private int min;
            private int max;
            private int base;

            public Interval(int min, int max, int base)
            {
                this.min = min;
                this.max = max;
                this.base = base;
            }

            public boolean contains(int x) {
                return min >= x && x <= max;
            }

            public String toString() {
                return "[" + min + ", " + max + "]";
            }

            public RTCPCompoundPacket transform(RTCPCompoundPacket rtcpCompoundPacket)
            {
                return rtcpCompoundPacket;
            }

            public RTCPCompoundPacket reverseTransform(RTCPCompoundPacket rtcpCompoundPacket)
            {
                return rtcpCompoundPacket;
            }

            public RTPPacket transform(RTPPacket pkt)
            {
                // Rewrite the SSRC.
                pkt.ssrc = (int) currentRewriteSsrc;

                // Rewrite the sequence number.
                int diff = pkt.seqnum - min;
                pkt.seqnum = base + diff;
                return pkt;
            }

            public RTPPacket reverseTransform(RTPPacket pkt)
            {
                return pkt;
            }

            public int length()
            {
                return max - min;
            }
        }

        /**
         * A <tt>NavigableMap</tt> that maps <tt>Integer</tt>s representing
         * interval maxes to <tt>Interval</tt>s.
         */
        private final NavigableMap<Integer, PeerSsrcEngine.Interval> intervals
                = new TreeMap<Integer, PeerSsrcEngine.Interval>();

        public PeerSsrcEngine(int ssrc)
        {
            this.ssrc = ssrc;
        }

        public RTCPCompoundPacket transform(
                RTCPCompoundPacket rtcpCompoundPacket)
        {
            return rtcpCompoundPacket;
        }

        public RTCPCompoundPacket reverseTransform(
                RTCPCompoundPacket rtcpCompoundPacket)
        {
            return rtcpCompoundPacket;
        }

        public RTPPacket transform(RTPPacket pkt)
        {
            // first, check if this is a retransmission and rewrite using
            // an appropriate interval.
            if (currentInterval != null && currentInterval.contains(pkt.seqnum))
            {
                logger.debug("Retransmitting packet with SEQNUM " + pkt.seqnum + " of SSRC " + (pkt.ssrc & 0xffffffffl) + " from the current interval.");
                return currentInterval.transform(pkt);
            }

            Map.Entry<Integer, PeerSsrcEngine.Interval> candidateInterval = intervals.higherEntry(pkt.seqnum);

            if (candidateInterval != null && candidateInterval.getValue().contains(pkt.seqnum))
            {
                logger.debug("Retransmitting packet with SEQNUM " + pkt.seqnum + " of SSRC " + (pkt.ssrc & 0xffffffffl) + " from a previous interval.");
                return candidateInterval.getValue().transform(pkt);
            }

            // this is not a retransmission.

            if (currentInterval == null)
            {
                // the stream has resumed.
                logger.debug("SSRC " + (pkt.ssrc & 0xffffffffl) + " has resumed.");
                currentInterval = new PeerSsrcEngine.Interval(pkt.seqnum, pkt.seqnum, currentRewriteSeqnumBase);
            }
            else
            {
                // more packets to the stream.
                currentInterval.max = pkt.seqnum;
            }

            return currentInterval.transform(pkt);
        }

        public RTPPacket reverseTransform(RTPPacket pkt)
        {
            return pkt;
        }

        public void assertPaused()
        {
            if (currentInterval != null)
            {
                logger.debug("Pausing SSRC " + (ssrc & 0xffffffffl) + ".");
                intervals.put(currentInterval.max, currentInterval);
                currentRewriteSeqnumBase += (currentInterval.length() + 1);
            }

            currentInterval = null;
        }
    }

    public RTPPacket transform(RTPPacket pkt)
    {
        if (currentRewriteSsrc == REWRITE_SSRC_NO_REWRITE_SSRC)
        {
            return pkt;
        }

        if (!peerSsrcEngines.containsKey(pkt.ssrc))
        {
            peerSsrcEngines.put(pkt.ssrc,  new PeerSsrcEngine(pkt.ssrc));
        }

        for (Map.Entry<Integer, PeerSsrcEngine> entry : peerSsrcEngines.entrySet())
        {
            if (entry.getKey() != pkt.ssrc)
            {
                entry.getValue().assertPaused();
            }
        }

        PeerSsrcEngine peerSsrcEngine = peerSsrcEngines.get(pkt.ssrc);

        return peerSsrcEngine.transform(pkt);
    }

    public RTPPacket reverseTransform(RTPPacket rtpPacket)
    {
        return rtpPacket;
    }

    public RTCPCompoundPacket transform(RTCPCompoundPacket rtcpCompoundPacket)
    {
        return rtcpCompoundPacket;
    }

    public RTCPCompoundPacket reverseTransform(RTCPCompoundPacket rtcpCompoundPacket)
    {
        return rtcpCompoundPacket;
    }

    public long getCurrentRewriteSsrc()
    {
        return currentRewriteSsrc;
    }

    public void setCurrentRewriteSsrc(long currentRewriteSsrc)
    {
        if (this.currentRewriteSsrc != currentRewriteSsrc)
        {
            this.currentRewriteSsrc = currentRewriteSsrc;

            // Also reset the seqnum space if the ssrc has changed.
            this.currentRewriteSeqnumBase = new Random().nextInt(0x10000); // 0xffff+1
        }
    }
}
