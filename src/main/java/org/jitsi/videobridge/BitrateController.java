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
     * The bitrate controllers for all SSRCs that this instance has seen.
     */
    private final Map<Integer, SimulcastController>
        ssrcToBitrateController = new ConcurrentHashMap<>();

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
    BitrateController(VideoChannel dest)
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
     * Defines a packet filter that controls which packets to be written into
     * the {@link Channel} that owns this {@link BitrateController}.
     *
     * @param data true if the specified packet/<tt>buffer</tt> is RTP, false if
     * it is RTCP.
     * @param buf the <tt>byte</tt> array that holds the packet.
     * @param off the offset in <tt>buffer</tt> at which the actual data begins.
     * @param len the number of <tt>byte</tt>s in <tt>buffer</tt> which
     * constitute the actual data.
     * @return <tt>true</tt> to allow the specified packet/<tt>buffer</tt> to be
     * written into the {@link Channel} that owns this {@link BitrateController}
     * ; otherwise, <tt>false</tt>
     */
    public boolean accept(boolean data, byte[] buf, int off, int len)
    {
        long ssrc;
        if (data)
        {

            ssrc = RawPacket.getSSRCAsLong(buf, off, len);
            if (ssrc < 0)
            {
                return false;
            }
        }
        else
        {
            ssrc = RTCPHeaderUtils.getSenderSSRC(buf, off, len);
            if (ssrc < 0)
            {
                return false;
            }

        }

        SimulcastController simulcastController
            = ssrcToBitrateController.get((int) ssrc);

        return simulcastController != null
            && simulcastController.accept(data, buf, off, len);
    }

    /**
     * Computes a new bitrate allocation for every endpoint in the conference,
     * and updates the state of this instance so that bitrate allocation is
     * eventually met.
     *
     * @param conferenceEndpoints the ordered list of {@link Endpoint}s
     * participating in the multipoint conference with the dominant (speaker)
     * {@link Endpoint} at the beginning of the list i.e. the dominant speaker
     * history. This parameter is optional but it can be used for performaance;
     * if it's omitted it will be fetched from the
     * {@link ConferenceSpeechActivity}.
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

        for (EndpointBitrateAllocation allocation : allocations)
        {
            int ssrc = allocation.targetSSRC,
                targetIdx = allocation.targetIdx;

            SimulcastController ctrl = ssrcToBitrateController.get(ssrc);
            if (ctrl == null && allocation.track != null)
            {
                ctrl = new SimulcastController(allocation.track);

                RTPEncodingDesc[] rtpEncodings
                    = allocation.track.getRTPEncodings();

                // Route all encodings to the specified bitrate controller.
                for (RTPEncodingDesc rtpEncoding : rtpEncodings)
                {
                    ssrcToBitrateController.put(
                        (int) rtpEncoding.getPrimarySSRC(), ctrl);

                    if (rtpEncoding.getRTXSSRC() != -1)
                    {
                        ssrcToBitrateController.put(
                            (int) rtpEncoding.getRTXSSRC(), ctrl);
                    }
                }
            }

            if (ctrl != null)
            {
                ctrl.update(targetIdx);
            }

            if (targetIdx > -1)
            {
                newForwardedEndpoints.add(allocation.endpointID);
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
    List<String> getForwardedEndpoints()
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
    boolean isForwarded(RtpChannel source)
    {
        String endpointID = source.getEndpoint().getID();
        return forwardedEndpoints.contains(endpointID);
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
                bweBps
                    = bweBps - endpointBitrateAllocations[i].getTargetBitrate();
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
         * Computes the optimal and the target bitrate, limiting the target to
         * be less than bandwidth estimation specified as an argument.
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

    /**
     * The {@link PacketTransformer} that handles incoming/outgoing RTP
     * packets for this {@link BitrateController} instance. Internally,
     * it delegates this responsibility to the appropriate media stream track
     * bitrate controller ({@link SimulcastController}, etc).
     */
    private class RTPTransformer
        extends SinglePacketTransformerAdapter
    {
        /**
         * Ctor.
         */
        public RTPTransformer()
        {
            super(RTPPacketPredicate.INSTANCE);
        }

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
        public RawPacket transform(RawPacket pkt)
        {
            int ssrc = pkt.getSSRC();

            SimulcastController subCtrl = ssrcToBitrateController.get(ssrc);

            if (subCtrl == null)
            {
                return null;
            }

            return subCtrl.rtpTransform(pkt);
        }
    }

    /**
     * The {@link PacketTransformer} that handles incoming/outgoing RTCP
     * packets for this {@link BitrateController} instance. Internally,
     * it delegates this responsibility to the appropriate media stream track
     * bitrate controller ({@link SimulcastController}, etc).
     */
    private class RTCPTransformer
        extends SinglePacketTransformerAdapter
    {
        /**
         * Ctor.
         */
        RTCPTransformer()
        {
            super(RTCPPacketPredicate.INSTANCE);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public RawPacket transform(RawPacket pkt)
        {

            int ssrc = (int) RTCPHeaderUtils.getSenderSSRC(pkt);
            SimulcastController subCtrl = ssrcToBitrateController.get(ssrc);

            if (subCtrl == null)
            {
                return null;
            }

            return subCtrl.rtcpTransform(pkt);
        }
    }
}
