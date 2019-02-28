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
package org.jitsi.nlj.transform.node

import org.jitsi.nlj.Event
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.RtpPayloadTypeAddedEvent
import org.jitsi.nlj.RtpPayloadTypeClearEvent
import org.jitsi.nlj.util.cinfo
import org.jitsi.rtp.extensions.unsigned.toPositiveInt
import org.jitsi.rtp.rtp.RtpPacket
import java.util.concurrent.ConcurrentHashMap

class PayloadTypeFilterNode : Node("RTP payload type filter") {
    private val acceptedPayloadTypes: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    override fun doProcessPackets(p: List<PacketInfo>) {
        val filteredPackets = p
            .filter { acceptedPayloadTypes.contains(it.packetAs<RtpPacket>().header.payloadType) }
        next(filteredPackets)
    }

    override fun handleEvent(event: Event) {
        when (event) {
            is RtpPayloadTypeAddedEvent -> {
                logger.cinfo { "Payload type filter ${hashCode()} now accepting PT ${event.payloadType.pt.toPositiveInt()}" }
                acceptedPayloadTypes.add(event.payloadType.pt.toPositiveInt())
            }
            is RtpPayloadTypeClearEvent -> acceptedPayloadTypes.clear()
        }
        super.handleEvent(event)
    }
}
