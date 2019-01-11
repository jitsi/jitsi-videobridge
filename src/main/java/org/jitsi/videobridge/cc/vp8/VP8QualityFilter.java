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
import org.jitsi.impl.neomedia.codec.video.vp8.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.util.*;

/**
 * This class is responsible for dropping VP8 simulcast/svc packets based on
 * their quality, i.e. packets that correspond to qualities that are above a
 * given quality target. Instances of this class are thread-safe.
 *
 * @author George Politis
 */
class VP8QualityFilter
{
    /**
     * The {@link Logger} to be used by this instance to print debug
     * information.
     */
    private static final Logger
        logger = Logger.getLogger(VP8QualityFilter.class);

    /**
     * The default maximum frequency (in millis) at which the media engine
     * generates key frame.
     */
    private static final int MIN_KEY_FRAME_WAIT_MS = 300;

    /**
     * Constants that define the minimum heights that a VP8 stream needs to have
     * in order to be considered HD, SD or LD.
     */
    private static final int MIN_HD_HEIGHT = 540, MIN_SD_HEIGHT = 360;

    /**
     * The HD, SD, LD and suspended spatial/quality layer IDs.
     */
    private static final int HD_LAYER_ID = 2, SD_LAYER_ID = 1,
        LD_LAYER_ID = 0, SUSPENDED_LAYER_ID = -1;

    /**
     * Holds the arrival time (in millis) of the most recent keyframe group.
     * Reading/writing of this field is synchronized on this instance.
     */
    private long mostRecentKeyframeGroupArrivalTimeMs = -1L;

    /**
     * A boolean flag that indicates whether a simulcast switch is pending. This
     * condition is equivalent to:
     *
     * internalSpatialLayerIdTarget != externalSpatialLayerIdTarget.
     *
     * Reading/writing of this field is synchronized on this instance.
     */
    private boolean needsKeyframe = false;

    /**
     * The spatial/quality layer id that this instance tries to achieve. Upon
     * receipt of a packet, we check whether externalSpatialLayerIdTarget
     * (that's specified as an argument to the {@link #acceptFrame(RawPacket, int)}
     * method) is set to something different, in which case we set
     * {@link #needsKeyframe} equal to true and update.
     */
    private int internalSpatialLayerIdTarget = SUSPENDED_LAYER_ID;

    /**
     * The spatial/quality layer ID that we're currently forwarding. -1
     * indicates that we're not forwarding anything. Reading/writing of this
     * field is synchronized on this instance.
     */
    private int currentSpatialLayerId = SUSPENDED_LAYER_ID;

    /**
     * Gets the spatial/quality layer ID of a packet. The specified packet MUST
     * be a VP8 keyframe because the frame height, which allows us to map the
     * different simulcast layers into spatial/quality layer IDs, are found in
     * the keyframe header.
     *
     * @param firstPacketOfKeyframe the first packet of a keyframe to map/get
     * its spatial/quality layer id.
     * @return the spatial/quality layer id of the packet.
     */
    private static int getSpatialLayerIndex(
        @NotNull RawPacket firstPacketOfKeyframe)
    {
        byte[] buf = firstPacketOfKeyframe.getBuffer();
        int payloadOffset = firstPacketOfKeyframe.getPayloadOffset(),
            payloadLen = firstPacketOfKeyframe.getPayloadLength(),
            payloadHeaderLen = 3;

        int payloadDescriptorLen = DePacketizer
            .VP8PayloadDescriptor.getSize(buf, payloadOffset, payloadLen);

        int height = DePacketizer.VP8KeyframeHeader.getHeight(
            buf, payloadOffset + payloadDescriptorLen + payloadHeaderLen);

        if (height >= MIN_HD_HEIGHT)
        {
            return HD_LAYER_ID;
        }
        else if (height >= MIN_SD_HEIGHT)
        {
            return SD_LAYER_ID;
        }
        else if (height > -1)
        {
            return LD_LAYER_ID;
        }
        else
        {
            // Some error occurred.
            return -1;
        }
    }

