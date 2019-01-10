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
package org.jitsi.videobridge.cc;

import net.sf.fmj.media.rtp.*;
import org.jetbrains.annotations.*;
import org.jitsi.impl.neomedia.rtcp.*;
import org.jitsi.impl.neomedia.rtp.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.service.neomedia.codec.*;
import org.jitsi.service.neomedia.format.*;
import org.jitsi.util.*;

/**
 * A generic implementation of an adaptive track projection context that can be
 * used with non-SVC codecs or when simulcast is not enabled/used or when
 * support for these advanced features is not implemented in the bridge. In this
 * restricted case the track projection can have only two states (or qualities),
 * either off or on (or -1, 0).
 *
 * Instances of this class suspend a track when the target quality is set to -1.
 * When the target quality is set back to 0, the request key frame flag is
 * raised and the track is re-activated after a key frame is received.
 * (consequently support for key frame detection for the specific media format
 * of the track that is being adapted is necessary).
 *
 * In order to make the suspend/resume operation transparent (at least in the
 * RTP level), instances of this class rewrite the RTP sequence to hide the gaps
 * caused by the suspend/resume operation.
 *
 * This may not be sufficient for fluid playback at the receiver as the decoder
 * may be unable to handle codec specific discontinuities (such as discontinuous
 * picture IDs in VP8). In this case a codec specific adaptive track projection
 * context implementation will have to be used instead.
 *
 * @author George Politis
 */
class GenericAdaptiveTrackProjectionContext
    implements AdaptiveTrackProjectionContext
{
    /**
     * Raised when a track has been resumed (after being suspended).
     */
    private boolean needsKeyframe = true;

    /**
     * Useful to determine whether a packet is a "keyframe".
     */
    private final MediaFormat format;

    /**
     * The maximum sequence number that we have sent.
     */
    private int maxDestinationSequenceNumber;

    /**
     * The delta to apply to the sequence numbers of the RTP packets of the
     * source track.
     */
    private int sequenceNumberDelta;

    /**
     * The synchronization root of {@link #transmittedBytes} and
     * {@link #transmittedPackets}.
     */
    private final Object transmittedSyncRoot = new Object();

    /**
     * Keeps track of the number of transmitted bytes. This is used in RTCP SR
     * rewriting.
     */
    private long transmittedBytes = 0;

    /**
     * Keeps track of the number of transmitted packets. This is used in RTCP SR
     * rewriting.
     */
    private long transmittedPackets = 0;

    /**
     * Ctor.
     *
     * @param format the media format to expect
     */
    GenericAdaptiveTrackProjectionContext(MediaFormat format)
    {
        this.format = format;
    }

    /**
     * Determines whether an RTP packet from the source track should be accepted
     * or not. If the track is currently suspended, a key frame is necessary to
     * start accepting packets again.
     *
     * @param rtpPacket the RTP packet to determine whether to accept or not.
     * @param targetIndex the target quality index
     * @return true if the packet should be accepted, false otherwise.
     */
    @Override
    public synchronized boolean
    accept(@NotNull RawPacket rtpPacket, int targetIndex)
    {
        if (targetIndex < 0)
        {
            // suspend the stream.
            needsKeyframe = true;
            return false;
        }

        int sourceSequenceNumber = rtpPacket.getSequenceNumber();

        boolean accept;
        if (needsKeyframe)
        {
            if (isKeyframe(rtpPacket))
            {
                needsKeyframe = false;
                // resume after being suspended, we compute the new seqnum delta
                // delta = destination - source <=> destination = source + delta
                sequenceNumberDelta = RTPUtils.getSequenceNumberDelta(
                    maxDestinationSequenceNumber + 1, sourceSequenceNumber);
                accept = true;
            }
            else
            {
                accept = false;
            }
        }
        else
        {
            accept = true;
        }

        if (accept)
        {
            int destinationSequenceNumber
                = (sourceSequenceNumber + sequenceNumberDelta)
                & RawPacket.SEQUENCE_NUMBER_MASK;

            if (RTPUtils.isOlderSequenceNumberThan(
                maxDestinationSequenceNumber, destinationSequenceNumber))
            {
                maxDestinationSequenceNumber = destinationSequenceNumber;
            }
        }

        return accept;
    }

    private boolean isKeyframe(@NotNull RawPacket rtpPacket)
    {
        byte[] buf = rtpPacket.getBuffer();
        int payloadOff = rtpPacket.getPayloadOffset(),
            payloadLen = rtpPacket.getPayloadLength();

        if (Constants.VP8.equalsIgnoreCase(format.getEncoding()))
        {
            return org.jitsi.impl.neomedia.codec.video.vp8.DePacketizer
                .isKeyFrame(buf, payloadOff, payloadLen);
        }
        else if (Constants.H264.equalsIgnoreCase(format.getEncoding()))
        {
            return org.jitsi.impl.neomedia.codec.video.h264.DePacketizer
                .isKeyFrame(buf, payloadOff, payloadLen);
        }
        else if (Constants.VP9.equalsIgnoreCase(format.getEncoding()))
        {
            return org.jitsi.impl.neomedia.codec.video.vp9.DePacketizer
                .isKeyFrame(buf, payloadOff, payloadLen);
        }
        else
        {
            return false;
        }
    }

    /**
     * @return true when a track has been resumed (after being suspended).
     */
    @Override
    public boolean needsKeyframe()
    {
        return needsKeyframe;
    }

    /**
     * Applies a delta to the sequence number of the RTP packet that is
     * specified as an argument in order to make suspending/resuming of the
     * source track transparent at the RTP level.
     *
     * @param rtpPacket the RTP packet to rewrite.
     * @param incomingRawPacketCache the packet cache to pull piggy-backed
     * packets from. It can be left null because piggybacking is not
     * implemented.
     * @return {@link #EMPTY_PACKET_ARR}
     */
    @Override
    public RawPacket[] rewriteRtp(
        @NotNull RawPacket rtpPacket, RawPacketCache incomingRawPacketCache)
    {
        int sourceSequenceNumber = rtpPacket.getSequenceNumber();
        int destinationSequenceNumber
            = (sourceSequenceNumber + sequenceNumberDelta)
            & RawPacket.SEQUENCE_NUMBER_MASK;

        if (sourceSequenceNumber != destinationSequenceNumber)
        {
            rtpPacket.setSequenceNumber(destinationSequenceNumber);
        }

        synchronized (transmittedSyncRoot)
        {
            transmittedBytes += rtpPacket.getLength();
            transmittedPackets++;
        }

        return EMPTY_PACKET_ARR;
    }

    /**
     * If the first RTCP packet of the compound RTCP packet that is specified as
     * a parameter is an SR, then this method updates the transmitted bytes and
     * transmitted packets of that first SR.
     *
     * @param rtcpPacket the compound RTCP packet to rewrite.
     * @return true.
     */
    @Override
    public boolean rewriteRtcp(@NotNull RawPacket rtcpPacket)
    {
        if (RTCPUtils.getPacketType(rtcpPacket) == RTCPPacket.SR)
        {
            synchronized (transmittedSyncRoot)
            {
                // Rewrite packet/octet count.
                RTCPSenderInfoUtils
                    .setOctetCount(rtcpPacket, (int) transmittedBytes);
                RTCPSenderInfoUtils
                    .setPacketCount(rtcpPacket, (int) transmittedPackets);
            }
        }

        return true;
    }
}
