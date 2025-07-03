/*
 * Copyright @ 2019 - present 8x8, Inc.
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
package org.jitsi.nlj.rtp.bandwidthestimation2

import org.jitsi.utils.NEVER
import java.time.Instant

/** Sent packet info,
 * based loosely on WebRTC rtc_base/network/sent_packet.{h,cc} in
 * WebRTC tag branch-heads/7204 (Chromium 138)
 * stripped down to only the fields needed.
 */

class PacketInfo(
    var includedInFeedback: Boolean = false,
    var includedInAllocation: Boolean = false,
    var packetSizeBytes: Long = 0
)

class SentPacketInfo(
    var packetId: Long = -1,
    var sendTime: Instant = NEVER,
    val info: PacketInfo = PacketInfo(),
)
