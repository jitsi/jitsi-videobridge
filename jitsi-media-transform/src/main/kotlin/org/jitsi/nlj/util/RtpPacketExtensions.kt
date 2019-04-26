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

package org.jitsi.nlj.util

import org.jitsi.rtp.rtp.RtpPacket

/**
 * Shifts the payload byte 'numBytes' to the right, increasing the length of the packet by 'numBytes'
 * and growing (i.e. re-allocating) the underlying buffer if necessary.
 */
fun RtpPacket.shiftPayloadRight(numBytes: Int) {
    // Make sure we have enough space.
    // TODO: if grow() needs to allocate a new buffer, we end up copying the payload twice, which can be avoided.
    // We expect that most of the time we have enough space and grow() doesn't need to do anything, but this should
    // be verified and optimized if it doesn't hold.
    grow(numBytes)

    val payloadOffset = payloadOffset
    System.arraycopy(buffer, payloadOffset, buffer, payloadOffset + 2, payloadLength)
    length += 2
}
