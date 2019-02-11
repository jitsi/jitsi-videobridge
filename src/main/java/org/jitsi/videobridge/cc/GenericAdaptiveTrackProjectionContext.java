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
 * In order to make the suspend/resume operation transparent to the receiver (at
 * least in the RTP level), instances of this class rewrite the RTP sequence
 * numbers of the source track to hide the gaps caused by the suspend/resume
 * operation.
 *
 * This may not be sufficient for fluid playback at the receiver as the decoder
 * may be unable to handle codec specific discontinuities (such as discontinuous
 * picture IDs in VP8). In this case a codec specific adaptive track projection
 * context implementation will have to be used instead.
 *
 * Instances of this class are thread-safe.
 *
 * @author George Politis
 */
class GenericAdaptiveTrackProjectionContext
    implements AdaptiveTrackProjectionContext
{
    /**
     * The <tt>Logger</tt> used by the
     * <tt>GenericAdaptiveTrackProjectionContext</tt> class and its instances to
     * log debug information.
     */
    private static final Logger logger
        = Logger.getLogger(GenericAdaptiveTrackProjectionContext.class);

    private final long ssrc;

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
     * The delta to apply to the timestamps of the RTP packets of the source
     * track.
     */
    private long timestampDelta;

    /**
     * A boolean that indicates whether or not the timestap delta has been
     * initialized. This is only necessary upon adaptive track projection
     * context switches.
     */
    private boolean timestampDeltaInitialized = false;

    /**
     * The maximum timestamp that we have sent.
     */
    private long maxDestinationTimestamp;

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
    private long transmittedBytes;

    /**
     * Keeps track of the number of transmitted packets. This is used in RTCP SR
     * rewriting.
     */
    private long transmittedPackets;

    /**
     * Ctor.
     *
     * @param format the media format to expect
     * @param rtpState the RTP state (i.e. seqnum, timestamp to start with, etc).
     */
    GenericAdaptiveTrackProjectionContext(
        @NotNull MediaFormat format, @NotNull RtpState rtpState)
    {
        this.format = format;
        this.ssrc = rtpState.ssrc;
        this.transmittedBytes = rtpState.transmittedBytes;
        this.transmittedPackets = rtpState.transmittedPackets;
        this.maxDestinationSequenceNumber = rtpState.maxSequenceNumber;
        this.maxDestinationTimestamp = rtpState.maxTimestamp;
    }

    /**
     * Determines whether an RTP packet from the source track should be accepted
     * or not. If the track is currently suspended, a key frame is necessary to
     * start accepting packets again.
     *
     * Note that, at the time of this writing, there's no practical need for a
     * synchronized keyword because there's only one thread (the translator
     * thread) accessing this method at a time.
     *
     * @param rtpPacket the RTP packet to determine whether to accept or not.
     * @param incomingIndex the quality index of the
     * @param targetIndex the target quality index
     * @return true if the packet should be accepted, false otherwise.
     */
    @Override
    public synchronized boolean
    accept(@NotNull RawPacket rtpPacket, int incomingIndex, int targetIndex)
    {
        if (targetIndex == RTPEncodingDesc.SUSPENDED_INDEX)
        {
            // suspend the stream.
            needsKeyframe = true;
            return false;
        }

        int sourceSequenceNumber = rtpPacket.getSequenceNumber();

        boolean accept;
        if (needsKeyframe)
        {
            if (isKeyframe(rtpPacket, format))
            {
                needsKeyframe = false;
                // resume after being suspended, we compute the new seqnum delta
                // delta = destination - source <=> destination = source + delta
                // In other words, we compute the using this formula
                // "delta = destination - source" and in order to find the
                // destination sequence number we use the equivalent formula
                // "destination = source + delta".
                int destinationSequenceNumber
                    = maxDestinationSequenceNumber + 1;
                sequenceNumberDelta = RTPUtils.getSequenceNumberDelta(
                    destinationSequenceNumber, sourceSequenceNumber);

                if (logger.isDebugEnabled())
                {
                    logger.debug("delta ssrc=" + rtpPacket.getSSRCAsLong()
                        + ",src_sequence=" + sourceSequenceNumber
                        + ",dst_sequence=" + destinationSequenceNumber
                        + ",max_sequence=" + maxDestinationSequenceNumber
                        + ",delta=" + sequenceNumberDelta);
                }

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
            maybeInitializeTimestampDelta(rtpPacket.getTimestamp());

            int destinationSequenceNumber
                = computeDestinationSequenceNumber(sourceSequenceNumber);

            long destinationTimestamp
                = computeDestinationTimestamp(rtpPacket.getTimestamp());

            if (RTPUtils.isOlderSequenceNumberThan(
                maxDestinationSequenceNumber, destinationSequenceNumber))
            {
                maxDestinationSequenceNumber = destinationSequenceNumber;
            }

            if (RTPUtils.isNewerTimestampThan(
                destinationSequenceNumber, maxDestinationTimestamp))
            {
                maxDestinationTimestamp = destinationTimestamp;
            }

            if (logger.isDebugEnabled())
            {
                logger.debug("accept ssrc=" + rtpPacket.getSSRCAsLong()
                + ",src_sequence=" + sourceSequenceNumber
                + ",dst_sequence=" + destinationSequenceNumber
                + ",max_sequence=" + maxDestinationSequenceNumber);
            }
        }
        else
        {
            if (logger.isDebugEnabled())
            {
                logger.debug("reject ssrc=" + rtpPacket.getSSRCAsLong()
                    + ",src_sequence=" + sourceSequenceNumber);
            }
        }

        return accept;
    }

    private void maybeInitializeTimestampDelta(long sourceTimestamp)
    {
        if (timestampDeltaInitialized)
        {
            return;
        }

        if (RTPUtils.isNewerTimestampThan(
            maxDestinationSequenceNumber, sourceTimestamp))
        {
            long destinationTimestamp =
                (maxDestinationTimestamp + 3000) & RawPacket.TIMESTAMP_MASK;

            timestampDelta
                = RTPUtils.rtpTimestampDiff(destinationTimestamp, sourceTimestamp);
        }

        timestampDeltaInitialized = true;
    }

    private static boolean isKeyframe(
        @NotNull RawPacket rtpPacket, @NotNull MediaFormat format)
    {
        // XXX merge with MediaStream.isKeyframe().
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
            = computeDestinationSequenceNumber(sourceSequenceNumber);

        if (sourceSequenceNumber != destinationSequenceNumber)
        {
            rtpPacket.setSequenceNumber(destinationSequenceNumber);
        }

        long sourceTimestamp = rtpPacket.getTimestamp();
        long destinationTimestamp
            = computeDestinationTimestamp(sourceTimestamp);

        if (sourceTimestamp != destinationTimestamp)
        {
            rtpPacket.setTimestamp(destinationTimestamp);
        }

        if (logger.isDebugEnabled())
        {
            logger.debug("rewrite ssrc=" + rtpPacket.getSSRCAsLong()
                + ",src_sequence=" + sourceSequenceNumber
                + ",dst_sequence=" + destinationSequenceNumber
                + ",max_sequence=" + maxDestinationSequenceNumber);
        }

        synchronized (transmittedSyncRoot)
        {
            transmittedBytes += rtpPacket.getLength();
            transmittedPackets++;
        }

        return EMPTY_PACKET_ARR;
    }

    private int computeDestinationSequenceNumber(int sourceSequenceNumber)
    {
        return sequenceNumberDelta != 0
            ? (sourceSequenceNumber + sequenceNumberDelta)
            & RawPacket.SEQUENCE_NUMBER_MASK : sourceSequenceNumber;
    }

    private long computeDestinationTimestamp(long sourceTimestamp)
    {
        return timestampDelta != 0
            ? (sourceTimestamp + timestampDelta) & RawPacket.TIMESTAMP_MASK
            : sourceTimestamp;
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

    @Override
    public RtpState getRtpState()
    {
        synchronized (transmittedSyncRoot)
        {
            return new RtpState(
                transmittedBytes, transmittedPackets,
                ssrc, maxDestinationSequenceNumber, maxDestinationTimestamp);
        }
    }

    @Override
    public MediaFormat getFormat()
    {
        return format;
    }
}
