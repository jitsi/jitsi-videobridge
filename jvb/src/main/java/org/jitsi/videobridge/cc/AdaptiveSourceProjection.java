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
import org.jitsi.nlj.*;
import org.jitsi.nlj.rtp.*;
import org.jitsi.nlj.rtp.codec.av1.*;
import org.jitsi.nlj.rtp.codec.vp8.*;
import org.jitsi.nlj.rtp.codec.vp9.*;
import org.jitsi.rtp.rtcp.*;
import org.jitsi.utils.logging.*;
import org.jitsi.utils.logging2.Logger;
import org.jitsi.videobridge.cc.av1.*;
import org.jitsi.videobridge.cc.vp8.*;
import org.jitsi.videobridge.cc.vp9.*;
import org.json.simple.*;

import java.lang.*;
import java.util.*;

/**
 * Filters the packets coming from a specific {@link MediaSourceDesc}
 * based on the currently forwarded subjective quality index. It's also taking
 * care of upscaling and downscaling. It is also responsible for rewriting the
 * forwarded packets so that the the quality switches are transparent from the
 * receiver. See svc.md in the doc folder for more details.
 *
 * XXX A projection is a "function" of taking an element of one space and
 * somehow putting it on another space. This is what we're doing here, we're
 * taking a source of the ingress and we're projecting it to the egress,
 * depending on the layers/encodings of the input source and how these encodings are
 * implemented (which depends on the codec that is used).
 *
 * Instances of this class are thread-safe.
 *
 * @author George Politis
 */
public class AdaptiveSourceProjection
{
    /**
     * The <tt>Logger</tt> used by the <tt>AdaptiveSourceProjection</tt> class
     * and its instances for logging output.
     */
    private final Logger logger;

    /**
     * The main SSRC of the source (if simulcast is used, this is the SSRC
     * of the low-quality layer). We use it as the SSRC of the source projection
     * and also request keyframes from this SSRC.
     */
    private final long targetSsrc;

    private final DiagnosticContext diagnosticContext;

    /**
     * The payload specific source projection context that's responsible for
     * rewriting the packets of a projected source.
     *
     * XXX The more general scheme and more correct approach would be to have
     * the adaptive source projection manage a context per payload type. The
     * intention was to implement the more general approach but, at the time of
     * this righting, we have no use case for that right now nor an easy way to
     * test this dynamic payload type changes. So practically what happens is
     * that we create the context once, and it remains the same throughout the
     * life of the this instance. It's not really possible to simplify and make
     * the context final (=> create it during construction) because we need a
     * payload type (could be VP9, could be H264, could be VP8) so it has to be
     * created on packet arrival.
     */
    private AdaptiveSourceProjectionContext context;

    /**
     * The target quality index for this source projection.
     */
    private int targetIndex = RtpLayerDesc.SUSPENDED_INDEX;

    /**
     * The map for persistent states.
     * Currently, we only expect it for AV1.
     */
    private final HashMap<Class<? extends AdaptiveSourceProjectionContext>, Object> persistentStates = new HashMap<>();

    /**
     * Ctor.
     *
     * @param source the {@link MediaSourceDesc} that owns the packets
     * that this instance filters.
     */
    public AdaptiveSourceProjection(
        @NotNull DiagnosticContext diagnosticContext,
        @NotNull MediaSourceDesc source,
        Runnable keyframeRequester,
        Logger parentLogger
    )
    {
        targetSsrc = source.getPrimarySSRC();
        this.diagnosticContext = diagnosticContext;
        this.logger = parentLogger.createChildLogger(AdaptiveSourceProjection.class.getName(),
            Map.of("targetSsrc", Long.toString(targetSsrc),
                "srcEpId", Objects.toString(source.getOwner(), ""),
                "srcName", source.getSourceName()
                ));
        this.keyframeRequester = keyframeRequester;
    }

    /**
     * Sets the target index value for this source projection.
     */
    public void setTargetIndex(int value)
    {
        targetIndex = value;
    }

    /**
     * The callback we'll invoke when we want to request a keyframe for a stream.
     */
    private final Runnable keyframeRequester;

