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

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
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
import org.jitsi.videobridge.cc.EncodingLiveness
import org.jitsi.videobridge.cc.EncodingSwitchPolicy
import org.jitsi.videobridge.cc.KeyframeRequestPacer
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

    /** Paces keyframe requests around the arrival of keyframe groups. Used under this instance's lock. */
    private val pacer = KeyframeRequestPacer()

    /**
     * A boolean flag that indicates whether a keyframe is needed, due to an
     * encoding or (in some cases) a decode target switch.
     */
    private var keyframeNeeded = false

    /**
     * Whether a keyframe is needed, due to an encoding or (in some cases) a decode target switch. Read, like
     * [shouldRequestKeyframe], on the thread which has just called [acceptFrame], so neither takes the lock it does.
     */
    val needsKeyframe: Boolean
        get() = keyframeNeeded

    /**
     * Whether a keyframe should be requested now: a keyframe is needed, and no keyframe has arrived within
     * [KeyframeRequestPacer.MIN_KEY_FRAME_WAIT]. Within that time, the keyframe being waited for may still be on its
     * way from a sender which generates keyframes on every encoding at once.
     */
    val shouldRequestKeyframe: Boolean
        get() = pacer.shouldRequest(keyframeNeeded)

    /**
     * Whether a keyframe may be requested now, for a need which is not this filter's own: no group is still
     * arriving.
     */
    val mayRequestKeyframe: Boolean
        get() = pacer.mayRequest()

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
        receivedTime: Instant?,
        liveness: EncodingLiveness
    ): AcceptResult {
        val prevIndex = currentIndex
        val accept = doAcceptFrame(frame, incomingEncoding, externalTargetIndex, receivedTime, liveness)
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
        receivedTime: Instant?,
        liveness: EncodingLiveness
    ): Boolean {
        val externalTargetEncoding = getEidFromIndex(externalTargetIndex)
        val currentEncoding = getEidFromIndex(currentIndex)

        pacer.onFrame(receivedTime)

        if (externalTargetEncoding != internalTargetEncoding) {
            // The externalEncodingIdTarget has changed since accept last
            // ran; perhaps we should request a keyframe. Not if the encoding we're forwarding is the best encoding
            // at or below the new target that is actually being sent. A request would only refresh it, and when the
            // sender turns the target encoding on, it sends a keyframe on its own.
            internalTargetEncoding = externalTargetEncoding
            if (externalTargetEncoding != SUSPENDED_ENCODING_ID) {
                keyframeNeeded =
                    EncodingSwitchPolicy.needsKeyframeAfter(currentEncoding, externalTargetEncoding, liveness)
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
            acceptKeyframe(frame, incomingEncoding, externalTargetIndex, receivedTime, liveness)
        } else if (currentEncoding != SUSPENDED_ENCODING_ID) {
            if (pacer.isOutOfSwitchingPhase(receivedTime) &&
                EncodingSwitchPolicy.switchPossible(currentEncoding, incomingEncoding, internalTargetEncoding, liveness)
            ) {
                // Frames are being sent on an encoding in the target's direction, or the encoding we're forwarding
                // has stopped. No keyframe has arrived for a while, so (re-)request a keyframe. This is how a switch
                // completes when the keyframe which would have completed it never arrived. That happens when the
                // sender generates keyframes on one encoding at a time and the keyframe which arrived was for another
                // encoding.
                keyframeNeeded = true
            } else if (keyframeNeeded &&
                currentEncoding != internalTargetEncoding &&
                !EncodingSwitchPolicy.needsKeyframeAfter(currentEncoding, internalTargetEncoding, liveness)
            ) {
                // The keyframe we were waiting for, to switch encodings, would no longer change anything. The sender
                // turned the target encoding off, so the encoding we're forwarding has become the best encoding at or
                // below the target that is being sent. Stop asking, or the request would only refresh what the
                // receiver already has. A keyframe needed for a decode target switch within the current encoding is
                // still needed.
                keyframeNeeded = false
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
                keyframeNeeded = true
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
                /* This shouldn't happen. The dependency descriptor is taken as accurate: a sender announces every
                 * change to its active decode targets, the parser then rebuilds the layers from the active ones and
                 * sets layeringChanged, and the allocation is updated on that packet before this filter sees it. So
                 * the target is never a decode target the frame says is inactive, and no fallback is attempted. The
                 * exception is a reordered packet still carrying the bitmask from before a change, whose frame is
                 * stale anyway. */
                logger.warn {
                    "External target DT $externalTargetDt not present in current decode targets 0x" +
                        Integer.toHexString(frame.activeDecodeTargets) + " for frame $frame."
                }
                return false
            }

            if (currentDt != externalTargetDt) {
                val frameMap = av1FrameMap[frame.ssrc]
                val targetDti = frameInfo.dti.getOrNull(externalTargetDt)
                if (targetDti == null) {
                    /* This shouldn't happen, for the same reason as above: a structure change sets layeringChanged
                     * on its packet, so the target is never a decode target the structure lacks. */
                    logger.warn { "Target DT $externalTargetDt not present for frame $frame [frameInfo $frameInfo]" }
                } else if (targetDti == DTI.SWITCH &&
                    frameMap != null &&
                    frameInfo.fdiff.all {
                        frameMap.getIndex(frame.index - it)?.isAccepted == true
                    }
                ) {
                    logger.debug { "Switching to DT $externalTargetDt from $currentDt" }
                    currentDt = externalTargetDt
                    currentIndex = externalTargetIndex
                } else if (frame.structure?.canSwitchWithoutKeyframe(currentDt, externalTargetDt) != true) {
                    logger.debug { "Want to switch to DT $externalTargetDt from $currentDt, requesting keyframe" }
                    keyframeNeeded = true
                }
            }

            /* The structure may have shrunk below the decode target being forwarded, in which case nothing is. */
            val currentFrameDti = frameInfo.dti.getOrNull(currentDt) ?: DTI.NOT_PRESENT
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
        receivedTime: Instant?,
        liveness: EncodingLiveness
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

        /* The index to forward at if this keyframe is taken, and whether it can be. Its encoding is the keyframe's,
         * which becomes current. Its decode target is a decode target the keyframe is part of and the sender has
         * active. For a keyframe of the target encoding, that is the target's decode target. For a refresh of the
         * current encoding while a switch to another encoding is pending, it is the decode target being forwarded.
         * That way the receiver is not sent more than it was allocated. The decode target being forwarded may have
         * left the structure, or the sender may have deactivated it. A refresh keyframe is then forwarded at the
         * highest usable decode target instead. Dropping it would leave its successors undecodable. A keyframe of any
         * other encoding is forwarded at its highest usable decode target. */
        val activeDts = frame.activeDecodeTargets
        fun active(dt: Int) = activeDts == null || activeDts.containsDecodeTarget(dt)
        fun usable(dt: Int) = dt in frameInfo.dtisPresent && active(dt)
        val currentDt = getDtFromIndex(currentIndex)
        val targetDt = getDtFromIndex(externalTargetIndex)
        fun highestUsableIndex(): Int? =
            frameInfo.dtisPresent.filter { usable(it) }.maxOrNull()?.let { getIndex(incomingEncoding, it) }
        val indexIfSwitched: Int? = when {
            /* A keyframe of the target encoding which is not part of the target decode target, such as another
             * spatial layer's in simulcast within one encoding, is not taken. A keyframe which is part of it
             * follows. This holds whether or not the target encoding is also the current encoding. */
            incomingEncoding == externalTargetEncoding -> externalTargetIndex.takeIf { usable(targetDt) }

            /* A refresh of the current encoding while a switch to another encoding is pending: kept, since dropping
             * it would leave its successors undecodable. A keyframe is still needed. */
            incomingEncoding == currentEncoding -> if (usable(currentDt)) currentIndex else highestUsableIndex()

            else -> highestUsableIndex()
        }
        val acceptIfSwitched = indexIfSwitched != null

        // Whether or not we take it, hold off requesting another keyframe for a bit. A sender which generates
        // keyframes on every encoding at once may still be sending the rest of the group.
        pacer.onKeyframe(receivedTime)

        val wouldSwitch =
            EncodingSwitchPolicy.acceptKeyframe(currentEncoding, incomingEncoding, internalTargetEncoding, liveness)
        val accept = acceptIfSwitched && wouldSwitch
        if (accept && indexIfSwitched != null) {
            if (currentEncoding != incomingEncoding) {
                logger.debug {
                    "Switching to encoding $incomingEncoding from $currentEncoding. " +
                        "The target is $internalTargetEncoding"
                }
            }
            currentIndex = indexIfSwitched
            // We keep needing a keyframe until we have reached the target, or the highest encoding below it which is
            // actually being sent. A keyframe on some other encoding, whether a keyframe we took as a step toward the
            // target or a keyframe we dropped, doesn't fulfill the request.
            keyframeNeeded =
                EncodingSwitchPolicy.needsKeyframeAfter(incomingEncoding, internalTargetEncoding, liveness) ||
                /* Taken at another decode target than the target's: a keyframe for that decode target is still
                 * needed. */
                (incomingEncoding == internalTargetEncoding && indexIfSwitched != externalTargetIndex)
        }
        return accept
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
            .addField("qf.needsKeyframe", needsKeyframe)
            .addField(
                "qf.mostRecentKeyframeGroupArrivalTimeMs",
                pacer.mostRecentKeyframeGroupArrivalTimeMs
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
    val debugState: ObjectNode
        get() {
            val debugState = JsonNodeFactory.instance.objectNode()
            debugState.put(
                "mostRecentKeyframeGroupArrivalTimeMs",
                pacer.mostRecentKeyframeGroupArrivalTimeMs
            )
            debugState.put("needsKeyframe", needsKeyframe)
            debugState.put("internalTargetEncoding", internalTargetEncoding)
            debugState.put("currentIndex", Av1DDRtpLayerDesc.indexString(currentIndex))
            return debugState
        }

    data class AcceptResult(
        val accept: Boolean,
        val isResumption: Boolean,
        val mark: Boolean,
        val newDt: Int?
    )
}
