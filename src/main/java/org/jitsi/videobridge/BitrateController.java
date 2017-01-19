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
package org.jitsi.videobridge;

import org.jitsi.impl.neomedia.*;
import org.jitsi.impl.neomedia.rtcp.*;
import org.jitsi.impl.neomedia.rtp.*;
import org.jitsi.impl.neomedia.rtp.translator.*;
import org.jitsi.impl.neomedia.transform.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.util.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * The {@link BitrateController} is attached to a destination
 * {@link VideoChannel} and its purpose is to filter incoming packets based on
 * their source {@link VideoChannel} and modify RTCP packets. This task is
 * actually delegated to the individual {@link SimulcastController} of each
 * media stream track.
 *
 * @author George Politis
 */
public class BitrateController
    implements TransformEngine
{
    /**
     * An empty list instance.
     */
    private static final List<String> INITIAL_EMPTY_LIST
        = Collections.unmodifiableList(new LinkedList<String>());

    /**
     * The {@link VideoChannel} which owns this {@link BitrateController}.
     */
    private final VideoChannel dest;

    /**
     * The bitrate controller of the {@link Conference}.
     */
    private final Map<Integer, SimulcastController>
        subCtrls = new ConcurrentHashMap<>();

    /**
     * The {@link PacketTransformer} that handles incoming/outgoing RTP
     * packets for this {@link BitrateController} instance. Internally,
     * it delegates this responsibility to the appropriate media stream track
     * bitrate controller ({@link SimulcastController}, etc).
     */
    private final PacketTransformer rtpTransformer = new RTPTransformer();

    /**
     * The {@link PacketTransformer} that handles incoming/outgoing RTCP
     * packets for this {@link BitrateController} instance. Internally,
     * it delegates this responsibility to the appropriate media stream track
     * bitrate controller ({@link SimulcastController}, etc).
     */
    private final PacketTransformer rtcpTransformer = new RTCPTransformer();

    /**
     * The {@link List} of endpoints that are currently being forwarded,
     * represented by their IDs. Required for backwards compatibility with
     * existing LastN code.
     */
    private List<String> forwardedEndpoints = INITIAL_EMPTY_LIST;

    /**
     * Initializes a new {@link BitrateController} instance which is to
     * belong to a particular {@link VideoChannel}.
     *
     * @param dest the {@link VideoChannel} that owns this instance.
     */
    public BitrateController(VideoChannel dest)
    {
        this.dest = dest;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PacketTransformer getRTPTransformer()
    {
        return rtpTransformer;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PacketTransformer getRTCPTransformer()
    {
        return rtcpTransformer;
    }

    /**
     *
     * @param data
     * @param buf
     * @param off
     * @param len
     * @param source
     * @return
     */
    public boolean rtpTranslatorWillWrite(
        boolean data, byte[] buf, int off, int len, RtpChannel source)
    {
        Integer ssrc;
        if (data)
        {

            long ret = RawPacket.getSSRCAsLong(buf, off, len);
            if (ret < 0)
            {
                return false;
            }
            ssrc = (int) ret;
        }
        else
        {
            // NOTE(gp) This will need to be adjusted when we enable
            // reduced-size RTCP.
            long ret = RTCPHeaderUtils.getSenderSSRC(buf, off, len);
            if (ret < 0)
            {
                return false;
            }

            ssrc = (int) ret;
        }

        SimulcastController subStrl = subCtrls.get(ssrc);

        return subStrl != null
            && subStrl.rtpTranslatorWillWrite(data, buf, off, len);
    }

    /**
     *
     * @param conferenceEndpoints
     */
    public void update(List<Endpoint> conferenceEndpoints)
    {
        // Gather the conference allocation input.
        if (conferenceEndpoints == null)
        {
            conferenceEndpoints
                = dest.getConferenceSpeechActivity().getEndpoints();
        }

        long bweBps = Long.MAX_VALUE;

        // Compute the bitrate allocation.
        EndpointBitrateAllocation[]
            allocations = allocate(bweBps, conferenceEndpoints);

        List<String> newForwardedEndpoints = new ArrayList<>();

        for (int i = 0; i < allocations.length; i++)
        {
            int ssrc = allocations[i].targetSSRC,
                targetIdx = allocations[i].targetIdx;

            SimulcastController ctrl = subCtrls.get(ssrc);
            if (ctrl == null && allocations[i].track != null)
            {
                ctrl = new SimulcastController(allocations[i].track);

                RTPEncodingDesc[] rtpEncodings
                    = allocations[i].track.getRTPEncodings();

                // Route all encodings to the specified bitrate controller.
                for (int j = 0; j < rtpEncodings.length; j++)
                {
                    subCtrls.put((int) rtpEncodings[j].getPrimarySSRC(), ctrl);

                    if (rtpEncodings[j].getRTXSSRC() != -1)
                    {
                        subCtrls.put((int) rtpEncodings[j].getRTXSSRC(), ctrl);
                    }
                }
            }

            if (ctrl != null)
            {
                ctrl.update(targetIdx);
            }

            if (targetIdx > -1)
            {
                newForwardedEndpoints.add(allocations[i].endpointID);
            }
        }

        this.forwardedEndpoints = newForwardedEndpoints;
    }

    /**
     * Gets the {@link List} of endpoints that are currently being forwarded,
     * represented by their IDs.
     *
     * @return the {@link List} of endpoints that are currently being forwarded,
     * represented by their IDs.
     */
    public List<String> getForwardedEndpoints()
    {
        return forwardedEndpoints;
    }

    /**
     * Checks whether RTP packets from {@code source} should be forwarded
     * to {@link #dest}.
     * @param source the channel.
     * @return {@code true} iff RTP packets from {@code source} should
     * be forwarded to {@link #dest}.
     */
    public boolean isForwarded(RtpChannel source)
    {
        String endpointID = source.getEndpoint().getID();
        return forwardedEndpoints.contains(endpointID);
    }

    /**
     * The {@link PacketTransformer} that handles incoming/outgoing RTP
     * packets for this {@link BitrateController} instance. Internally,
     * it delegates this responsibility to the appropriate media stream track
     * bitrate controller ({@link SimulcastController}, etc).
     */
    class RTPTransformer
        implements PacketTransformer
    {
        /**
         * {@inheritDoc}
         */
        @Override
        public void close()
        {
            // TODO decrease counters.
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public RawPacket[] reverseTransform(RawPacket[] pkts)
        {
            return pkts;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public RawPacket[] transform(RawPacket[] pkts)
        {
            if (ArrayUtils.isNullOrEmpty(pkts))
            {
                return pkts;
            }

            for (int i = 0; i < pkts.length; i++)
            {
                if (pkts[i] == null
                    || !RTPPacketPredicate.INSTANCE.test(pkts[i]))
                {
                    continue;
                }

                int ssrc = pkts[i].getSSRC();
                SimulcastController subCtrl = subCtrls.get(ssrc);

                if (subCtrl == null)
                {
                    pkts[i] = null;
                    continue;
                }

                RawPacket[] transformedPkts = subCtrl
                    .getRTPTransformer().transform(new RawPacket[]{pkts[i]});

                pkts[i] = transformedPkts[0];
            }

            return pkts;
        }
    }

    /**
     * The {@link PacketTransformer} that handles incoming/outgoing RTCP
     * packets for this {@link BitrateController} instance. Internally,
     * it delegates this responsibility to the appropriate media stream track
     * bitrate controller ({@link SimulcastController}, etc).
     */
    class RTCPTransformer
        implements PacketTransformer
    {
        /**
         * {@inheritDoc}
         */
        @Override
        public void close()
        {

        }

        /**
         * {@inheritDoc}
         */
        @Override
        public RawPacket[] reverseTransform(RawPacket[] pkts)
        {
            return pkts;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public RawPacket[] transform(RawPacket[] pkts)
        {
            if (ArrayUtils.isNullOrEmpty(pkts))
            {
                return pkts;
            }

            for (int i = 0; i < pkts.length; i++)
            {
                if (pkts[i] == null
                    || !RTPPacketPredicate.INSTANCE.test(pkts[i]))
                {
                    continue;
                }

                int ssrc = (int) RTCPHeaderUtils.getSenderSSRC(pkts[i]);
                SimulcastController subCtrl = subCtrls.get(ssrc);

                if (subCtrl == null)
                {
                    pkts[i] = null;
                    continue;
                }

                RawPacket[] transformedPkts = subCtrl
                    .getRTCPTransformer().transform(new RawPacket[]{pkts[i]});

                pkts[i] = transformedPkts[0];
            }

            return pkts;
        }
    }

    /**
     * Computes the optimal and the target bitrate, limiting the target to be
     * less than bandwidth estimation specified as an argument.
     *
     * @param bweBps the max bandwidth estimation that the target bitrate must
     * not exceed.
     *
     * @return an array of {@link EndpointBitrateAllocation}.
     */
    private EndpointBitrateAllocation[] allocate(
        long bweBps, List<Endpoint> conferenceEndpoints)
    {
        // Init.
        int szConference = conferenceEndpoints.size();

        // subtract 1 for destEndpoint.
        EndpointBitrateAllocation[] endpointBitrateAllocations
            = new EndpointBitrateAllocation[szConference - 1];

        int lastN = dest.getLastN();
        if (lastN < 0)
        {
            // If lastN is disable, pretend lastN == szConference.
            lastN = endpointBitrateAllocations.length;
        }

        Endpoint destEndpoint = dest.getEndpoint();
        Set<String> pinnedEndpoints = destEndpoint.getPinnedEndpoints();
        int szPinned = pinnedEndpoints == null ? 0 : pinnedEndpoints.size();
        if (szPinned > 0)
        {
            // The lastN value should be large enough to accommodate all the
            // pinned endpoints.
            lastN = Math.max(lastN, szPinned);
        }

        Set<String> selectedEndpoints = destEndpoint.getSelectedEndpoints();
        int offPinned = 0, szSelected
            = selectedEndpoints == null ? 0 : selectedEndpoints.size();

        int maxQuality = 0;
        long oldBweBps = bweBps;

        int idx = -1;
        for (int i = 0; i < szConference; i++)
        {
            Endpoint sourceEndpoint = conferenceEndpoints.get(i);
            if (sourceEndpoint == destEndpoint)
            {
                continue;
            }

            idx++;

            boolean pinned = szPinned != 0
                && pinnedEndpoints.contains(sourceEndpoint.getID());

            int priority; // Higher priority endpoints are optimized first.
            if (pinned)
            {
                priority = offPinned;
                offPinned++;
            }
            else
            {
                priority = idx - offPinned + szPinned;
            }

            boolean selected = szSelected != 0
                && selectedEndpoints.contains(sourceEndpoint.getID());

            boolean forwarded = priority < lastN;

            EndpointBitrateAllocation endpointBitrateAllocation
                = new EndpointBitrateAllocation(
                sourceEndpoint, forwarded, selected);

            endpointBitrateAllocations[priority] = endpointBitrateAllocation;

            // First pass.
            endpointBitrateAllocation.allocate(bweBps, maxQuality);
            bweBps = bweBps - endpointBitrateAllocation.getTargetBitrate();

            maxQuality++;
        }

        while (oldBweBps != bweBps)
        {
            oldBweBps = bweBps;

            for (int i = 0; i < endpointBitrateAllocations.length; i++)
            {
                bweBps
                    = bweBps + endpointBitrateAllocations[i].getTargetBitrate();
                endpointBitrateAllocations[i].allocate(bweBps, maxQuality);
                bweBps = bweBps - endpointBitrateAllocations[i].getTargetBitrate();
            }

            maxQuality++;
        }

        return endpointBitrateAllocations;
    }

    /**
     * @author George Politis
     */
    private class EndpointBitrateAllocation
    {
        /**
         *
         */
        private final String endpointID;

        /**
         *
         */
        private final boolean forwarded;

        /**
         *
         */
        private final boolean selected;

        /**
         *
         */
        private final int targetSSRC;

        /**
         *
         */
        private final MediaStreamTrackDesc track;

        /**
         *
         */
        private final long[] rates;

        /**
         *
         */
        private int targetIdx = -1;

        /**
         * Ctor.
         *
         * @param endpoint the {@link Endpoint} that this bitrate allocation
         * pertains to.
         * @param forwarded a flag indicating whether or not the endpoint is in
         * LastN.
         * @param selected a flag indicating whether or not the endpoint is
         * selected.
         */
        EndpointBitrateAllocation(
            Endpoint endpoint, boolean forwarded, boolean selected)
        {
            this.endpointID = endpoint.getID();
            this.selected = selected;
            this.forwarded = forwarded;

            // This assumes that the array is ordered by the subjective quality
            // ordering ex: one can argue that 360p@30fps looks better than
            // 720p@7.5fps.
            MediaStreamTrackDesc[] tracks
                = endpoint.getMediaStreamTracks(MediaType.VIDEO);

            if (ArrayUtils.isNullOrEmpty(tracks))
            {
                rates = new long[0];
                targetSSRC = -1;
                track = null;
                return;
            }


            RTPEncodingDesc[] encodings = tracks[0].getRTPEncodings();

            if (ArrayUtils.isNullOrEmpty(encodings))
            {
                rates = new long[0];
                targetSSRC = -1;
                track = null;
                return;
            }

            targetSSRC = (int) encodings[0].getPrimarySSRC();
            track = tracks[0];

            // Initialize rates.
            rates = new long[encodings.length];
            for (int i = 0; i < encodings.length; i++)
            {
                rates[i] = encodings[i].getLastStableBitrateBps();
            }
        }

        /**
         * Computes the optimal and the target bitrate, limiting the target to be
         * less than bandwidth estimation specified as an argument.
         *
         * @param maxBps the maximum bitrate (in bps) that the target subjective
         * quality can have.
         * @param maxQuality the maximum subjective quality that the target
         * subjective quality can have.
         */
        void allocate(long maxBps, int maxQuality)
        {
            if (!forwarded || rates.length == 0)
            {
                return;
            }

            maxQuality = selected ? Math.min(maxQuality, rates.length - 1) : 0;

            for (int i = maxQuality; i >= 0; i--)
            {
                if (maxBps >= rates[i])
                {
                    targetIdx = i;
                    break;
                }
            }
        }

        /**
         *
         * @return the target bitrate (in bps) for this endpoint allocation.
         */
        long getTargetBitrate()
        {
            return targetIdx == -1 ? 0 : rates[targetIdx];
        }
    }
}
