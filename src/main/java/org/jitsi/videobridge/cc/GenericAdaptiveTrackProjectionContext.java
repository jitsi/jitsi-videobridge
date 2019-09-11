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

import org.jetbrains.annotations.*;
import org.jitsi.nlj.format.*;
import org.jitsi.nlj.rtp.*;
import org.jitsi.nlj.util.*;
import org.jitsi.rtp.rtcp.*;
import org.jitsi.rtp.util.*;
import org.jitsi.utils.logging2.*;
import org.jitsi_modified.impl.neomedia.rtp.*;
import org.json.simple.*;

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
     * An empty array that is used as a return value when no packets need to be
     * piggy-backed.
     */
    private static final VideoRtpPacket[] EMPTY_PACKET_ARR = new VideoRtpPacket[0];

    /**
     * Checks if the given packet with the given format is part of a key frame.
     */
    private static boolean isKeyframe(
            @NotNull VideoRtpPacket rtpPacket, @NotNull PayloadType payloadType)
    {
        byte[] buf = rtpPacket.getBuffer();
        int payloadOff = rtpPacket.getPayloadOffset(),
                payloadLen = rtpPacket.getPayloadLength();

        if (payloadType instanceof Vp8PayloadType)
        {
            return org.jitsi.impl.neomedia.codec.video.vp8.DePacketizer
                    .isKeyFrame(buf, payloadOff, payloadLen);
        }
        else if (payloadType instanceof H264PayloadType)
        {
            return org.jitsi.impl.neomedia.codec.video.h264.DePacketizer
                    .isKeyFrame(buf, payloadOff, payloadLen);
        }
        else if (payloadType instanceof Vp9PayloadType)
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
     * The <tt>Logger</tt> used by the
     * <tt>GenericAdaptiveTrackProjectionContext</tt> class and its instances to
     * log debug information.
     */
    private final Logger logger;

    private final long ssrc;

    /**
     * Raised when a track has been resumed (after being suspended).
     */
    private boolean needsKeyframe = true;

    /**
     * Useful to determine whether a packet is a "keyframe".
     */
    private final PayloadType payloadType;

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
     * Ctor.
     *
     * @param payloadType the media format to expect
     * @param rtpState the RTP state (i.e. seqnum, timestamp to start with, etc).
     */
    GenericAdaptiveTrackProjectionContext(
            @NotNull PayloadType payloadType,
            @NotNull RtpState rtpState,
            @NotNull Logger parentLogger)
    {
        this.payloadType = payloadType;
        this.ssrc = rtpState.ssrc;
        this.maxDestinationSequenceNumber = rtpState.maxSequenceNumber;
        this.maxDestinationTimestamp = rtpState.maxTimestamp;
        this.logger = parentLogger.createChildLogger(GenericAdaptiveTrackProjectionContext.class.getName());
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
    accept(@NotNull VideoRtpPacket rtpPacket, int incomingIndex, int targetIndex)
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
            if (isKeyframe(rtpPacket, payloadType))
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
                sequenceNumberDelta
                    = RtpUtils.getSequenceNumberDelta(
                            destinationSequenceNumber,
                            sourceSequenceNumber);

                logger.debug(() -> "delta ssrc=" + rtpPacket.getSsrc()
                    + ",src_sequence=" + sourceSequenceNumber
                    + ",dst_sequence=" + destinationSequenceNumber
                    + ",max_sequence=" + maxDestinationSequenceNumber
                    + ",delta=" + sequenceNumberDelta);

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
                = RtpUtils.applySequenceNumberDelta(
                        sourceSequenceNumber, sequenceNumberDelta);

            long destinationTimestamp
                = RtpUtils.applyTimestampDelta(
                        rtpPacket.getTimestamp(), timestampDelta);

            if (RtpUtils.isOlderSequenceNumberThan(
                maxDestinationSequenceNumber, destinationSequenceNumber))
            {
                maxDestinationSequenceNumber = destinationSequenceNumber;
            }

            if (RtpUtils.isNewerTimestampThan(
                destinationSequenceNumber, maxDestinationTimestamp))
            {
                maxDestinationTimestamp = destinationTimestamp;
            }

                logger.debug(() -> "accept ssrc=" + rtpPacket.getSsrc()
                + ",src_sequence=" + sourceSequenceNumber
                + ",dst_sequence=" + destinationSequenceNumber
                + ",max_sequence=" + maxDestinationSequenceNumber);
        }
        else
        {
            logger.debug(() -> "reject ssrc=" + rtpPacket.getSsrc()
                + ",src_sequence=" + sourceSequenceNumber);
        }

        return accept;
    }

    /**
     * Initializes {@link #timestampDelta} if it hasn't been initialized
     * already.
     * @param sourceTimestamp
     */
    private void maybeInitializeTimestampDelta(long sourceTimestamp)
    {
        if (timestampDeltaInitialized)
        {
            return;
        }

        if (RtpUtils.isNewerTimestampThan(
            maxDestinationSequenceNumber, sourceTimestamp))
        {
            long destinationTimestamp =
                    RtpUtils.applyTimestampDelta(maxDestinationTimestamp, 3000);

            timestampDelta
                = RtpUtils.getTimestampDiff(
                        destinationTimestamp, sourceTimestamp);
        }

        timestampDeltaInitialized = true;
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
     * @param incomingPacketCache the packet cache to pull piggy-backed
     * packets from. It can be left null because piggybacking is not
     * implemented.
     * @return {@link #EMPTY_PACKET_ARR}
     */
    @Override
    public VideoRtpPacket[] rewriteRtp(
        @NotNull VideoRtpPacket rtpPacket, PacketCache incomingPacketCache)
    {
        int sourceSequenceNumber = rtpPacket.getSequenceNumber();
        int destinationSequenceNumber
            = RtpUtils.applySequenceNumberDelta(
                    sourceSequenceNumber, sequenceNumberDelta);

        if (sourceSequenceNumber != destinationSequenceNumber)
        {
            rtpPacket.setSequenceNumber(destinationSequenceNumber);
        }

        long sourceTimestamp = rtpPacket.getTimestamp();
        long destinationTimestamp
            = RtpUtils.applyTimestampDelta(sourceTimestamp, timestampDelta);

        if (sourceTimestamp != destinationTimestamp)
        {
            rtpPacket.setTimestamp(destinationTimestamp);
        }

        logger.debug(() -> "rewrite ssrc=" + rtpPacket.getSsrc()
            + ",src_sequence=" + sourceSequenceNumber
            + ",dst_sequence=" + destinationSequenceNumber
            + ",max_sequence=" + maxDestinationSequenceNumber);

        return EMPTY_PACKET_ARR;
    }

    /**
     * If the first RTCP packet of the compound RTCP packet that is specified as
     * a parameter is an SR, then this method updates the transmitted bytes and
     * transmitted packets of that first SR.
     *
     * @param rtcpSrPacket the compound RTCP packet to rewrite.
     * @return true.
     */
    @Override
    public boolean rewriteRtcp(@NotNull RtcpSrPacket rtcpSrPacket)
    {
        return true;
    }

    @Override
    public RtpState getRtpState()
    {
        return new RtpState(
                ssrc, maxDestinationSequenceNumber, maxDestinationTimestamp);
    }

    @Override
    public PayloadType getPayloadType()
    {
        return payloadType;
    }

    /**
     * {@inheritDoc}
     * @return
     */
    @Override
    public JSONObject getDebugState()
    {
        JSONObject debugState = new JSONObject();
        debugState.put(
                "class",
                GenericAdaptiveTrackProjectionContext.class.getSimpleName());
        debugState.put("TODO", "export more state (or refactor)");

        return debugState;
    }
}
