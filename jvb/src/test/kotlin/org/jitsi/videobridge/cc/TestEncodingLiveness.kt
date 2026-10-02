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

import org.jitsi.nlj.PacketInfo

/** Accepts a packet as if every encoding of the source were being sent, for tests which do not care about liveness. */
fun AdaptiveSourceProjectionContext.accept(packetInfo: PacketInfo, targetIndex: Int): Boolean =
    accept(packetInfo, targetIndex, EncodingSwitchPolicy.ALL_LIVE)

/**
 * The encodings for which [isLive] holds are being sent, and have been for a while: the sender turned the others
 * off, and nothing has just resumed. For tests of a sender turning encodings off; see [EncodingLiveness.hasOutlasted].
 */
fun liveEncodings(isLive: (Int) -> Boolean): EncodingLiveness = object : EncodingLiveness {
    override fun isLive(eid: Int) = isLive(eid)
    override fun hasOutlasted(eid: Int, otherEid: Int) = isLive(eid)
}
