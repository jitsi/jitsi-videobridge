/*
 * Copyright @ 2019 - present 8x8, Inc
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
package org.jitsi.videobridge.cc.av1

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings
import org.jitsi.nlj.RtpLayerDesc.Companion.SUSPENDED_ENCODING_ID
import org.jitsi.nlj.RtpLayerDesc.Companion.SUSPENDED_INDEX
import org.jitsi.nlj.RtpLayerDesc.Companion.getEidFromIndex
import org.jitsi.nlj.rtp.codec.av1.Av1DDRtpLayerDesc
import org.jitsi.nlj.rtp.codec.av1.Av1DDRtpLayerDesc.Companion.SUSPENDED_DT
import org.jitsi.nlj.rtp.codec.av1.Av1DDRtpLayerDesc.Companion.getDtFromIndex
import org.jitsi.nlj.rtp.codec.av1.Av1DDRtpLayerDesc.Companion.getIndex
import org.jitsi.nlj.rtp.codec.av1.containsDecodeTarget
import org.jitsi.rtp.rtp.header_extensions.DTI
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import org.json.simple.JSONObject
import java.time.Duration
import java.time.Instant

/**
 * This class is responsible for dropping AV1 simulcast/svc packets based on
 * their quality, i.e. packets that correspond to qualities that are above a
 * given quality target. Instances of this class are thread-safe.
 */
