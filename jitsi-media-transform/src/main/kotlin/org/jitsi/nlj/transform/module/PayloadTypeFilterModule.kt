/*
 * Copyright @ 2018 Atlassian Pty Ltd
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
package org.jitsi.nlj.transform.module

import org.jitsi.nlj.Event
import org.jitsi.nlj.RtpPayloadTypeAddedEvent
import org.jitsi.nlj.RtpPayloadTypeClearEvent
import org.jitsi.rtp.Packet
import org.jitsi.rtp.SrtpPacket
import unsigned.toUInt
import java.util.concurrent.ConcurrentHashMap

class PayloadTypeFilterModule : Module("RTP payload type filter") {
    private val acceptedPayloadTypes: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    override fun doProcessPackets(p: List<Packet>) {
        val filteredPackets = p
            .map { it as SrtpPacket }
            .filter { acceptedPayloadTypes.contains(it.header.payloadType) }
            .toList()
        next(filteredPackets)
    }

    override fun handleEvent(event: Event) {
        when (event) {
            is RtpPayloadTypeAddedEvent -> {
                println("BRIAN: payload type filter ${hashCode()} now accepting PT ${event.payloadType.toUInt()}")
                acceptedPayloadTypes.add(event.payloadType.toUInt())
            }
            is RtpPayloadTypeClearEvent -> acceptedPayloadTypes.clear()
        }
        super.handleEvent(event)
    }
}