    /**
     * Determines whether an RTP packet needs to be accepted or not.
     *
     * @param packetInfo packet info for the video RTP packet to determine
     * whether to accept or not.
     * @return true if the packet is accepted, false otherwise.
     */
    public boolean accept(@NotNull PacketInfo packetInfo)
    {
        VideoRtpPacket videoRtpPacket = packetInfo.packetAs();
        AdaptiveSourceProjectionContext contextCopy = getContext(videoRtpPacket);
        if (contextCopy == null)
        {
            return false;
        }

        // XXX We want to let the context know that the stream has been
        // suspended so that it can raise the needsKeyframe flag and also allow
        // it to compute a sequence number delta when the target becomes > -1.

        if (videoRtpPacket.getEncodingId() == RtpLayerDesc.SUSPENDED_ENCODING_ID)
        {
            logger.warn(
                "Dropping an RTP packet, because egress was unable to find " +
                "an associated layer/encoding. rtpPacket=" + videoRtpPacket);
            return false;
        }

        int targetIndexCopy = targetIndex;
        boolean accept = contextCopy.accept(packetInfo, targetIndexCopy);

        // We check if the context needs a keyframe regardless of whether or not
        // the packet was accepted.
        //
        // XXX Upon reading this for the first time, one may think it's
        // sufficient to only check for needing a key frame if the packet wasn't
        // accepted. But this wouldn't be enough, as we may be accepting packets
        // of low-quality, while we wish to switch to high-quality.
        if (contextCopy.needsKeyframe()
            && targetIndexCopy > RtpLayerDesc.SUSPENDED_INDEX)
        {
            keyframeRequester.run();
        }

        return accept;
    }

    /**
     * Gets or creates the adaptive source projection context that corresponds to
     * the parsed class of the RTP packet that is specified as a parameter. If
     * the payload type is different from the appropriate one for the context, then a
     * new adaptive source projection context is created that is appropriate for
     * the new payload type.
     *
     * We make no attempt for thread safety, assuming no concurrent access.
     *
     * @param rtpPacket the RTP packet of the adaptive source projection context
     * to get or create.
     * @return the adaptive source projection context that corresponds to
     * the payload type of the RTP packet that is specified as a parameter.
     */
    private AdaptiveSourceProjectionContext getContext(@NotNull VideoRtpPacket rtpPacket)
    {
        int payloadType = rtpPacket.getPayloadType();

        if (rtpPacket instanceof Vp8Packet)
        {
            // Context switch between VP8 simulcast and VP8 non-simulcast (sort
            // of pretend that they're different codecs).
            //
            // HACK: When simulcast is activated, we also get temporal
            // scalability, conversely if temporal scalability is disabled
            // then simulcast is disabled.

            /* Check whether this stream is projectable by the VP8AdaptiveSourceProjectionContext. */
            boolean projectable = ((Vp8Packet)rtpPacket).getHasTemporalLayerIndex() &&
                ((Vp8Packet)rtpPacket).getHasPictureId();

            if (projectable
                && !(context instanceof VP8AdaptiveSourceProjectionContext))
            {
                // context switch
                RtpState rtpState = getRtpState();
                logger.debug(() -> "adaptive source projection " +
                    (context == null ? "creating new" : "changing to") +
                    " VP8 context for source packet ssrc " + rtpPacket.getSsrc());
                context = new VP8AdaptiveSourceProjectionContext(
                    diagnosticContext, rtpState, logger);
            }
            else if (!projectable
                && (!(context instanceof GenericAdaptiveSourceProjectionContext) ||
                    ((GenericAdaptiveSourceProjectionContext)context).getPayloadType() != payloadType))
            {
                RtpState rtpState = getRtpState();
                // context switch
                logger.debug(() -> {
                    boolean hasTemporalLayer = ((Vp8Packet)rtpPacket).getHasTemporalLayerIndex();
                    boolean hasPictureId = ((Vp8Packet)rtpPacket).getHasPictureId();
                    return "adaptive source projection " +
                        (context == null ? "creating new" : "changing to") +
                        " generic context for non-scalable VP8 payload " +
                        " (packet is " + rtpPacket.getClass().getSimpleName() +
                        ", ssrc " + rtpPacket.getSsrc() +
                        ", hasTL=" + hasTemporalLayer + ", hasPID=" + hasPictureId + ")";
                });
                context = new GenericAdaptiveSourceProjectionContext(payloadType, rtpState, logger);
            }

            // no context switch
            return context;
        }
        else if (rtpPacket instanceof Vp9Packet)
        {
            if (!(context instanceof Vp9AdaptiveSourceProjectionContext))
            {
                // context switch
                RtpState rtpState = getRtpState();
                logger.debug(() -> "adaptive source projection " +
                    (context == null ? "creating new" : "changing to") +
                    " VP9 context for source packet ssrc " + rtpPacket.getSsrc());
                context = new Vp9AdaptiveSourceProjectionContext(
                    diagnosticContext, rtpState, logger);
            }

            return context;
        }
        else if (rtpPacket instanceof Av1DDPacket)
        {
            if (!(context instanceof Av1DDAdaptiveSourceProjectionContext))
            {
                // context switch
                RtpState rtpState = getRtpState();
                logger.debug(() -> "adaptive source projection " +
                    (context == null ? "creating new" : "changing to") +
                    " AV1 DD context for source packet ssrc " + rtpPacket.getSsrc());
                context = new Av1DDAdaptiveSourceProjectionContext(
                        diagnosticContext, rtpState,
                        persistentStates.get(Av1DDAdaptiveSourceProjectionContext.class),
                        logger);
            }

            return context;
        }
        else
        {
            if (!(context instanceof GenericAdaptiveSourceProjectionContext) ||
                ((GenericAdaptiveSourceProjectionContext)context).getPayloadType() != payloadType)
            {
                RtpState rtpState = getRtpState();
                logger.debug(() -> "adaptive source projection " +
                    (context == null ? "creating new" : "changing to") +
                    " generic context for payload type " + rtpPacket.getPayloadType());
                context = new GenericAdaptiveSourceProjectionContext(payloadType, rtpState, logger);
            }
            return context;
        }
    }