    /**
     * @return true if a the target spatial/quality layer id has changed and a
     * keyframe hasn't been received yet, false otherwise.
     */
    boolean needsKeyframe()
    {
        return needsKeyframe;
    }

    /**
     * Determines whether to accept or drop a VP8 frame. The first packet of a
     * VP8 frame is required because this is were the spatial and/or temporal
     * layer id are found.
     *
     * Note that, at the time of this writing, there's no practical need for a
     * synchronized keyword because there's only one thread accessing this
     * method at a time.
     *
     * @param firstPacketOfFrame the first packet of the VP8 frame.
     * @param externalTargetIndex the target quality index that the user of this
     * instance wants to achieve.
     * @return true to accept the VP8 frame, otherwise false.
     */
    synchronized boolean acceptFrame(
        @NotNull RawPacket firstPacketOfFrame, int externalTargetIndex)
    {
        // We make local copies of the externalTemporalLayerIdTarget and the
        // externalSpatialLayerIdTarget (as they may be updated by some other
        // thread).
        int externalTemporalLayerIdTarget
            = getTemporalLayerId(externalTargetIndex);
        int externalSpatialLayerIdTarget
            = getSpatialLayerId(externalTargetIndex);

        if (externalSpatialLayerIdTarget != internalSpatialLayerIdTarget)
        {
            // The externalSpatialLayerIdTarget has changed since accept last
            // run; perhaps we should request a keyframe.
            internalSpatialLayerIdTarget = externalSpatialLayerIdTarget;
            if (externalSpatialLayerIdTarget > SUSPENDED_LAYER_ID)
            {
                needsKeyframe = true;
            }
        }

        if (externalSpatialLayerIdTarget < 0
            || externalTemporalLayerIdTarget < 0)
        {
            // We stop forwarding immediately. We will need a keyframe in order
            // to resume.
            currentSpatialLayerId = SUSPENDED_LAYER_ID;
            return false;
        }

        byte[] buf = firstPacketOfFrame.getBuffer();
        int payloadOff = firstPacketOfFrame.getPayloadOffset(),
            payloadLen = firstPacketOfFrame.getPayloadLength();
        int temporalLayerIdOfFrame = DePacketizer.VP8PayloadDescriptor
            .getTemporalLayerIndex(buf, payloadOff, payloadLen);

        if (temporalLayerIdOfFrame < 0)
        {
            // This is strange; If temporal scalability is enabled the TID is
            // included in every packet (keyframe, interframe, continuation). So
            // it seems like the encoder has disabled (or maybe it has never
            // enabled?) temporal scalability.. For now we will pretend that
            // this is the base temporal layer.
            temporalLayerIdOfFrame = 0;
        }

        if (DePacketizer.isKeyFrame(buf, payloadOff, payloadLen))
        {
            return acceptKeyframe(firstPacketOfFrame);
        }
        else if (currentSpatialLayerId > SUSPENDED_LAYER_ID)
        {
            // This branch reads the {@link #currentSpatialLayerId} and it
            // filters packets based on their temporal layer.

            if (currentSpatialLayerId > externalSpatialLayerIdTarget)
            {
                // pending downscale, decrease the frame rate until we
                // downscale.
                return temporalLayerIdOfFrame < 1;
            }
            else if (currentSpatialLayerId < externalSpatialLayerIdTarget)
            {
                // pending upscale, increase the frame rate until we upscale.
                return true;
            }
            else
            {
                // The currentSpatialLayerId matches exactly the target
                // currentSpatialLayerId.
                return temporalLayerIdOfFrame <= externalTemporalLayerIdTarget;
            }
        }
        else
        {
            // In this branch we're not processing a keyframe and the
            // currentSpatialLayerId is in suspended state, which means we need
            // a keyframe to start streaming again. Reaching this point also
            // means that we want to forward something (because both
            // externalSpatialLayerIdTarget and externalTemporalLayerIdTarget
            // are greater than 0) so we set the request keyframe flag.

            // assert needsKeyframe == true;
            return false;
        }
    }

