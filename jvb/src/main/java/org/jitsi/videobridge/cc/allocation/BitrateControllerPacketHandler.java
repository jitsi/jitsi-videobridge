/*
 * Copyright @ 2020 - present 8x8, Inc.
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
package org.jitsi.videobridge.cc.allocation;

import kotlin.*;
import org.jetbrains.annotations.*;
import org.jitsi.nlj.*;
import org.jitsi.nlj.format.*;
import org.jitsi.nlj.rtp.*;
import org.jitsi.rtp.rtcp.*;
import org.jitsi.utils.*;
import org.jitsi.utils.logging.*;
import org.jitsi.utils.logging2.Logger;
import org.jitsi.videobridge.cc.AdaptiveSourceProjection;
import org.jitsi.videobridge.cc.RewriteException;
import org.jitsi.videobridge.util.*;
import org.json.simple.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

class BitrateControllerPacketHandler
{
    /**
     * The time (in ms) when this instance first transformed any media. This
     * allows to ignore the CC during the early stages of the call and ramp up
     * the send rate faster.
     *
     * NOTE This is only meant to be as a temporary hack and ideally this should
     * be fixed in the CC.
     */
    private long firstMediaMs = -1;

    private final AtomicInteger numDroppedPacketsUnknownSsrc = new AtomicInteger(0);

    private final Map<Byte, PayloadType> payloadTypes = new ConcurrentHashMap<>();

    private final Clock clock;
    private final Logger logger;

    /**
     * The {@link AdaptiveSourceProjection}s that this instance is managing, keyed
     * by the SSRCs of the associated {@link MediaSourceDesc}.
     */
    private final Map<Long, AdaptiveSourceProjection> adaptiveSourceProjectionMap = new ConcurrentHashMap<>();

    private final DiagnosticContext diagnosticContext;
    private final EventEmitter<BitrateController.EventHandler> eventEmitter;

    BitrateControllerPacketHandler(
            Clock clock,
            Logger parentLogger,
            DiagnosticContext diagnosticContext,
            EventEmitter<BitrateController.EventHandler> eventEmitter)
    {
        this.clock = clock;
        this.logger = parentLogger.createChildLogger(BitrateControllerPacketHandler.class.getName());
        this.diagnosticContext = diagnosticContext;
        this.eventEmitter = eventEmitter;
    }

    /**
     * Transforms a video RTP packet.
     * @param packetInfo the video rtp packet
     * @return true if the packet was successfully transformed in place; false if
     * if the given packet is not accepted and should
     * be dropped.
     */
    boolean transformRtp(@NotNull PacketInfo packetInfo)
    {
        VideoRtpPacket videoPacket = (VideoRtpPacket)packetInfo.getPacket();
        if (firstMediaMs == -1)
        {
            firstMediaMs = clock.instant().toEpochMilli();
        }

        Long ssrc = videoPacket.getSsrc();
        AdaptiveSourceProjection adaptiveSourceProjection = adaptiveSourceProjectionMap.get(ssrc);

        if (adaptiveSourceProjection == null)
        {
            return false;
        }

        try
        {
            adaptiveSourceProjection.rewriteRtp(packetInfo);

            // The rewriteRtp operation must not modify the VP8 payload.
            if (PacketInfo.Companion.getENABLE_PAYLOAD_VERIFICATION())
            {
                String expected = packetInfo.getPayloadVerification();
                String actual = videoPacket.getPayloadVerification();
                if (!"".equals(expected) && !expected.equals(actual))
                {
                    logger.warn("Payload unexpectedly modified! Expected: " + expected + ", actual: " + actual);
                }
            }

            return true;
        }
        catch (RewriteException e)
        {
            logger.warn("Failed to rewrite a packet.", e);
            return false;
        }
    }

    /**
     * Defines a packet filter that controls which RTP packets to be written
     * into the {@code Endpoint} that owns this {@link BitrateAllocator}.
     *
     * @param packetInfo that packet for which to decide whether to accept
     * @return <tt>true</tt> to allow the specified packet to be
     * written into the {@code Endpoint} that owns this {@link BitrateAllocator}
     * ; otherwise, <tt>false</tt>
     */
    boolean accept(@NotNull PacketInfo packetInfo)
    {
        VideoRtpPacket videoRtpPacket = packetInfo.packetAs();
        long ssrc = videoRtpPacket.getSsrc();

        AdaptiveSourceProjection adaptiveSourceProjection = adaptiveSourceProjectionMap.get(ssrc);

        if (adaptiveSourceProjection == null)
        {
            logger.debug(() -> "Dropping an RTP packet, because the SSRC has not been signaled:" + ssrc);
            numDroppedPacketsUnknownSsrc.incrementAndGet();
            return false;
        }

        return adaptiveSourceProjection.accept(packetInfo);
    }

    /**
     * Defines a packet filter that controls which RTCP Sender Report
     * packets to be written into the {@code Endpoint} that owns this
     * {@link BitrateAllocator}.
     * </p>
     * Filters out packets that match one of the streams that this
     * {@code BitrateController} manages, but don't match the target SSRC.
     * Allows packets for streams not managed by this {@link BitrateAllocator}.
     *
     * @param rtcpSrPacket that packet for which to decide whether to accept
     * @return <tt>true</tt> to allow the specified packet to be
     * written into the {@code Endpoint} that owns this {@link BitrateAllocator}
     * ; otherwise, <tt>false</tt>
     */
    boolean accept(RtcpSrPacket rtcpSrPacket)
    {
        long ssrc = rtcpSrPacket.getSenderSsrc();

        AdaptiveSourceProjection adaptiveSourceProjection = adaptiveSourceProjectionMap.get(ssrc);

        if (adaptiveSourceProjection == null)
        {
            // This is probably for an audio stream. In any case, if it's for a
            // stream which we are not forwarding it will be stripped off at
            // a later stage (in RtcpSrUpdater).
            return true;
        }

        // We only accept SRs for the SSRC that we're forwarding with.
        return ssrc == adaptiveSourceProjection.getTargetSsrc();
    }

    boolean transformRtcp(RtcpSrPacket rtcpSrPacket)
    {
        long ssrc = rtcpSrPacket.getSenderSsrc();

        AdaptiveSourceProjection adaptiveSourceProjection = adaptiveSourceProjectionMap.get(ssrc);

        return adaptiveSourceProjection != null && adaptiveSourceProjection.rewriteRtcp(rtcpSrPacket);
    }

    /**
     * Utility method that looks-up or creates the adaptive source projection of
     * a source.
     *
     * @param sourceBitrateAllocation the source bitrate allocation
     * @return the adaptive source projection for the source bitrate allocation
     * that is specified as an argument.
     */
    AdaptiveSourceProjection lookupOrCreateAdaptiveSourceProjection(
            SingleSourceAllocation sourceBitrateAllocation)
    {
        MediaSourceDesc source = sourceBitrateAllocation.source;
        if (source == null)
        {
            return null;
        }

        synchronized (adaptiveSourceProjectionMap)
        {
            AdaptiveSourceProjection adaptiveSourceProjection
                    = adaptiveSourceProjectionMap.get(source.getPrimarySSRC());

            if (adaptiveSourceProjection != null)
            {
                return adaptiveSourceProjection;
            }

            RtpEncodingDesc[] rtpEncodings = source.getRtpEncodings();

            if (ArrayUtils.isNullOrEmpty(rtpEncodings))
            {
                return null;
            }

            // XXX the lambda keeps a reference to the sourceBitrateAllocation
            // (a short lived object under normal circumstances) which keeps
            // a reference to the Endpoint object that it refers to. That
            // can cause excessive object retention (i.e. the endpoint is expired
            // but a reference persists in the adaptiveSourceProjectionMap). We're
            // creating local final variables and pass that to the lambda function
            // in order to avoid that.
            final String endpointID = sourceBitrateAllocation.endpointID;
            final long targetSSRC = source.getPrimarySSRC();
            adaptiveSourceProjection
                    = new AdaptiveSourceProjection(
                    diagnosticContext,
                    sourceBitrateAllocation.source,
                    () -> eventEmitter.fireEvent(handler -> {
                        handler.keyframeNeeded(endpointID, targetSSRC);
                        return Unit.INSTANCE;
                    }),
                    payloadTypes,
                    logger);

            logger.debug(() -> "new source projection for " + sourceBitrateAllocation.source);

            // Route all encodings to the specified bitrate controller.
            for (RtpEncodingDesc rtpEncoding: rtpEncodings)
            {
                adaptiveSourceProjectionMap.put(rtpEncoding.getPrimarySSRC(), adaptiveSourceProjection);
            }

            return adaptiveSourceProjection;
        }
    }

    long timeSinceFirstMedia()
    {
        if (firstMediaMs == -1)
        {
            return -1;
        }
        return clock.instant().toEpochMilli() - firstMediaMs;
    }

    Map<Long, AdaptiveSourceProjection> getAdaptiveSourceProjectionMap()
    {
        return adaptiveSourceProjectionMap;
    }

    void addPayloadType(PayloadType payloadType)
    {
        payloadTypes.put(payloadType.getPt(), payloadType);
    }

    @SuppressWarnings("unchecked")
    JSONObject getDebugState()
    {
        JSONObject debugState = new JSONObject();
        debugState.put("numDroppedPacketsUnknownSsrc", numDroppedPacketsUnknownSsrc.intValue());

        JSONObject adaptiveSourceProjectionsJson = new JSONObject();
        for (Map.Entry<Long, AdaptiveSourceProjection> entry : adaptiveSourceProjectionMap.entrySet())
        {
            adaptiveSourceProjectionsJson.put(entry.getKey(), entry.getValue().getDebugState());
        }
        debugState.put("adaptiveSourceProjectionMap", adaptiveSourceProjectionsJson);

        return debugState;
    }
}
