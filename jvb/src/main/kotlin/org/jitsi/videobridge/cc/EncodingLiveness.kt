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

/**
 * Tells a projection which encodings of its source the sender is currently sending, see
 * [org.jitsi.nlj.EncodingLivenessTracker.isLive]. Evaluated at the time of the packet being processed.
 */
interface EncodingLiveness {
    fun isLive(eid: Int): Boolean

    /**
     * Whether encoding [eid] kept being sent while encoding [otherEid] stopped, see
     * [org.jitsi.nlj.EncodingLivenessTracker.hasOutlasted]. Not defaulted to [isLive]. [EncodingSwitchPolicy] relies
     * on the distinction between a sender turning an encoding off and a whole source resuming after a stall. A
     * default would let an implementation silently lose that distinction.
     */
    fun hasOutlasted(eid: Int, otherEid: Int): Boolean
}
