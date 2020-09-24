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

package org.jitsi.nlj.stats

import org.jitsi.nlj.util.NEVER
import org.jitsi.nlj.util.latest
import org.jitsi.nlj.util.threadSafeVetoable
import java.time.Instant

@Suppress("unused")
class PacketIOActivity {
    /**
     * The last time an RTP or RTCP packet was received.
     */
    var lastRtpPacketReceivedInstant: Instant by threadSafeVetoable(NEVER) { _, oldValue, newValue ->
        newValue.isAfter(oldValue)
    }
    /**
     * The last time an RTP or RTCP packet was received.
     */
    var lastRtpPacketSentInstant: Instant by threadSafeVetoable(NEVER) { _, oldValue, newValue ->
        newValue.isAfter(oldValue)
    }
    /**
     * The last time ICE consent was refreshed.
     */
    var lastIceActivityInstant: Instant by threadSafeVetoable(NEVER) { _, oldValue, newValue ->
        newValue.isAfter(oldValue)
    }

    /**
     * The last time an RTP or RTCP packet was sent or received.
     */
    val lastRtpActivityInstant: Instant
        get() = latest(lastRtpPacketReceivedInstant, lastRtpPacketSentInstant)

    /**
     * The last time a packet was received (RTP, RTCP or ICE consent).
     */
    val lastIncomingActivityInstant: Instant
        get() = latest(lastRtpPacketReceivedInstant, lastIceActivityInstant)

    /**
     * The last time a packet was sent or received.
     */
    val lastActivityInstant: Instant
        get() = latest(lastRtpPacketReceivedInstant, lastRtpPacketSentInstant, lastIceActivityInstant)
}
