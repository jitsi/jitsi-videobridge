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

import org.jitsi.rtp.Packet
import org.jitsi.rtp.SrtpPacket
import org.jitsi.service.neomedia.format.MediaFormat
import unsigned.toUInt
import java.util.concurrent.ConcurrentHashMap

class PayloadTypeFilterModule : Module("RTP payload type filter") {
    private val acceptedPayloadTypes: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    override fun doProcessPackets(p: List<Packet>) {
        val filteredPackets = p
            .map { it as SrtpPacket }
            .filter { acceptedPayloadTypes.contains(it.header.payloadType) }
            .toList()
        if (filteredPackets.isNotEmpty()) {
            next(filteredPackets)
        }
    }

    override fun onRtpPayloadTypeAdded(payloadType: Byte, format: MediaFormat) {
        println("BRIAN: payload type filter ${hashCode()} now accepting PT ${payloadType.toUInt()}")
        acceptedPayloadTypes.add(payloadType.toUInt())
    }

    override fun onRtpPayloadTypeRemoved(payloadType: Byte) {
        acceptedPayloadTypes.clear()
    }
}
