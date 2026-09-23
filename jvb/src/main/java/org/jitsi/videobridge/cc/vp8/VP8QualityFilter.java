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
import org.jetbrains.annotations.Nullable;
import org.jitsi.nlj.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.videobridge.cc.*;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
     * The HD, SD, LD and suspended spatial/quality layer IDs.
     */
    private static final int SUSPENDED_ENCODING_ID = -1;

    /**
     * Paces keyframe requests around the arrival of keyframe groups. Used under this instance's lock.
     */
    private final KeyframeRequestPacer pacer = new KeyframeRequestPacer();

    /**
     * Whether a keyframe is needed to reach the target encoding: a switch is
     * pending, and the encoding being forwarded is not yet the highest encoding
     * being sent at or below the target. See {@link #needsKeyframe()}.
     *
     * Reading/writing of this field is synchronized on this instance.
     */
    private boolean needsKeyframe = false;

    /**
     * The encoding id that this instance tries to achieve. Upon
     * receipt of a packet, we check whether externalSpatialLayerIdTarget
     * (that's specified as an argument to the
     * {@link #acceptFrame(VP8Frame, int, int, Instant, EncodingLiveness)} method) is set to something
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
     * @return true if a keyframe is needed: the target encoding has changed, and the keyframe which would complete
     * the switch hasn't been received yet. Read on the thread which has just called {@link #acceptFrame}, as is
     * {@link #shouldRequestKeyframe()}, so neither takes the lock it does.
     */
    boolean needsKeyframe()
    {
        return needsKeyframe;
    }

    /**
     * @return true if a keyframe should be requested now: a keyframe is needed, and no keyframe has arrived within
     * {@link KeyframeRequestPacer#MIN_KEY_FRAME_WAIT}. Within that time, the keyframe being waited for may still be
     * on its way.
     */
    boolean shouldRequestKeyframe()
    {
        return pacer.shouldRequest(needsKeyframe);
    }

    /**
     * @return whether a keyframe may be requested now, for a need which is not this filter's own: no keyframe group
     * is still arriving. See {@link #shouldRequestKeyframe()} for the filter's own need.
     */
    boolean mayRequestKeyframe()
    {
        return pacer.mayRequest();
    }

    /**
     * Determines whether to accept or drop a VP8 frame.
     *
     * Note that, at the time of this writing, there's no practical need for a
     * synchronized keyword because there's only one thread accessing this
     * method at a time.
     *
     * @param frame  the VP8 frame.
     * @param incomingEncoding the encoding index of the incoming RTP packet
     * @param externalTargetIndex the target quality index that the user of this
     * instance wants to achieve.
     * @param receivedTime the current time
     * @param liveness which encodings the sender is currently sending.
     * @return true to accept the VP8 frame, otherwise false.
     */
    synchronized boolean acceptFrame(
        @NotNull VP8Frame frame,
        int incomingEncoding,
        int externalTargetIndex, Instant receivedTime,
        @NotNull EncodingLiveness liveness)
    {
        // We make local copies of the externalTemporalLayerIdTarget and the
        // externalEncodingTarget (as they may be updated by some other
        // thread).
        int externalTemporalLayerIdTarget
            = RtpLayerDesc.getTidFromIndex(externalTargetIndex);
        int externalEncodingIdTarget
            = RtpLayerDesc.getEidFromIndex(externalTargetIndex);

        pacer.onFrame(receivedTime);

        if (externalEncodingIdTarget != internalEncodingIdTarget)
        {
            // The externalEncodingIdTarget has changed since accept last
            // run; perhaps we should request a keyframe. Not if the encoding we're forwarding is the best encoding
            // at or below the new target that is actually being sent. A request would only refresh it, and when the
            // sender turns the target encoding on, it sends a keyframe on its own.
            internalEncodingIdTarget = externalEncodingIdTarget;
            if (externalEncodingIdTarget > SUSPENDED_ENCODING_ID)
            {
                needsKeyframe = EncodingSwitchPolicy.needsKeyframeAfter(
                    currentEncodingId, externalEncodingIdTarget, liveness);
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

        if (frame.isKeyframe())
        {
            logger.debug(() -> "Quality filter got keyframe for stream "
                    + frame.getSsrc());
            return acceptKeyframe(incomingEncoding, receivedTime, liveness);
        }
        else if (currentEncodingId > SUSPENDED_ENCODING_ID)
        {
            if (pacer.isOutOfSwitchingPhase(receivedTime)
                && EncodingSwitchPolicy.switchPossible(
                    currentEncodingId, incomingEncoding, internalEncodingIdTarget, liveness))
            {
                // Frames are being sent on an encoding in the target's direction, or the encoding we're forwarding
                // has stopped. No keyframe has arrived for a while, so (re-)request a keyframe. This is how a switch
                // completes when the keyframe which would have completed it never arrived. That happens when the
                // sender generates keyframes on one encoding at a time and the keyframe which arrived was for another
                // encoding.
                needsKeyframe = true;
            }
            else if (needsKeyframe
                && currentEncodingId != internalEncodingIdTarget
                && !EncodingSwitchPolicy.needsKeyframeAfter(currentEncodingId, internalEncodingIdTarget, liveness))
            {
                // The keyframe we were waiting for, to switch encodings, would no longer change anything. The sender
                // turned the target encoding off, so the encoding we're forwarding has become the best encoding at or
                // below the target that is being sent. Stop asking, or the request would only refresh what the
                // receiver already has.
                needsKeyframe = false;
            }

            if (incomingEncoding != currentEncodingId)
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
            // currentEncodingId is in suspended state, which means we need
            // a keyframe to start streaming again.

            // We should have already requested a keyframe, either above or when the
            // internal target encoding was first moved off SUSPENDED_ENCODING.

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
     * @param liveness which encodings the sender is currently sending.
     * @return true to accept the VP8 keyframe, otherwise false.
     */
    private synchronized boolean acceptKeyframe(
        int encodingIdOfKeyframe, @Nullable Instant receivedTime, @NotNull EncodingLiveness liveness)
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

        // Whether or not we take it, hold off requesting another keyframe for a bit. A sender which generates
        // keyframes on every encoding at once may still be sending the rest of the group.
        pacer.onKeyframe(receivedTime);

        boolean accept = EncodingSwitchPolicy.acceptKeyframe(
            currentEncodingId, encodingIdOfKeyframe, internalEncodingIdTarget, liveness);
        if (accept)
        {
            logger.debug(() -> "Switching to encoding " + encodingIdOfKeyframe
                + " from " + currentEncodingId + ". The target is " + internalEncodingIdTarget);
            currentEncodingId = encodingIdOfKeyframe;
            // We keep needing a keyframe until we have reached the target, or the highest encoding below it which is
            // actually being sent. A keyframe on some other encoding, whether a keyframe we took as a step toward the
            // target or a keyframe we dropped, doesn't fulfill the request.
            needsKeyframe = EncodingSwitchPolicy.needsKeyframeAfter(
                currentEncodingId, internalEncodingIdTarget, liveness);
        }
        return accept;
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
    public ObjectNode getDebugState()
    {
        ObjectNode debugState = JsonNodeFactory.instance.objectNode();
        debugState.put("mostRecentKeyframeGroupArrivalTimeMs", pacer.getMostRecentKeyframeGroupArrivalTimeMs());
        debugState.put("needsKeyframe", needsKeyframe);
        debugState.put(
                "internalEncodingIdTarget",
            internalEncodingIdTarget);
        debugState.put("currentEncodingId", currentEncodingId);

        return debugState;
    }
}
