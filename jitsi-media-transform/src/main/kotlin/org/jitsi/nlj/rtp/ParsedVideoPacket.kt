/*
 * Copyright @ 2018 - present 8x8, Inc.
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

package org.jitsi.nlj.rtp

/**
 * A [VideoRtpPacket] which has been parsed and therefore
 * contains more information, such as whether or not it's
 * a keyframe
 */
abstract class ParsedVideoPacket(
    buffer: ByteArray,
    offset: Int,
    length: Int,
    encodingId: Int
) : VideoRtpPacket(buffer, offset, length, encodingId) {

    abstract val isKeyframe: Boolean
    abstract val isStartOfFrame: Boolean
    abstract val isEndOfFrame: Boolean

    /** Whether the packet meets the needs of the routing infrastructure.
     * If a packet could be parsed more than one way (e.g. it is VP8 or VP9 but also has an AV1 DD)
     * this will let us choose which parse to prefer.
     */
    abstract fun meetsRoutingNeeds(): Boolean
}