    /**
     * Gets the {@link RtpState}.
     */
    private @NotNull RtpState getRtpState()
    {
        if (context == null)
        {
            // TODO If '1' are the starting seq number and timestamp, should
            //  we use random values?
            return new RtpState(
                targetSsrc,
                1 /* maxSequenceNumber */,
                1 /* maxTimestamp */) ;
        }
        else
        {
            savePersistentState();
            return context.getRtpState();
        }
    }

    private void savePersistentState()
    {
        if (context == null)
        {
            return;
        }
        Object state = context.getPersistentState();
        if (state != null)
        {
            if (context.getClass() != Av1DDAdaptiveSourceProjectionContext.class)
            {
                logger.warn("Got unexpected context persistent state from class " +
                        context.getClass().getSimpleName());
            }
            persistentStates.put(context.getClass(), state);
        }
        else if (persistentStates.get(context.getClass()) != null)
        {
            persistentStates.remove(context.getClass());
        }
    }

    /**
     * Rewrites an RTP packet for projection.
     *
     * @param packetInfo the RTP packet to rewrite.
     */
   public void rewriteRtp(@NotNull PacketInfo packetInfo)
        throws RewriteException
    {
        AdaptiveSourceProjectionContext contextCopy = context;
        if (contextCopy != null)
        {
            contextCopy.rewriteRtp(packetInfo);
        }
    }

    /**
     * Rewrites an RTCP packet.
     *
     * @param rtcpSrPacket the RTCP SR packet to rewrite.
     * @return true to let the RTCP packet through, false to drop.
     */
    public boolean rewriteRtcp(@NotNull RtcpSrPacket rtcpSrPacket)
    {
        AdaptiveSourceProjectionContext contextCopy = context;
        if (contextCopy == null)
        {
            return true;
        }

        return contextCopy.rewriteRtcp(rtcpSrPacket);
    }

    /**
     * @return the SSRC of the source projection.
     */
    public long getTargetSsrc()
    {
        return targetSsrc;
    }

    /**
     * Gets a JSON representation of the parts of this object's state that
     * are deemed useful for debugging.
     */
    @SuppressWarnings("unchecked")
    public JSONObject getDebugState(@NotNull DebugStateMode mode)
    {
        JSONObject debugState = new JSONObject();

        debugState.put("targetSsrc", targetSsrc);
        AdaptiveSourceProjectionContext contextCopy = context;
        if (mode == DebugStateMode.FULL)
        {
            debugState.put(
                    "context",
                    contextCopy == null ? null : contextCopy.getDebugState());
        }
        else if (mode == DebugStateMode.STATS)
        {
            debugState.put(
                    "context",
                    contextCopy == null ? "null" : contextCopy.getClass().getSimpleName());
        }
        debugState.put("targetIndex", targetIndex);

        return debugState;
    }
}
