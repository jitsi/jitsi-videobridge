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

import java.time.Duration
import java.time.Instant

/**
 * Paces a quality filter's keyframe requests, shared by the codec-specific filters.
 *
 * A sender which generates keyframes on every encoding at once sends them within a few frame intervals of each
 * other. So for [MIN_KEY_FRAME_WAIT] after a keyframe arrives, the keyframe a filter is waiting for may still be on
 * its way, and should not be requested again. The same interval decides when frames of another encoding, arriving
 * without a keyframe, show that a switch is possible and a request is due. Keyframes closer together than that are
 * one group, whose arrival time is that of its first keyframe.
 *
 * Not thread-safe: a filter calls it under its own lock, or on the thread which has just called its accept method.
 */
class KeyframeRequestPacer {
    /** The arrival time of the first keyframe of the most recent group, or null if none has arrived. */
    private var mostRecentKeyframeGroupArrivalTime: Instant? = null

    /** The arrival time of the most recent frame, keyframe or not, or null if none has arrived. */
    private var mostRecentFrameArrivalTime: Instant? = null

    /** Records the arrival of a frame, keyframe or not. */
    fun onFrame(receivedTime: Instant?) {
        receivedTime?.let { mostRecentFrameArrivalTime = it }
    }

    /** Records the arrival of a keyframe; the first after [MIN_KEY_FRAME_WAIT] starts a new group. */
    fun onKeyframe(receivedTime: Instant?) {
        if (isOutOfSwitchingPhase(receivedTime)) {
            mostRecentKeyframeGroupArrivalTime = receivedTime
        }
    }

    /**
     * Whether more than [MIN_KEY_FRAME_WAIT] had passed at [receivedTime] since the most recent keyframe group
     * started, or no group has started yet. False if the time is not known.
     */
    fun isOutOfSwitchingPhase(receivedTime: Instant?): Boolean {
        if (receivedTime == null) {
            return false
        }
        val groupArrival = mostRecentKeyframeGroupArrivalTime ?: return true
        return Duration.between(groupArrival, receivedTime) > MIN_KEY_FRAME_WAIT
    }

    /**
     * Whether a keyframe may be requested now: as of the most recent frame, no keyframe group is still arriving.
     * With no frame time known this is true, so that a request the filter needs is not held off, as it was not
     * before pacing was shared. [isOutOfSwitchingPhase], which re-arms a request, is false then, as it was.
     */
    fun mayRequest(): Boolean = mostRecentFrameArrivalTime?.let { isOutOfSwitchingPhase(it) } ?: true

    /** Whether a keyframe should be requested now: a keyframe is [needed], and [mayRequest]. */
    fun shouldRequest(needed: Boolean): Boolean = needed && mayRequest()

    /** The arrival time of the most recent keyframe group in epoch milliseconds, or -1, for debug output. */
    val mostRecentKeyframeGroupArrivalTimeMs: Long
        get() = mostRecentKeyframeGroupArrivalTime?.toEpochMilli() ?: -1L

    companion object {
        /** How long after a keyframe arrives to wait before requesting another keyframe. */
        @JvmField
        val MIN_KEY_FRAME_WAIT: Duration = Duration.ofMillis(300)
    }
}
