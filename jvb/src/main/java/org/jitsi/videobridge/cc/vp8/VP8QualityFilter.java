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

import edu.umd.cs.findbugs.annotations.*;
import org.jetbrains.annotations.*;
import org.jitsi.nlj.*;
import org.jitsi.utils.logging2.*;
import org.json.simple.*;

import java.lang.SuppressWarnings;
import java.time.*;

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
     * The default maximum frequency at which the media engine
     * generates key frame.
     */
    private static final Duration MIN_KEY_FRAME_WAIT = Duration.ofMillis(300);

    /**
     * The HD, SD, LD and suspended spatial/quality layer IDs.
     */
    private static final int SUSPENDED_ENCODING_ID = -1;

    /**
     * Holds the arrival time of the most recent keyframe group.
     * Reading/writing of this field is synchronized on this instance.
     */
    private Instant mostRecentKeyframeGroupArrivalTime = null;

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
     * The encoding id that this instance tries to achieve. Upon
     * receipt of a packet, we check whether externalSpatialLayerIdTarget
     * (that's specified as an argument to the
     * {@link #acceptFrame(VP8Frame, int, int, Instant)} method) is set to something
     * different, in which case we set {@link #needsKeyframe} equal to true and
     * update.
     */
    private int internalEncodingIdTarget = SUSPENDED_ENCODING_ID;

    /**
     * The encoding layer ID that we're currently forwarding. -1
     * indicates that we're not forwarding anything. Reading/writing of this
     * field is synchronized on this instance.
     */
    private int currentEncodingId = SUSPENDED_ENCODING_ID;

    public VP8QualityFilter(Logger parentLogger)
    {
        this.logger = parentLogger.createChildLogger(VP8QualityFilter.class.getName());
    }

    /**
     * @return true if a the target encoding id has changed and a
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
     * @param receivedTime the current time
     * @return true to accept the VP8 frame, otherwise false.
     */
    synchronized boolean acceptFrame(
        @NotNull VP8Frame frame,
        int incomingIndex,
        int externalTargetIndex, Instant receivedTime)
    {
        // We make local copies of the externalTemporalLayerIdTarget and the
        // externalEncodingTarget (as they may be updated by some other
        // thread).
        int externalTemporalLayerIdTarget
            = RtpLayerDesc.getTidFromIndex(externalTargetIndex);
        int externalEncodingIdTarget
            = RtpLayerDesc.getEidFromIndex(externalTargetIndex);

        if (externalEncodingIdTarget != internalEncodingIdTarget)
        {
            // The externalEncodingIdTarget has changed since accept last
            // run; perhaps we should request a keyframe.
            internalEncodingIdTarget = externalEncodingIdTarget;
            if (externalEncodingIdTarget > SUSPENDED_ENCODING_ID)
            {
                needsKeyframe = true;
            }
        }

        if (externalEncodingIdTarget < 0
            || externalTemporalLayerIdTarget < 0)
        {
            // We stop forwarding immediately. We will need a keyframe in order
            // to resume.
            currentEncodingId = SUSPENDED_ENCODING_ID;
            return false;
        }

        int temporalLayerIdOfFrame = frame.getTemporalLayer();

        if (temporalLayerIdOfFrame < 0)
        {
            // temporal scalability is not enabled. Pretend that
            // this is the base temporal layer.
            temporalLayerIdOfFrame = 0;
        }

        int encodingId = RtpLayerDesc.getEidFromIndex(incomingIndex);
        if (frame.isKeyframe())
        {
            logger.debug(() -> "Quality filter got keyframe for stream "
                    + frame.getSsrc());
            return acceptKeyframe(encodingId, receivedTime);
        }
        else if (currentEncodingId > SUSPENDED_ENCODING_ID)
        {
            if (isOutOfSwitchingPhase(receivedTime) && isPossibleToSwitch(encodingId))
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

            if (encodingId != currentEncodingId)
            {
                // for non-keyframes, we can't route anything but the current encoding
                return false;
            }

            // This branch reads the {@link #currentEncodingId} and it
            // filters packets based on their temporal layer.

            if (currentEncodingId > externalEncodingIdTarget)
            {
                // pending downscale, decrease the frame rate until we
                // downscale.
                return temporalLayerIdOfFrame < 1;
            }
            else if (currentEncodingId < externalEncodingIdTarget)
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
            // externalEncodingIdTarget and externalTemporalLayerIdTarget
            // are greater than 0) so we set the request keyframe flag.

            // assert needsKeyframe == true;
            return false;
        }
    }

    /**
     * Returns a boolean that indicates whether we are in layer switching phase
     * or not.
     *
     * @param receivedTime the time the latest frame was received
     * @return true if we're in layer switching phase, false otherwise.
     */
    private synchronized boolean isOutOfSwitchingPhase(Instant receivedTime)
    {
        if (receivedTime == null)
        {
            return false;
        }
        if (mostRecentKeyframeGroupArrivalTime == null)
        {
            return true;
        }

        Duration delta = Duration.between(mostRecentKeyframeGroupArrivalTime, receivedTime);
        return delta.compareTo(MIN_KEY_FRAME_WAIT) > 0;
    }

    /**
     * @return true if it looks like we can re-scale (see implementation of
     * method for specific details).
     */
    private synchronized boolean isPossibleToSwitch(int encodingId)
    {
        if (encodingId == -1)
        {
            // We failed to resolve the spatial/quality layer of the packet.
            return false;
        }

        if (encodingId > currentEncodingId
            && currentEncodingId < internalEncodingIdTarget)
        {
            // It looks like upscaling is possible.
            return true;
        }
        else if (encodingId < currentEncodingId
            && currentEncodingId > internalEncodingIdTarget)
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
     * @param receivedTime the time the frame was received
     * @return true to accept the VP8 keyframe, otherwise false.
     */
    private synchronized boolean acceptKeyframe(
        int encodingIdOfKeyframe, Instant receivedTime)
    {
        // This branch writes the {@link #currentSpatialLayerId} and it
        // determines whether or not we should switch to another simulcast
        // stream.
        if (encodingIdOfKeyframe < 0)
        {
            // something went terribly wrong, normally we should be able to
            // extract the layer id from a keyframe.
            logger.error("unable to get layer id from keyframe");
            return false;
        }

        logger.debug(() -> "Received a keyframe of encoding: "
                    + encodingIdOfKeyframe);


        // The keyframe request has been fulfilled at this point, regardless of
        // whether we'll be able to achieve the internalEncodingIdTarget.
        needsKeyframe = false;

        if (isOutOfSwitchingPhase(receivedTime))
        {
            // During the switching phase we always project the first
            // keyframe because it may very well be the only one that we
            // receive (i.e. the endpoint is sending low quality only). Then
            // we try to approach the target.

            mostRecentKeyframeGroupArrivalTime = receivedTime;

            logger.debug(() -> "First keyframe in this kf group " +
                "currentEncodingId: " + encodingIdOfKeyframe +
                ". Target is " + internalEncodingIdTarget);

            if (encodingIdOfKeyframe <= internalEncodingIdTarget)
            {
                // If the target is 180p and the first keyframe of a group of
                // keyframes is a 720p keyframe we don't project it. If we
                // receive a 720p keyframe, we know that there MUST be a 180p
                // keyframe shortly after.
                currentEncodingId = encodingIdOfKeyframe;
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

            if (currentEncodingId <= encodingIdOfKeyframe
                && encodingIdOfKeyframe <= internalEncodingIdTarget)
            {
                // upscale or current quality case
                currentEncodingId = encodingIdOfKeyframe;
                logger.debug(() -> "Upscaling to encoding "
                    + encodingIdOfKeyframe
                    + ". The target is " + internalEncodingIdTarget);
                return true;
            }
            else if (encodingIdOfKeyframe <= internalEncodingIdTarget
                && internalEncodingIdTarget < currentEncodingId)
            {
                // downscale case
                currentEncodingId = encodingIdOfKeyframe;
                logger.debug(() -> " Downscaling to encoding "
                    + encodingIdOfKeyframe + ". The target is + "
                    + internalEncodingIdTarget);
                return true;
            }
            else
            {
                return false;
            }
        }
    }

    /**
     * Gets a JSON representation of the parts of this object's state that
     * are deemed useful for debugging.
     */
    @SuppressWarnings("unchecked")
    @SuppressFBWarnings(
            value = "IS2_INCONSISTENT_SYNC",
            justification = "We intentionally avoid synchronizing while reading" +
                    " fields only used in debug output.")
    public JSONObject getDebugState()
    {
        JSONObject debugState = new JSONObject();
        debugState.put(
                "mostRecentKeyframeGroupArrivalTimeMs",
            mostRecentKeyframeGroupArrivalTime);
        debugState.put("needsKeyframe", needsKeyframe);
        debugState.put(
                "internalEncodingIdTarget",
            internalEncodingIdTarget);
        debugState.put("currentEncodingId", currentEncodingId);

        return debugState;
    }
}
