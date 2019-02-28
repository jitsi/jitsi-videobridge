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

package org.jitsi.rtp.rtcp.rtcpfb.fci.tcc

// https://tools.ietf.org/html/draft-holmer-rmcat-transport-wide-cc-extensions-01#section-3.1.1
interface PacketStatusSymbol {
    fun hasDelta(): Boolean
    fun getDeltaSizeBytes(): Int
    val value: Int
}

object UnknownSymbol : PacketStatusSymbol {
    override val value: Int = -1
    override fun hasDelta(): Boolean = false
    override fun getDeltaSizeBytes(): Int = throw Exception()
}

class InvalidDeltaException(deltaMs: Double) : Exception("Invalid delta found: $deltaMs")

/**
 * Note that although the spec says:
 * "packet received" (0) and "packet not received" (1)
 * Chrome actually has it backwards ("packet received" uses the value
 * 1 and "packet not received" uses the value 0, so here we go against
 * the spec to match chrome.
 * Chrome file:
 * https://codesearch.chromium.org/chromium/src/third_party/webrtc/modules/rtp_rtcp/source/rtcp_packet/transport_feedback.cc?l=185&rcl=efbcb31cb67e3090b82c09ed5aabc4bbc53f37be
 */
//TODO: we will be unable to turn a received TCC packet with one bit symbols into one
// with 2 bit symbols because two bit symbols don't support a status of 'received but
// with no delta'
//NOTE: the old code interprets 'RECEIVED' for one bit symbols as having a small delta.  I haven't
// seen a part in the spec that defines this, but chrome appears to treat it this way
enum class OneBitPacketStatusSymbol(override val value: Int) : PacketStatusSymbol {
    RECEIVED(1),
    NOT_RECEIVED(0);

    companion object {
        private val map = OneBitPacketStatusSymbol.values().associateBy(OneBitPacketStatusSymbol::value);
        fun fromInt(type: Int): PacketStatusSymbol = map.getOrDefault(type, UnknownSymbol)
    }

    override fun hasDelta(): Boolean = this == RECEIVED
    override fun getDeltaSizeBytes(): Int = 1
}

enum class TwoBitPacketStatusSymbol(override val value: Int) : PacketStatusSymbol {
    NOT_RECEIVED(0),
    RECEIVED_SMALL_DELTA(1),
    RECEIVED_LARGE_OR_NEGATIVE_DELTA(2);

    companion object {
        private val map = TwoBitPacketStatusSymbol.values().associateBy(TwoBitPacketStatusSymbol::value)
        fun fromInt(type: Int): PacketStatusSymbol = map.getOrDefault(type, UnknownSymbol)
        // This method assumes only supports cases where the given delta falls into
        // one of the two delta ranges
        fun fromDeltaMs(deltaMs: Double): PacketStatusSymbol {
            return when (deltaMs) {
                in 0.0..63.75 -> RECEIVED_SMALL_DELTA
                in -8192.0..8191.75 -> RECEIVED_LARGE_OR_NEGATIVE_DELTA
                else -> throw InvalidDeltaException(deltaMs)
            }
        }
    }

    override fun hasDelta(): Boolean {
        return this == RECEIVED_SMALL_DELTA ||
                this == RECEIVED_LARGE_OR_NEGATIVE_DELTA
    }

    override fun getDeltaSizeBytes(): Int {
        return when (this) {
            RECEIVED_SMALL_DELTA -> 1
            RECEIVED_LARGE_OR_NEGATIVE_DELTA -> 2
            else -> 0
        }
    }
}
