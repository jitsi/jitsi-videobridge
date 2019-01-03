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
 * @author George Politis
 */
public class BasicAdaptiveTrackProjectionContext
    implements AdaptiveTrackProjectionContext
{
    /**
     *
     */
    private boolean needsKeyframe = true;

    /**
     * Useful to determine whether a packet is a "keyframe".
     */
    private final MediaFormat format;

    /**
     *
     */
    private int maxDestinationSequenceNumber;

    /**
     *
     */
    private int sequenceNumberDelta;

    /**
     *
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
    BasicAdaptiveTrackProjectionContext(MediaFormat format)
    {
        this.format = format;
    }

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

    @Override
    public boolean needsKeyframe()
    {
        return needsKeyframe;
    }

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
