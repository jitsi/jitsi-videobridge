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
    var lastRtpPacketReceivedTimestamp: Instant by threadSafeVetoable(NEVER) { _, oldValue, newValue ->
        newValue.isAfter(oldValue)
    }
    var lastRtpPacketSentTimestamp: Instant by threadSafeVetoable(NEVER) { _, oldValue, newValue ->
        newValue.isAfter(oldValue)
    }
    var lastIceActivityTimestamp: Instant by threadSafeVetoable(NEVER) { _, oldValue, newValue ->
        newValue.isAfter(oldValue)
    }

    val lastOverallRtpActivity: Instant
        get() = latest(lastRtpPacketReceivedTimestamp, lastRtpPacketSentTimestamp)

    val latestOverallActivity: Instant
        get() = latest(lastRtpPacketReceivedTimestamp, lastRtpPacketSentTimestamp, lastIceActivityTimestamp)

    // Deprecated

    @Deprecated(replaceWith = ReplaceWith("lastRtpPacketReceivedTimestamp"), message = "Deprecated")
    var lastPacketReceivedTimestampMs: Long
        set(value) {
            lastRtpPacketReceivedTimestamp = Instant.ofEpochMilli(value)
        }
        get() = lastRtpPacketSentTimestamp.toEpochMilli()

    @Deprecated(replaceWith = ReplaceWith("lastRtpPacketSentTimestamp"), message = "Deprecated")
    var lastPacketSentTimestampMs: Long
        set(value) {
            lastRtpPacketSentTimestamp = Instant.ofEpochMilli(value)
        }
        get() = lastRtpPacketSentTimestamp.toEpochMilli()

    @Deprecated(replaceWith = ReplaceWith("lastOverallRtpPactivity"), message = "Deprecated")
    val lastOverallActivityTimestampMs: Long
        get() = lastOverallRtpActivity.toEpochMilli()
}
