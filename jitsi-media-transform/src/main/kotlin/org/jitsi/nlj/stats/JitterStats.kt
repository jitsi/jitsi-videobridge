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

import kotlin.math.abs
import org.jitsi.nlj.PacketInfo

open class JitterStats {
    var jitter: Double = 0.0
        private set
    /**
     * The timestamp of the previously received packet, converted to a millisecond timestamp based on the received
     * RTP timestamp and the clock rate for that stream.
     * 'previously received packet' here is as defined by the order in which the packets were received by this code,
     * which may be different than the order according to sequence number.
     */
    private var previousPacketReceivedTimestampMs: Long = -1
    private var previousPacketSentTimestampMs: Long = -1

    fun addPacket(currentPacketSentTimestampMs: Long, currentPacketReceivedTimestampMs: Long) {
        if (previousPacketReceivedTimestampMs != -1L) {
            jitter = calculateJitter(
                jitter,
                previousPacketSentTimestampMs,
                previousPacketReceivedTimestampMs,
                currentPacketSentTimestampMs,
                currentPacketReceivedTimestampMs
            )
        }
        previousPacketSentTimestampMs = currentPacketSentTimestampMs
        previousPacketReceivedTimestampMs = currentPacketReceivedTimestampMs
    }

    companion object {
        fun calculateJitter(
            currentJitter: Double,
            previousPacketSentTimestamp: Long,
            previousPacketReceivedTimestamp: Long,
            currentPacketSentTimestamp: Long,
            currentPacketReceivedTimestamp: Long
        ): Double {
            /**
             * If Si is the RTP timestamp from packet i, and Ri is the time of
             * arrival in RTP timestamp units for packet i, then for two packets
             * i and j, D may be expressed as
             *
             * D(i,j) = (Rj - Ri) - (Sj - Si) = (Rj - Sj) - (Ri - Si)
             */
            // TODO(boris) take wraps into account
            val delta = (previousPacketReceivedTimestamp - previousPacketSentTimestamp) -
                (currentPacketReceivedTimestamp - currentPacketSentTimestamp)

            /**
             * The interarrival jitter SHOULD be calculated continuously as each
             * data packet i is received from source SSRC_n, using this
             * difference D for that packet and the previous packet i-1 in order
             * of arrival (not necessarily in sequence), according to the formula
             *
             * J(i) = J(i-1) + (|D(i-1,i)| - J(i-1))/16
             *
             * Whenever a reception report is issued, the current value of J is
             * sampled.
             */
            return currentJitter + (abs(delta) - currentJitter) / 16.0
        }
    }
}

/**
 * Tracks the jitter of packets *within* the bridge (not over the network)
 */
class BridgeJitterStats : JitterStats() {

    fun packetSent(packetInfo: PacketInfo) {
        if (packetInfo.receivedTime >= 0) {
            super.addPacket(packetInfo.receivedTime, System.currentTimeMillis())
        }
    }
}
