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

package org.jitsi.nlj.rtcp

import org.jitsi.rtp.rtcp.RtcpPacket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A central place to allow the publishing of when RTCP packets are recieved or sent.  We're
 * interested in both of these scenarios for things like SRs, RRs and for RTT calculations
 */
// TODO(brian): maybe post the notifications to another pool, so we don't hold up the caller?
class RtcpEventNotifier {
    private val rtcpListeners: MutableList<RtcpListener> = CopyOnWriteArrayList<RtcpListener>()

    fun addRtcpEventListener(listener: RtcpListener) {
        rtcpListeners.add(listener)
    }

    fun notifyRtcpReceived(packet: RtcpPacket, receivedTime: Long) {
        rtcpListeners.forEach { it.rtcpPacketReceived(packet, receivedTime) }
    }

    fun notifyRtcpSent(rtcpPacket: RtcpPacket) {
        rtcpListeners.forEach { it.rtcpPacketSent(rtcpPacket) }
    }
}