    /**
     * Determines whether to accept or drop a VP8 keyframe. This method updates
     * the spatial layer id.
     *
     * Note that, at the time of this writing, there's no practical need for a
     * synchronized keyword because there's only one thread accessing this
     * method at a time.
     *
     * @param firstPacketOfKeyframe the first packet of a VP8 keyframe.
     * @return true to accept the VP8 keyframe, otherwise false.
     */
    private synchronized boolean acceptKeyframe(
        @NotNull RawPacket firstPacketOfKeyframe)
    {
        // This branch writes the {@link #currentSpatialLayerId} and it
        // determines whether or not we should switch to another simulcast
        // stream.

        int spatialLayerIdOfKeyframe
            = getSpatialLayerIndex(firstPacketOfKeyframe);
        if (spatialLayerIdOfKeyframe < 0)
        {
            // something went terribly wrong, normally we should be able to
            // extract the layer id from a keyframe.
            return false;
        }

        if (logger.isDebugEnabled())
        {
            logger.debug(
                "Received a keyframe of spatial layer: " + spatialLayerIdOfKeyframe);

        }

        // The keyframe request has been fulfilled at this point, regardless of
        // whether we'll be able to achieve the internalSpatialLayerIdTarget.
        needsKeyframe = false;

        long nowMs = System.currentTimeMillis();
        long deltaMs = nowMs - mostRecentKeyframeGroupArrivalTimeMs;
        if (deltaMs > MIN_KEY_FRAME_WAIT_MS)
        {
            // During the switching phase we always project the first
            // keyframe because it may very well be the only one that we
            // receive (i.e. the endpoint is sending low quality only). Then
            // we try to approach the target.

            mostRecentKeyframeGroupArrivalTimeMs = nowMs;

            if (logger.isDebugEnabled())
            {
                logger.debug("First keyframe in this kf group " +
                    "currentSpatialLayerId: " + spatialLayerIdOfKeyframe +
                    ". Target is " + internalSpatialLayerIdTarget);
            }

            if (spatialLayerIdOfKeyframe <= internalSpatialLayerIdTarget)
            {
                // If the target is 180p and the first keyframe of a group of
                // keyframes is a 720p keyframe we don't project it. If we
                // receive a 720p keyframe, we know that there MUST be a 180p
                // keyframe shortly after.
                currentSpatialLayerId = spatialLayerIdOfKeyframe;
                return true;
            }
            else
            {
                return false;
            }
        }
        else
        {
            // We're within the 300ms window since the reception of the
            // first key frame of a key frame group, let's check whether an
            // upscale/downscale is possible.

            if (currentSpatialLayerId <= spatialLayerIdOfKeyframe
                && spatialLayerIdOfKeyframe <= internalSpatialLayerIdTarget)
            {
                // upscale or current quality case
                currentSpatialLayerId = spatialLayerIdOfKeyframe;
                if (logger.isDebugEnabled())
                {
                    logger.debug("Upscaling to spatial layer "
                        + spatialLayerIdOfKeyframe
                        + ". The target is " + internalSpatialLayerIdTarget);
                }
                return true;
            }
            else if (spatialLayerIdOfKeyframe <= internalSpatialLayerIdTarget
                && internalSpatialLayerIdTarget < currentSpatialLayerId)
            {
                // downscale case
                currentSpatialLayerId = spatialLayerIdOfKeyframe;
                if (logger.isDebugEnabled())
                {
                    logger.debug("Downscaling to spatial layer "
                        + spatialLayerIdOfKeyframe + ". The target is + "
                        + internalSpatialLayerIdTarget);
                }
                return true;
            }
            else
            {
                return false;
            }
        }
    }

    /**
     * Small utility method that computes the temporal layer index from the
     * quality index
     * @param index the quality index
     * @return the temporal layer index
     */
    private static int getTemporalLayerId(int index)
    {
        return index > -1 ? index % 3 : -1;
    }

    /**
     * Small utility method that computes the spatial/quality layer index from
     * the quality index
     *
     * FIXME quality has a very specific meaning in video coding. Consider
     * picking another name for our quality index.
     *
     * @param index the quality index
     * @return the spatial/quality index
     */
    private static int getSpatialLayerId(int index)
    {
        return index > -1 ? index / 3 : -1;
    }
}
