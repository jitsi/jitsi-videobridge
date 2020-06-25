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
package org.jitsi.videobridge.cc.vp9

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings
import org.jitsi.nlj.RtpLayerDesc.Companion.getEidFromIndex
import org.jitsi.nlj.RtpLayerDesc.Companion.getSidFromIndex
import org.jitsi.nlj.RtpLayerDesc.Companion.getTidFromIndex
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import org.json.simple.JSONObject

/**
 * This class is responsible for dropping VP9 simulcast/svc packets based on
 * their quality, i.e. packets that correspond to qualities that are above a
 * given quality target. Instances of this class are thread-safe.
 */
internal class Vp9QualityFilter(parentLogger: Logger) {
    /**
     * The [Logger] to be used by this instance to print debug
     * information.
     */
    private val logger: Logger = createChildLogger(parentLogger)

    /**
     * Holds the arrival time (in millis) of the most recent keyframe group.
     * Reading/writing of this field is synchronized on this instance.
     */
    private var mostRecentKeyframeGroupArrivalTimeMs = -1L

    /**
     * A boolean flag that indicates whether a simulcast switch is pending. This
     * condition is equivalent to:
     *
     * internalSpatialLayerIdTarget != externalSpatialLayerIdTarget.
     *
     * Reading/writing of this field is synchronized on this instance.
     */
    private var needsKeyframe = false

    /**
     * The encoding id that this instance tries to achieve. Upon
     * receipt of a packet, we check whether externalSpatialLayerIdTarget
     * (that's specified as an argument to the
     * [acceptFrame] method) is set to something
     * different, in which case we set [needsKeyframe] equal to true and
     * update.
     */
    private var internalEncodingIdTarget = SUSPENDED_ENCODING_ID

    /**
     * The encoding layer ID that we're currently forwarding. -1
     * indicates that we're not forwarding anything. Reading/writing of this
     * field is synchronized on this instance.
     */
    private var currentEncodingId = SUSPENDED_ENCODING_ID

    /**
     * @return true if a the target encoding id has changed and a
     * keyframe hasn't been received yet, false otherwise.
     */
    fun needsKeyframe(): Boolean {
        return needsKeyframe
    }

    /**
     * Determines whether to accept or drop a VP9 frame.
     *
     * Note that, at the time of this writing, there's no practical need for a
     * synchronized keyword because there's only one thread accessing this
     * method at a time.
     *
     * @param frame the VP9 frame.
     * @param incomingIndex the quality index of the incoming RTP packet
     * @param externalTargetIndex the target quality index that the user of this
     * instance wants to achieve.
     * @param receivedMs the current time (in millis)
     * @return true to accept the VP9 frame, otherwise false.
     */
    @Synchronized
    fun acceptFrame(
        frame: Vp9Frame,
        incomingIndex: Int,
        externalTargetIndex: Int,
        receivedMs: Long
    ): Boolean {
        // We make local copies of the externalTemporalLayerIdTarget and the
        // externalEncodingTarget (as they may be updated by some other
        // thread).
        val externalTemporalLayerIdTarget = getTidFromIndex(externalTargetIndex)
        val externalSpatialLayerIdTarget = getSidFromIndex(externalTargetIndex)
        val externalEncodingIdTarget = getEidFromIndex(externalTargetIndex)

        if (externalEncodingIdTarget != internalEncodingIdTarget) {
            // The externalEncodingIdTarget has changed since accept last
            // run; perhaps we should request a keyframe.
            internalEncodingIdTarget = externalEncodingIdTarget
            if (externalEncodingIdTarget > SUSPENDED_ENCODING_ID) {
                needsKeyframe = true
            }
        }
        if (externalEncodingIdTarget < 0) {
            // We stop forwarding immediately. We will need a keyframe in order
            // to resume.
            currentEncodingId = SUSPENDED_ENCODING_ID
            return false
        }
        var temporalLayerIdOfFrame = frame.temporalLayer
        if (temporalLayerIdOfFrame < 0) {
            // temporal scalability is not enabled. Pretend that
            // this is the base temporal layer.
            temporalLayerIdOfFrame = 0
        }
        val encodingId = getEidFromIndex(incomingIndex)
        return if (frame.isKeyframe) {
            logger.debug {
                "Quality filter got keyframe for stream ${frame.ssrc}"
            }
            acceptKeyframe(encodingId, receivedMs)
        } else if (currentEncodingId > SUSPENDED_ENCODING_ID) {
            if (isOutOfSwitchingPhase(receivedMs) && isPossibleToSwitch(encodingId)) {
                // XXX(george) i've noticed some "rogue" base layer keyframes
                // that trigger this. what happens is the client sends a base
                // layer key frame, the bridge switches to that layer because
                // for all it knows it may be the only keyframe sent by the
                // client engine. then the bridge notices that packets from the
                // higher quality streams are flowing and execution ends-up
                // here. it is a mystery why the engine is "leaking" base layer
                // key frames
                needsKeyframe = true
            }
            if (encodingId != currentEncodingId) {
                // for non-keyframes, we can't route anything but the current encoding
                return false
            }

            // This branch reads the {@link #currentEncodingId} and it
            // filters packets based on their temporal layer.
            if (currentEncodingId > externalEncodingIdTarget) {
                // pending downscale, decrease the frame rate until we
                // downscale.
                temporalLayerIdOfFrame < 1
            } else if (currentEncodingId < externalEncodingIdTarget) {
                // pending upscale, increase the frame rate until we upscale.
                true
            } else {
                // The currentSpatialLayerId matches exactly the target
                // currentSpatialLayerId.
                temporalLayerIdOfFrame <= externalTemporalLayerIdTarget
            }
        } else {
            // In this branch we're not processing a keyframe and the
            // currentSpatialLayerId is in suspended state, which means we need
            // a keyframe to start streaming again. Reaching this point also
            // means that we want to forward something (because both
            // externalEncodingIdTarget and externalTemporalLayerIdTarget
            // are greater than 0) so we set the request keyframe flag.

            // assert needsKeyframe == true;
            false
        }
    }

