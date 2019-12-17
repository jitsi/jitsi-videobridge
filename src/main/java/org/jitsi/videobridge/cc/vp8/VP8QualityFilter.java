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
import org.jitsi.nlj.rtp.*;
import org.jitsi.utils.logging2.*;
import org.jitsi_modified.impl.neomedia.codec.video.vp8.*;
import org.json.simple.*;

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
    private final Logger logger;

    /**
     * The default maximum frequency (in millis) at which the media engine
     * generates key frame.
     */
    private static final int MIN_KEY_FRAME_WAIT_MS = 300;

    /**
     * The HD, SD, LD and suspended spatial/quality layer IDs.
     */
    private static final int SUSPENDED_LAYER_ID = -1;

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
     * (that's specified as an argument to the
     * {@link #acceptFrame(VP8Frame, int, int, long)} method) is set to something
     * different, in which case we set {@link #needsKeyframe} equal to true and
     * update.
     */
    private int internalSpatialLayerIdTarget = SUSPENDED_LAYER_ID;

    /**
     * The spatial/quality layer ID that we're currently forwarding. -1
     * indicates that we're not forwarding anything. Reading/writing of this
     * field is synchronized on this instance.
     */
    private int currentSpatialLayerId = SUSPENDED_LAYER_ID;

    public VP8QualityFilter(Logger parentLogger)
    {
        this.logger = parentLogger.createChildLogger(VP8QualityFilter.class.getName());
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
     * Determines whether to accept or drop a VP8 frame.
     *
     * Note that, at the time of this writing, there's no practical need for a
     * synchronized keyword because there's only one thread accessing this
     * method at a time.
     *
     * @param frame  the VP8 frame.
     * @param incomingIndex the quality index of the incoming RTP packet
     * @param externalTargetIndex the target quality index that the user of this
     * instance wants to achieve.
     * @param receivedMs the current time (in millis)
     * @return true to accept the VP8 frame, otherwise false.
     */
    synchronized boolean acceptFrame(
        @NotNull VP8Frame frame,
        int incomingIndex,
        int externalTargetIndex, long receivedMs)
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

        int temporalLayerIdOfFrame = frame.getTemporalLayer();

        if (temporalLayerIdOfFrame < 0)
        {
            // This is strange; If temporal scalability is enabled the TID is
            // included in every packet (keyframe, interframe, continuation). So
            // it seems like the encoder has disabled (or maybe it has never
            // enabled?) temporal scalability.. For now we will pretend that
            // this is the base temporal layer.
            temporalLayerIdOfFrame = 0;
        }

        int spatialLayerId = getSpatialLayerId(incomingIndex);
        if (frame.isKeyframe())
        {
            logger.debug(() -> "Quality filter got keyframe for stream "
                    + frame.getSsrc());
            return acceptKeyframe(spatialLayerId, receivedMs);
        }
        else if (currentSpatialLayerId > SUSPENDED_LAYER_ID)
        {
            if (isOutOfSwitchingPhase(receivedMs) && isPossibleToSwitch(spatialLayerId))
            {
                // XXX(george) i've noticed some "rogue" base layer keyframes
                // that trigger this. what happens is the client sends a base
                // layer key frame, the bridge switches to that layer because
                // for all it knows it may be the only keyframe sent by the
                // client engine. then the bridge notices that packets from the
                // higher quality streams are flowing and execution ends-up
                // here. it is a mystery why the engine is "leaking" base layer
                // key frames
                needsKeyframe = true;
            }

            if (spatialLayerId != currentSpatialLayerId)
            {
                // for non-keyframes, we can't route anything but the current spatial layer
                return false;
            }

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
     * Returns a boolean that indicates whether we are in layer switching phase
     * or not.
     *
     * @param receivedMs the time the latest frame was received (in millis)
     * @return true if we're in layer switching phase, false otherwise.
     */
    private synchronized boolean isOutOfSwitchingPhase(long receivedMs)
    {
        long deltaMs = receivedMs - mostRecentKeyframeGroupArrivalTimeMs;
        return deltaMs > MIN_KEY_FRAME_WAIT_MS;
    }

    /**
     * @return true if it looks like we can re-scale (see implementation of
     * method for specific details).
     */
    private synchronized boolean isPossibleToSwitch(int spatialLayerId)
    {
        if (spatialLayerId == -1)
        {
            // We failed to resolve the spatial/quality layer of the packet.
            return false;
        }

        if (spatialLayerId > currentSpatialLayerId
            && currentSpatialLayerId < internalSpatialLayerIdTarget)
        {
            // It looks like upscaling is possible.
            return true;
        }
        else if (spatialLayerId < currentSpatialLayerId
            && currentSpatialLayerId > internalSpatialLayerIdTarget)
        {
            // It looks like downscaling is possible.
            return true;
        }
        else
        {
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
     * @param receivedMs the time the frame was received (in millis)
     * @return true to accept the VP8 keyframe, otherwise false.
     */
    private synchronized boolean acceptKeyframe(
        int spatialLayerIdOfKeyframe, long receivedMs)
    {
        // This branch writes the {@link #currentSpatialLayerId} and it
        // determines whether or not we should switch to another simulcast
        // stream.
        if (spatialLayerIdOfKeyframe < 0)
        {
            // something went terribly wrong, normally we should be able to
            // extract the layer id from a keyframe.
            logger.error("unable to get layer id from keyframe");
            return false;
        }

        logger.debug(() -> "Received a keyframe of spatial layer: "
                    + spatialLayerIdOfKeyframe);


        // The keyframe request has been fulfilled at this point, regardless of
        // whether we'll be able to achieve the internalSpatialLayerIdTarget.
        needsKeyframe = false;

        if (isOutOfSwitchingPhase(receivedMs))
        {
            // During the switching phase we always project the first
            // keyframe because it may very well be the only one that we
            // receive (i.e. the endpoint is sending low quality only). Then
            // we try to approach the target.

            mostRecentKeyframeGroupArrivalTimeMs = receivedMs;

            logger.debug(() -> "First keyframe in this kf group " +
                "currentSpatialLayerId: " + spatialLayerIdOfKeyframe +
                ". Target is " + internalSpatialLayerIdTarget);

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
                logger.debug(() -> "Upscaling to spatial layer "
                    + spatialLayerIdOfKeyframe
                    + ". The target is " + internalSpatialLayerIdTarget);
                return true;
            }
            else if (spatialLayerIdOfKeyframe <= internalSpatialLayerIdTarget
                && internalSpatialLayerIdTarget < currentSpatialLayerId)
            {
                // downscale case
                currentSpatialLayerId = spatialLayerIdOfKeyframe;
                logger.debug(() -> " Downscaling to spatial layer "
                    + spatialLayerIdOfKeyframe + ". The target is + "
                    + internalSpatialLayerIdTarget);
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

    /**
     * Gets a JSON representation of the parts of this object's state that
     * are deemed useful for debugging.
     */
    public JSONObject getDebugState()
    {
        JSONObject debugState = new JSONObject();
        debugState.put(
                "mostRecentKeyframeGroupArrivalTimeMs",
                mostRecentKeyframeGroupArrivalTimeMs);
        debugState.put("needsKeyframe", needsKeyframe);
        debugState.put(
                "internalSpatialLayerIdTarget",
                internalSpatialLayerIdTarget);
        debugState.put("currentSpatialLayerId", currentSpatialLayerId);


        return debugState;
    }
}
