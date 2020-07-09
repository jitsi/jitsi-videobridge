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
import org.jitsi.nlj.RtpLayerDesc.Companion.SUSPENDED_ENCODING_ID
import org.jitsi.nlj.RtpLayerDesc.Companion.SUSPENDED_INDEX
import org.jitsi.nlj.RtpLayerDesc.Companion.getEidFromIndex
import org.jitsi.nlj.RtpLayerDesc.Companion.getSidFromIndex
import org.jitsi.nlj.RtpLayerDesc.Companion.getTidFromIndex
import org.jitsi.nlj.RtpLayerDesc.Companion.indexString
import org.jitsi.utils.logging.DiagnosticContext
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
     * A boolean flag that indicates whether a keyframe is needed, due to an
     * encoding or (in some cases) a spatial layer switch.
     *
     * Reading/writing of this field is synchronized on this instance.
     */
    private var needsKeyframe = false

    /**
     * The layer index that this instance tries to achieve. Upon
     * receipt of a packet, we check whether externalTargetIndex
     * (that's specified as an argument to the
     * [acceptFrame] method) is set to a different encoding or spatial layer,
     * in which case we set [needsKeyframe] equal to true and
     * update.
     */
    private var internalTargetIndex = SUSPENDED_INDEX

    /**
     * The layer index that we're currently forwarding. [SUSPENDED_INDEX]
     * indicates that we're not forwarding anything. Reading/writing of this
     * field is synchronized on this instance.
     */
    private var currentIndex = SUSPENDED_INDEX

    /**
     * Which spatial layers are currently being forwarded.
     */
    private val layers: Array<Boolean> = Array(MAX_VP9_LAYERS) { false }

    /**
     * @return true if a keyframe is needed and hasn't been received yet, false otherwise.
     * (This will be set if the encoding changes, and in some cases if the spatial layer changes.)
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
    ): AcceptResult {
        val accept = acceptFrame_(frame, incomingIndex, externalTargetIndex, receivedMs)
        return AcceptResult(accept, getSidFromIndex(incomingIndex) == getSidFromIndex(currentIndex))
    }

    fun acceptFrame_(
        frame: Vp9Frame,
        incomingIndex: Int,
        externalTargetIndex: Int,
        receivedMs: Long
    ): Boolean {
        val externalTargetEncoding = getEidFromIndex(externalTargetIndex)
        val internalTargetEncoding = getEidFromIndex(internalTargetIndex)
        val currentEncoding = getEidFromIndex(currentIndex)

        if (externalTargetEncoding != internalTargetEncoding) {
            // The externalEncodingIdTarget has changed since accept last
            // ran; perhaps we should request a keyframe.
            internalTargetIndex = externalTargetIndex
            if (externalTargetEncoding != SUSPENDED_ENCODING_ID &&
                externalTargetEncoding != currentEncoding) {
                needsKeyframe = true
            }
        }
        if (externalTargetEncoding == SUSPENDED_ENCODING_ID) {
            // We stop forwarding immediately. We will need a keyframe in order
            // to resume.
            currentIndex = SUSPENDED_INDEX
            return false
        }
        var temporalLayerIdOfFrame = frame.temporalLayer
        if (temporalLayerIdOfFrame < 0) {
            // temporal scalability is not enabled. Pretend that
            // this is the base temporal layer.
            temporalLayerIdOfFrame = 0
        }
        val incomingEncoding = getEidFromIndex(incomingIndex)
        return if (frame.isKeyframe) {
            logger.debug {
                "Quality filter got keyframe for stream ${frame.ssrc}"
            }
            val accept = acceptKeyframe(incomingIndex, receivedMs)
            if (accept) {
                // Keyframes reset layer forwarding, whether or not they're an encoding switch
                for (i in layers.indices) {
                    layers[i] = (i == 0)
                }
            }
            accept
        } else if (currentEncoding != SUSPENDED_ENCODING_ID) {
            if (isOutOfSwitchingPhase(receivedMs) && isPossibleToSwitch(incomingEncoding)) {
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
            if (incomingEncoding != currentEncoding) {
                // for non-keyframes, we can't route anything but the current encoding
                return false
            }

            /* Logic to forward spatial layer:
             * Can forward layer: If layer is being sent, or frame is not inter-picture predicted; and
             *  if layer below is being sent, or if frame does not use inter-picture dependency.
             * Switching layers: If layer of frame is closer to target than current, set current to target
             *  if can forward, otherwise request keyframe.
             * Want to forward layer: If layer of frame is equal to currentLayer, or is less than currentLayer
             *  and isUpperLayerReference.
             * If wantToForward and !canForward, something's wrong, request keyFrame.
             * accept = wantToForward && canForward
             * if (tid == 0)
             *   layers[layerOfFrame] = accept
             * return accept
             */

            val spatialLayerOfFrame = getSidFromIndex(incomingIndex)
            val externalTargetSpatialId = getSidFromIndex(externalTargetIndex)
            var currentSpatialLayer = getSidFromIndex(currentIndex)

            val canForwardLayer = (!frame.isInterPicturePredicted || layers[spatialLayerOfFrame]) &&
                (!frame.usesInterLayerDependency || layers[spatialLayerOfFrame - 1])

            val wantToSwitch =
                (spatialLayerOfFrame > currentSpatialLayer && currentSpatialLayer < externalTargetSpatialId) ||
                (spatialLayerOfFrame < currentEncoding && currentSpatialLayer > externalTargetSpatialId)

            if (wantToSwitch) {
                if (canForwardLayer) {
                    currentIndex = incomingIndex
                    currentSpatialLayer = spatialLayerOfFrame
                } else {
                    needsKeyframe = true
                }
            }

            val wantToForwardLayer =
                (spatialLayerOfFrame == currentSpatialLayer) ||
                    (spatialLayerOfFrame < currentSpatialLayer && frame.isUpperLevelReference)

            if (wantToForwardLayer && !canForwardLayer) {
                logger.warn("Want to forward ${indexString(currentIndex)} frame, but can't! " +
                    "layers=${layers.joinToString()}, currentIndex=${indexString(currentIndex)}, " +
                    "isInterPicturePredicted=${frame.isInterPicturePredicted}, " +
                    "usesInterLayerDependency=${frame.usesInterLayerDependency}."
                )
            }

            val accept = wantToForwardLayer && canForwardLayer

            if (temporalLayerIdOfFrame == 0) {
                layers[spatialLayerOfFrame] = accept
            }

            if (!accept) {
                return false
            }

            // This branch reads the {@link #currentEncodingId} and it
            // filters packets based on their temporal layer.
            if (currentEncoding > externalTargetEncoding || currentSpatialLayer > externalTargetSpatialId) {
                // pending downscale, decrease the frame rate until we
                // downscale.
                temporalLayerIdOfFrame < 1
            } else if (currentEncoding < externalTargetEncoding || currentSpatialLayer < externalTargetSpatialId) {
                // pending upscale, increase the frame rate until we upscale.
                true
            } else {
                // The currentEncoding exactly matches the externalTargetEncoding.
                val externalTargetTemporalId = getTidFromIndex(externalTargetIndex)
                temporalLayerIdOfFrame <= externalTargetTemporalId
            }
        } else {
            // In this branch we're not processing a keyframe and the
            // currentEncoding is in suspended state, which means we need
            // a keyframe to start streaming again. Reaching this point also
            // means that we want to forward something (because both
            // externalEncodingTarget is not suspended) so we set the request keyframe flag.

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
    private fun isPossibleToSwitch(incomingEncoding: Int): Boolean {
        val currentEncoding = getEidFromIndex(currentIndex)
        val internalTargetEncoding = getEidFromIndex(internalTargetIndex)

        if (incomingEncoding == SUSPENDED_ENCODING_ID) {
            // We failed to resolve the spatial/quality layer of the packet.
            return false
        }
        return when {
            incomingEncoding > currentEncoding && currentEncoding < internalTargetEncoding ->
                // It looks like upscaling is possible
                true
            incomingEncoding < currentEncoding && currentEncoding > internalTargetEncoding ->
                // It looks like downscaling is possible.
                true
            else ->
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
        incomingIndex: Int,
        receivedMs: Long
    ): Boolean {
        val encodingIdOfKeyframe = getEidFromIndex(incomingIndex)
        // This branch writes the {@link #currentSpatialLayerId} and it
        // determines whether or not we should switch to another simulcast
        // stream.
        if (encodingIdOfKeyframe < 0) {
            // something went terribly wrong, normally we should be able to
            // extract the layer id from a keyframe.
            logger.error("unable to get layer id from keyframe")
            return false
        }
        // Keyframes have to be sid 0, tid 0, unless something screwy is going on.
        if (getSidFromIndex(incomingIndex) != 0 || getTidFromIndex(incomingIndex) != 0) {
            logger.warn("Surprising index ${indexString(incomingIndex)} on keyframe")
        }
        logger.debug {
            "Received a keyframe of encoding: $encodingIdOfKeyframe"
        }

        val internalTargetEncoding = getEidFromIndex(internalTargetIndex)
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
                    "Target is $internalTargetEncoding"
            }
            if (encodingIdOfKeyframe <= internalTargetEncoding) {
                // If the target is 180p and the first keyframe of a group of
                // keyframes is a 720p keyframe we don't project it. If we
                // receive a 720p keyframe, we know that there MUST be a 180p
                // keyframe shortly after.
                currentIndex = incomingIndex
                true
            } else {
                false
            }
        } else {
            // We're within the 300ms window since the reception of the
            // first key frame of a key frame group, let's check whether an
            // upscale/downscale is possible.
            val currentEncoding = getEidFromIndex(currentIndex)
            when {
                currentEncoding <= encodingIdOfKeyframe &&
                    encodingIdOfKeyframe <= internalTargetEncoding -> {
                    // upscale or current quality case
                    currentIndex = incomingIndex
                    logger.debug {
                        "Upscaling to encoding $encodingIdOfKeyframe. " +
                            "The target is $internalTargetEncoding"
                    }
                    true
                }
                encodingIdOfKeyframe <= internalTargetEncoding &&
                    internalTargetEncoding < currentEncoding -> {
                    // downscale case
                    currentIndex = incomingIndex
                    logger.debug {
                        "Downscaling to encoding $encodingIdOfKeyframe. " +
                            "The target is $internalTargetEncoding"
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
     * Adds internal state to a diagnostic context time series point.
     */
    @SuppressFBWarnings(value = ["IS2_INCONSISTENT_SYNC"],
        justification = "We intentionally avoid synchronizing while reading fields only used in debug output.")
    internal fun addDiagnosticContext(pt: DiagnosticContext.TimeSeriesPoint) {
        pt.addField("qf.currentIndex", indexString(currentIndex))
            .addField("qf.internalTargetIndex", indexString(internalTargetIndex))
            .addField("qf.needsKeyframe", needsKeyframe)
            .addField("qf.mostRecentKeyframeGroupArrivalTimeMs", mostRecentKeyframeGroupArrivalTimeMs)
        for (i in layers.indices) {
            pt.addField("qf.layer.$i", layers[i])
        }
    }

    /**
     * Gets a JSON representation of the parts of this object's state that
     * are deemed useful for debugging.
     */
    @get:SuppressFBWarnings(value = ["IS2_INCONSISTENT_SYNC"],
        justification = "We intentionally avoid synchronizing while reading fields only used in debug output.")
    val debugState: JSONObject
        get() {
            val debugState = JSONObject()
            debugState["mostRecentKeyframeGroupArrivalTimeMs"] = mostRecentKeyframeGroupArrivalTimeMs
            debugState["needsKeyframe"] = needsKeyframe
            debugState["internalTargetIndex"] = indexString(internalTargetIndex)
            debugState["currentIndex"] = indexString(currentIndex)
            return debugState
        }

    data class AcceptResult(
        val accept: Boolean,
        val mark: Boolean = false
    )

    companion object {
        /**
         * The default maximum frequency (in millis) at which the media engine
         * generates key frame.
         */
        private const val MIN_KEY_FRAME_WAIT_MS = 300

        /**
         * The maximum possible number of VP9 spatial layers.
         */
        private const val MAX_VP9_LAYERS = 8
    }
}