    /**
     * Returns a boolean that indicates whether we are in layer switching phase
     * or not.
     *
     * @param receivedMs the time the latest frame was received (in millis)
     * @return true if we're in layer switching phase, false otherwise.
     */
    @Synchronized
    private fun isOutOfSwitchingPhase(receivedMs: Long): Boolean {
        val deltaMs = receivedMs - mostRecentKeyframeGroupArrivalTimeMs
        return deltaMs > MIN_KEY_FRAME_WAIT_MS
    }

    /**
     * @return true if it looks like we can re-scale (see implementation of
     * method for specific details).
     */
    @Synchronized
    private fun isPossibleToSwitch(encodingId: Int): Boolean {
        if (encodingId == -1) {
            // We failed to resolve the spatial/quality layer of the packet.
            return false
        }
        return if (encodingId > currentEncodingId &&
            currentEncodingId < internalEncodingIdTarget) {
            // It looks like upscaling is possible.
            true
        } else if (encodingId < currentEncodingId &&
            currentEncodingId > internalEncodingIdTarget) {
            // It looks like downscaling is possible.
            true
        } else {
            false
        }
    }

    /**
     * Determines whether to accept or drop a VP9 keyframe. This method updates
     * the spatial layer id.
     *
     * Note that, at the time of this writing, there's no practical need for a
     * synchronized keyword because there's only one thread accessing this
     * method at a time.
     *
     * @param receivedMs the time the frame was received (in millis)
     * @return true to accept the VP9 keyframe, otherwise false.
     */
    @Synchronized
    private fun acceptKeyframe(
        encodingIdOfKeyframe: Int,
        receivedMs: Long
    ): Boolean {
        // This branch writes the {@link #currentSpatialLayerId} and it
        // determines whether or not we should switch to another simulcast
        // stream.
        if (encodingIdOfKeyframe < 0) {
            // something went terribly wrong, normally we should be able to
            // extract the layer id from a keyframe.
            logger.error("unable to get layer id from keyframe")
            return false
        }
        logger.debug {
            "Received a keyframe of encoding: $encodingIdOfKeyframe"
        }

        // The keyframe request has been fulfilled at this point, regardless of
        // whether we'll be able to achieve the internalEncodingIdTarget.
        needsKeyframe = false
        return if (isOutOfSwitchingPhase(receivedMs)) {
            // During the switching phase we always project the first
            // keyframe because it may very well be the only one that we
            // receive (i.e. the endpoint is sending low quality only). Then
            // we try to approach the target.
            mostRecentKeyframeGroupArrivalTimeMs = receivedMs
            logger.debug {
                "First keyframe in this kf group " +
                    "currentEncodingId: $encodingIdOfKeyframe. " +
                    "Target is $internalEncodingIdTarget"
            }
            if (encodingIdOfKeyframe <= internalEncodingIdTarget) {
                // If the target is 180p and the first keyframe of a group of
                // keyframes is a 720p keyframe we don't project it. If we
                // receive a 720p keyframe, we know that there MUST be a 180p
                // keyframe shortly after.
                currentEncodingId = encodingIdOfKeyframe
                true
            } else {
                false
            }
        } else {
            // We're within the 300ms window since the reception of the
            // first key frame of a key frame group, let's check whether an
            // upscale/downscale is possible.
            when {
                encodingIdOfKeyframe in currentEncodingId..internalEncodingIdTarget -> {
                    // upscale or current quality case
                    currentEncodingId = encodingIdOfKeyframe
                    logger.debug {
                        "Upscaling to encoding $encodingIdOfKeyframe. " +
                            "The target is $internalEncodingIdTarget"
                    }
                    true
                }
                internalEncodingIdTarget in encodingIdOfKeyframe until currentEncodingId -> {
                    // downscale case
                    currentEncodingId = encodingIdOfKeyframe
                    logger.debug {
                        "Downscaling to encoding $encodingIdOfKeyframe. " +
                            "The target is $internalEncodingIdTarget"
                    }
                    true
                }
                else -> {
                    false
                }
            }
        }
    }

    /**
     * Gets a JSON representation of the parts of this object's state that
     * are deemed useful for debugging.
     */
    @get:SuppressFBWarnings(value = ["IS2_INCONSISTENT_SYNC"], justification = "We intentionally avoid synchronizing while reading" +
        " fields only used in debug output.")
    val debugState: JSONObject
        get() {
            val debugState = JSONObject()
            debugState["mostRecentKeyframeGroupArrivalTimeMs"] = mostRecentKeyframeGroupArrivalTimeMs
            debugState["needsKeyframe"] = needsKeyframe
            debugState["internalEncodingIdTarget"] = internalEncodingIdTarget
            debugState["currentEncodingId"] = currentEncodingId
            return debugState
        }

    companion object {
        /**
         * The default maximum frequency (in millis) at which the media engine
         * generates key frame.
         */
        private const val MIN_KEY_FRAME_WAIT_MS = 300

        /**
         * The HD, SD, LD and suspended spatial/quality layer IDs.
         */
        private const val SUSPENDED_ENCODING_ID = -1
    }
}
