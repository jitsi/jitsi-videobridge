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
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A central place to allow the publishing of when RTCP packets are received or sent.  We're
 * interested in both of these scenarios for things like SRs, RRs and for RTT calculations
 */
// TODO(brian): maybe post the notifications to another pool, so we don't hold up the caller?
class RtcpEventNotifier {
    private val rtcpListeners: MutableList<Pair<RtcpListener, Boolean>> = CopyOnWriteArrayList()

    /**
     * Add an [RtcpListener].  An [external] listener will not receive notifications that are themselves
     * marked as external.  This allows notifications to be passed between multiple listeners without
     * creating an infinite loop.
     */
    fun addRtcpEventListener(listener: RtcpListener, external: Boolean = false) {
        rtcpListeners.add(Pair(listener, external))
    }

    fun notifyRtcpReceived(packet: RtcpPacket, receivedTime: Instant?, external: Boolean = false) {
        rtcpListeners.forEach { (listener, listenerIsExternal) ->
            if (!external || !listenerIsExternal) listener.rtcpPacketReceived(packet, receivedTime)
        }
    }

    fun notifyRtcpSent(rtcpPacket: RtcpPacket, external: Boolean = false) {
        rtcpListeners.forEach { (listener, listenerIsExternal) ->
            if (!external || !listenerIsExternal) listener.rtcpPacketSent(rtcpPacket)
        }
    }
}
