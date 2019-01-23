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
package org.jitsi.nlj.transform.node.incoming

import org.jitsi_modified.impl.neomedia.transform.RetransmissionRequesterImpl
import org.jitsi.nlj.Event
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.RtpPayloadTypeAddedEvent
import org.jitsi.nlj.RtpPayloadTypeClearEvent
import org.jitsi.nlj.transform.node.Node
import org.jitsi.nlj.util.cinfo
import org.jitsi.nlj.util.getByteBuffer
import org.jitsi.nlj.util.toRawPacket
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.service.neomedia.RawPacket
import unsigned.toUInt

class RetransmissionRequester(rtcpSender: (RtcpPacket) -> Unit) : Node("Retransmission requester") {
    /**
     * Wrap the given [rtcpSender] method with one that takes a RawPacket (which
     * is what the retransmission requester will use).
     */
    private val rtcpSenderAdapter: (RawPacket) -> Unit = { rawPacket ->
        val rtcpPacket = RtcpPacket.fromBuffer(rawPacket.getByteBuffer())
        rtcpSender(rtcpPacket)
    }

    private val retransmissionRequester = RetransmissionRequesterImpl(rtcpSenderAdapter)

    override fun doProcessPackets(p: List<PacketInfo>) {
        p.forEach { packetInfo ->
            val rp = packetInfo.packet.toRawPacket()
            retransmissionRequester.reverseTransform(rp)
        }
        next(p)
    }

    override fun handleEvent(event: Event) {
        when (event) {
            is RtpPayloadTypeAddedEvent -> {
                logger.cinfo { "RetransmissionRequester ${hashCode()} now accepting " +
                        "PT ${event.payloadType.pt.toUInt()}" }
                retransmissionRequester.payloadTypes[event.payloadType.pt] = event.payloadType
            }
            is RtpPayloadTypeClearEvent -> retransmissionRequester.payloadTypes.clear()
        }
        super.handleEvent(event)
    }

    override fun stop() {
        super.stop()
        retransmissionRequester.close()
    }
}
