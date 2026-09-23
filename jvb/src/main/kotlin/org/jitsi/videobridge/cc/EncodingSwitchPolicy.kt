/*
 * Copyright @ 2026 - present 8x8, Inc.
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
package org.jitsi.videobridge.cc

import org.jitsi.nlj.MediaSourceDesc
import org.jitsi.nlj.RtpLayerDesc.Companion.SUSPENDED_ENCODING_ID

/**
 * The decisions a quality filter makes about switching between the encodings of a simulcast source, shared by the
 * codec-specific filters. Encodings are identified by their encoding ID (eid), ordered by quality, with
 * [SUSPENDED_ENCODING_ID] meaning nothing is being forwarded.
 *
 * These rules are written for a sender which generates a keyframe on one encoding at a time, as libwebrtc does with
 * the WebRTC-Video-PerSsrcKeyframes field trial. Such a keyframe may arrive alone. The same rules handle a sender
 * which generates keyframes on every encoding at once. From such a group they take the first keyframe which is a
 * step toward the target, and each further keyframe which is a further step. They reject the rest.
 *
 * The filters used to take a keyframe on a lower encoding than the encoding being forwarded. The theory was that it
 * might be the only keyframe coming. The rules no longer do that. [EncodingLiveness] now answers that guess: a lower
 * keyframe is only taken when the target is lower or the current encoding has stopped.
 */
object EncodingSwitchPolicy {
    /** Considers every encoding live, which is how the filters behaved before liveness was tracked. */
    @JvmField
    val ALL_LIVE = object : EncodingLiveness {
        override fun isLive(eid: Int) = true
        override fun hasOutlasted(eid: Int, otherEid: Int) = true
    }

    /**
     * Whether a keyframe on encoding [incoming] should be forwarded, and [incoming] become the current encoding, when
     * [current] is being forwarded and [target] is wanted.
     *
     * - Nothing being forwarded: take any keyframe at or below the target, to start sending video. If it is below
     *   the target the filter keeps needing a keyframe, see [needsKeyframeAfter].
     * - The current encoding: always take it. It refreshes what the receiver is decoding, and its successors will be
     *   forwarded, so dropping it would break the receiver's decoder.
     * - A higher encoding: take it if it is at or below the target, as a step up toward it.
     * - A lower encoding: take it in two cases.
     *   - The target is below the current encoding, and the keyframe is between the effective target and the target.
     *     This is the one step down that reaches what this receiver can be sent.
     *   - The current encoding has stopped being sent while the keyframe's encoding kept being sent, and the
     *     keyframe is the effective target, or between it and the target. The receiver gets video again, without
     *     being sent more than it was allocated or less than it can be. When a whole source resumes after a stall,
     *     the current encoding's own frames are about to follow. A lower keyframe would then only be a brief drop;
     *     see [EncodingLiveness.hasOutlasted].
     *   Otherwise it is some other receiver's keyframe: a keyframe below what this receiver can reach, or an
     *   intermediate keyframe which would be a second switch. Taking it would needlessly drop this receiver's
     *   quality or cost a keyframe.
     */
    @JvmStatic
    fun acceptKeyframe(current: Int, incoming: Int, target: Int, liveness: EncodingLiveness): Boolean = when {
        current == SUSPENDED_ENCODING_ID -> incoming <= target
        incoming == current -> true
        incoming > current -> incoming <= target
        target < current && incoming <= target && incoming >= effectiveTarget(target, liveness) -> true
        else -> acceptLowerOfStopped(current, incoming, target, liveness)
    }

    /** The last case of [acceptKeyframe]: a lower keyframe while the current encoding has stopped being sent. */
    private fun acceptLowerOfStopped(current: Int, incoming: Int, target: Int, liveness: EncodingLiveness): Boolean =
        incoming <= target &&
            !liveness.isLive(current) &&
            liveness.hasOutlasted(incoming, current) &&
            incoming >= effectiveTarget(target, liveness)

    /**
     * The encoding a filter wanting [target] should be trying to reach: the highest live encoding at or below it. If
     * no encoding at or below [target] is live, it is [target] itself. That way a filter which can not reach anything
     * keeps needing a keyframe. The same search the projection makes to choose the encoding it requests keyframes
     * from, see [MediaSourceDesc.getEffectiveTargetEncoding].
     *
     * Only encodings at or below the target are considered. Suppose a sender turned off the target encoding and
     * everything below it while still sending a higher encoding. A filter forwarding that higher encoding would then
     * stay at its base temporal layer. It would want the target and re-request a keyframe of it at the rate limit.
     * Nothing is done about that, since a sender turns encodings off from the top down: the lowest is the last to go.
     */
    @JvmStatic
    fun effectiveTarget(target: Int, liveness: EncodingLiveness): Int =
        MediaSourceDesc.effectiveTargetEid(target) { liveness.isLive(it) }

    /**
     * Whether a filter which has just started forwarding a keyframe on [current], wanting [target], still needs a
     * keyframe: it does unless it has reached the effective target.
     */
    @JvmStatic
    fun needsKeyframeAfter(current: Int, target: Int, liveness: EncodingLiveness): Boolean =
        current != effectiveTarget(target, liveness)

    /**
     * Whether a non-keyframe on encoding [incoming], arriving while [current] is forwarded and [target] is wanted,
     * shows that a switch is possible, and a keyframe should be requested for it: the frame is from an encoding in
     * the target's direction, or the current encoding has stopped being sent and anything arriving is better than
     * the frozen picture the receiver has.
     */
    @JvmStatic
    fun switchPossible(current: Int, incoming: Int, target: Int, liveness: EncodingLiveness): Boolean = when {
        incoming == SUSPENDED_ENCODING_ID -> false
        !liveness.isLive(current) -> true
        incoming > current && current < target -> true
        incoming < current && current > target -> true
        else -> false
    }
}