internal class Av1DDQualityFilter(
    val av1FrameMap: Map<Long, Av1DDFrameMap>,
    parentLogger: Logger
) {
    /**
     * The [Logger] to be used by this instance to print debug
     * information.
     */
    private val logger: Logger = createChildLogger(parentLogger)

    /**
     * Holds the arrival time of the most recent keyframe group.
     * Reading/writing of this field is synchronized on this instance.
     */
    private var mostRecentKeyframeGroupArrivalTime: Instant? = null

    /**
     * A boolean flag that indicates whether a keyframe is needed, due to an
     * encoding or (in some cases) a decode target switch.
     */
    var needsKeyframe = false
        private set

    /**
     * The encoding ID that this instance tries to achieve. Upon
     * receipt of a packet, we check whether encoding in the externalTargetIndex
     * (that's specified as an argument to the
     * [acceptFrame] method) is set to something different,
     * in which case we set [needsKeyframe] equal to true and
     * update.
     */
    private var internalTargetEncoding = SUSPENDED_ENCODING_ID

    /**
     * The decode target that this instance tries to achieve.
     */
    private var internalTargetDt = SUSPENDED_DT

    /**
     * The layer index that we're currently forwarding. [SUSPENDED_INDEX]
     * indicates that we're not forwarding anything. Reading/writing of this
     * field is synchronized on this instance.
     */
    private var currentIndex = SUSPENDED_INDEX

    /**
     * Determines whether to accept or drop an AV1 frame.
     *
     * Note that, at the time of this writing, there's no practical need for a
     * synchronized keyword because there's only one thread accessing this
     * method at a time.
     *
     * @param frame the AV1 frame.
     * @param incomingEncoding The encoding ID of the incoming packet
     * @param externalTargetIndex the target quality index that the user of this
     * instance wants to achieve.
     * @param receivedTime the current time (as an Instant)
     * @return true to accept the AV1 frame, otherwise false.
     */
    @Synchronized
    fun acceptFrame(
        frame: Av1DDFrame,
        incomingEncoding: Int,
        externalTargetIndex: Int,
        receivedTime: Instant?
    ): AcceptResult {
        val prevIndex = currentIndex
        val accept = doAcceptFrame(frame, incomingEncoding, externalTargetIndex, receivedTime)
        val currentDt = getDtFromIndex(currentIndex)
        val mark = currentDt != SUSPENDED_DT &&
            (frame.frameInfo?.spatialId == frame.structure?.decodeTargetLayers?.getOrNull(currentDt)?.spatialId)
        val isResumption = (prevIndex == SUSPENDED_INDEX && currentIndex != SUSPENDED_INDEX)
        if (isResumption) {
            check(accept) {
                // Every code path that can turn off SUSPENDED_INDEX also accepts
                "isResumption=$isResumption but accept=$accept for frame ${frame.frameNumber}, " +
                    "frameInfo=${frame.frameInfo}"
            }
        }
        val dtChanged = (prevIndex != currentIndex)
        if (dtChanged && currentDt != SUSPENDED_DT) {
            check(accept) {
                // Every code path that changes DT also accepts
                "dtChanged=$dtChanged but accept=$accept for frame ${frame.frameNumber}, frameInfo=${frame.frameInfo}"
            }
        }
        val newDt = if (dtChanged || frame.activeDecodeTargets != null) currentDt else null
        return AcceptResult(accept = accept, isResumption = isResumption, mark = mark, newDt = newDt)
    }

    private fun doAcceptFrame(
        frame: Av1DDFrame,
        incomingEncoding: Int,
        externalTargetIndex: Int,
        receivedTime: Instant?
    ): Boolean {
        val externalTargetEncoding = getEidFromIndex(externalTargetIndex)
        val currentEncoding = getEidFromIndex(currentIndex)

        if (externalTargetEncoding != internalTargetEncoding) {
            // The externalEncodingIdTarget has changed since accept last
            // ran; perhaps we should request a keyframe.
            internalTargetEncoding = externalTargetEncoding
            if (externalTargetEncoding != SUSPENDED_ENCODING_ID &&
                externalTargetEncoding != currentEncoding
            ) {
                needsKeyframe = true
            }
        }
        if (externalTargetEncoding == SUSPENDED_ENCODING_ID) {
            // We stop forwarding immediately. We will need a keyframe in order
            // to resume.
            currentIndex = SUSPENDED_INDEX
            return false
        }
        return if (frame.isKeyframe) {
            logger.debug {
                "Quality filter got keyframe for stream ${frame.ssrc}"
            }
            acceptKeyframe(frame, incomingEncoding, externalTargetIndex, receivedTime)
        } else if (currentEncoding != SUSPENDED_ENCODING_ID) {
            if (isOutOfSwitchingPhase(receivedTime) && isPossibleToSwitch(incomingEncoding)) {
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

            /** Logic to forward a non-keyframe:
             * If the frame does not have FrameInfo, reject and set needsKeyframe (we couldn't decode its templates).
             * If we're trying to switch DTs in the current encoding, check the template structure to ensure that
             * there is at least one template which is SWITCH for the target DT and not NOT_PRESENT for the current DT.
             * If there is not, request a keyframe.
             * If the current frame is SWITCH for the target DT, and we've forwarded all the frames on which (by
             * its fdiffs) it depends, forward it, and change the current DT to the target DT.
             * In normal circumstances (when the target index == the current index), or when we're trying to switch
             * *up* encodings, forward all frames whose DT for the current DT is not NOT_PRESENT.
             * If we're trying to switch *down* encodings, only forward frames which are REQUIRED or SWITCH for the
             * current DT.
             */
            val frameInfo = frame.frameInfo ?: run {
                needsKeyframe = true
                return@doAcceptFrame false
            }
            var currentDt = getDtFromIndex(currentIndex)
            val externalTargetDt = if (currentEncoding == externalTargetEncoding) {
                getDtFromIndex(externalTargetIndex)
            } else {
                currentDt
            }

            if (
                frame.activeDecodeTargets != null &&
                !frame.activeDecodeTargets.containsDecodeTarget(externalTargetDt)
            ) {
                /* This shouldn't happen, because we should have set layeringChanged for this packet. */
                logger.warn {
                    "External target DT $externalTargetDt not present in current decode targets 0x" +
                        Integer.toHexString(frame.activeDecodeTargets) + " for frame $frame."
                }
                return false
            }

            if (currentDt != externalTargetDt) {
                val frameMap = av1FrameMap[frame.ssrc]
                if (frameInfo.dti.getOrNull(externalTargetDt) == null) {
                    logger.warn { "Target DT $externalTargetDt not present for frame $frame [frameInfo $frameInfo]" }
                }
                if (frameInfo.dti[externalTargetDt] == DTI.SWITCH &&
                    frameMap != null &&
                    frameInfo.fdiff.all {
                        frameMap.getIndex(frame.index - it)?.isAccepted == true
                    }
                ) {
                    logger.debug { "Switching to DT $externalTargetDt from $currentDt" }
                    currentDt = externalTargetDt
                    currentIndex = externalTargetIndex
                } else {
                    if (frame.structure?.canSwitchWithoutKeyframe(currentDt, externalTargetDt) != true) {
                        logger.debug {
                            "Want to switch to DT $externalTargetDt from $currentDt, requesting keyframe"
                        }
                        needsKeyframe = true
                    }
                }
            }

            val currentFrameDti = frameInfo.dti[currentDt]
            if (currentEncoding > externalTargetEncoding) {
                (currentFrameDti == DTI.SWITCH || currentFrameDti == DTI.REQUIRED)
            } else {
                (currentFrameDti != DTI.NOT_PRESENT)
            }
        } else {
            // In this branch we're not processing a keyframe and the
            // currentEncoding is in suspended state, which means we need
            // a keyframe to start streaming again.

            // We should have already requested a keyframe, either above or when the
            // internal target encoding was first moved off SUSPENDED_ENCODING.
            false
        }
    }

    /**
     * Returns a boolean that indicates whether we are in layer switching phase
     * or not.
     *
     * @param receivedTime the time the latest frame was received
     * @return false if we're in layer switching phase, true otherwise.
     */
    @Synchronized
    private fun isOutOfSwitchingPhase(receivedTime: Instant?): Boolean {
        if (receivedTime == null) {
            return false
        }
        if (mostRecentKeyframeGroupArrivalTime == null) {
            return true
        }
        val delta = Duration.between(mostRecentKeyframeGroupArrivalTime, receivedTime)
        return delta > MIN_KEY_FRAME_WAIT
    }

    /**
     * @return true if it looks like we can re-scale (see implementation of
     * method for specific details).
     */
    @Synchronized
    private fun isPossibleToSwitch(incomingEncoding: Int): Boolean {
        val currentEncoding = getEidFromIndex(currentIndex)

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
     * Determines whether to accept or drop an AV1 keyframe. This method updates
     * the encoding id.
     *
     * Note that, at the time of this writing, there's no practical need for a
     * synchronized keyword because there's only one thread accessing this
     * method at a time.
     *
     * @param receivedTime the time the frame was received
     * @return true to accept the AV1 keyframe, otherwise false.
     */
    @Synchronized
    private fun acceptKeyframe(
        frame: Av1DDFrame,
        incomingEncoding: Int,
        externalTargetIndex: Int,
        receivedTime: Instant?
    ): Boolean {
        // This branch writes the {@link #currentSpatialLayerId} and it
        // determines whether or not we should switch to another simulcast
        // stream.
        if (incomingEncoding < 0) {
            // something went terribly wrong, normally we should be able to
            // extract the layer id from a keyframe.
            logger.error("unable to get layer id from keyframe")
            return false
        }
        val frameInfo = frame.frameInfo ?: run {
            // something went terribly wrong, normally we should be able to
            // extract the frame info from a keyframe.
            logger.error("unable to get frame info from keyframe")
            return@acceptKeyframe false
        }
        logger.debug {
            "Received a keyframe of encoding: $incomingEncoding"
        }

        val currentEncoding = getEidFromIndex(currentIndex)
        val externalTargetEncoding = getEidFromIndex(externalTargetIndex)

        val indexIfSwitched = when {
            incomingEncoding == externalTargetEncoding -> externalTargetIndex
            incomingEncoding == internalTargetEncoding && internalTargetDt != -1 ->
                getIndex(currentEncoding, internalTargetDt)
            else -> frameInfo.dtisPresent.maxOrNull()!!
        }
        val dtIfSwitched = getDtFromIndex(indexIfSwitched)
        val dtiIfSwitched = frameInfo.dti[dtIfSwitched]
        val acceptIfSwitched = dtiIfSwitched != DTI.NOT_PRESENT

        // The keyframe request has been fulfilled at this point, regardless of
        // whether we'll be able to achieve the internalEncodingIdTarget.
        needsKeyframe = false
        return if (isOutOfSwitchingPhase(receivedTime)) {
            // During the switching phase we always project the first
            // keyframe because it may very well be the only one that we
            // receive (i.e. the endpoint is sending low quality only). Then
            // we try to approach the target.
            mostRecentKeyframeGroupArrivalTime = receivedTime
            logger.debug {
                "First keyframe in this kf group " +
                    "currentEncodingId: $incomingEncoding. " +
                    "Target is $internalTargetEncoding"
            }
            if (incomingEncoding <= internalTargetEncoding) {
                // If the target is 180p and the first keyframe of a group of
                // keyframes is a 720p keyframe we don't project it. If we
                // receive a 720p keyframe, we know that there MUST be a 180p
                // keyframe shortly after.
                if (acceptIfSwitched) {
                    currentIndex = indexIfSwitched
                }
                acceptIfSwitched
            } else {
                false
            }
        } else {
            // We're within the 300ms window since the reception of the
            // first key frame of a key frame group, let's check whether an
            // upscale/downscale is possible.
            when {
                currentEncoding <= incomingEncoding &&
                    incomingEncoding <= internalTargetEncoding -> {
                    // upscale or current quality case
                    if (acceptIfSwitched) {
                        currentIndex = indexIfSwitched
                        logger.debug {
                            "Upscaling to encoding $incomingEncoding. " +
                                "The target is $internalTargetEncoding"
                        }
                    }
                    acceptIfSwitched
                }
                incomingEncoding <= internalTargetEncoding &&
                    internalTargetEncoding < currentEncoding -> {
                    // downscale case
                    if (acceptIfSwitched) {
                        currentIndex = indexIfSwitched
                        logger.debug {
                            "Downscaling to encoding $incomingEncoding. " +
                                "The target is $internalTargetEncoding"
                        }
                    }
                    acceptIfSwitched
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
    @SuppressFBWarnings(
        value = ["IS2_INCONSISTENT_SYNC"],
        justification = "We intentionally avoid synchronizing while reading fields only used in debug output."
    )
    internal fun addDiagnosticContext(pt: DiagnosticContext.TimeSeriesPoint) {
        pt.addField("qf.currentIndex", Av1DDRtpLayerDesc.indexString(currentIndex))
            .addField("qf.internalTargetEncoding", internalTargetEncoding)
            .addField("qf.internalTargetDt", internalTargetDt)
            .addField("qf.needsKeyframe", needsKeyframe)
            .addField(
                "qf.mostRecentKeyframeGroupArrivalTimeMs",
                mostRecentKeyframeGroupArrivalTime?.toEpochMilli() ?: -1
            )
        /* TODO any other fields necessary */
    }

    /**
     * Gets a JSON representation of the parts of this object's state that
     * are deemed useful for debugging.
     */
    @get:SuppressFBWarnings(
        value = ["IS2_INCONSISTENT_SYNC"],
        justification = "We intentionally avoid synchronizing while reading fields only used in debug output."
    )
    val debugState: JSONObject
        get() {
            val debugState = JSONObject()
            debugState["mostRecentKeyframeGroupArrivalTimeMs"] =
                mostRecentKeyframeGroupArrivalTime?.toEpochMilli() ?: -1
            debugState["needsKeyframe"] = needsKeyframe
            debugState["internalTargetEncoding"] = internalTargetEncoding
            debugState["internalTargetDt"] = internalTargetDt
            debugState["currentIndex"] = Av1DDRtpLayerDesc.indexString(currentIndex)
            return debugState
        }

    data class AcceptResult(
        val accept: Boolean,
        val isResumption: Boolean,
        val mark: Boolean,
        val newDt: Int?
    )

    companion object {
        /**
         * The default maximum frequency at which the media engine
         * generates key frame.
         */
        private val MIN_KEY_FRAME_WAIT = Duration.ofMillis(300)
    }
}
