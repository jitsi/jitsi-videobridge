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

package org.jitsi.nlj.transform.node.outgoing

import org.jitsi.nlj.Event
import org.jitsi.nlj.PacketHandler
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.RtpPayloadTypeClearEvent
import org.jitsi.nlj.SsrcAssociationEvent
import org.jitsi.nlj.rtp.VideoRtpPacket
import org.jitsi.nlj.transform.node.Node
import org.jitsi.nlj.util.getByteBuffer
import org.jitsi_modified.impl.neomedia.rtp.NewRawPacketCache

/**
 * [ProbingDataSender] currently supports probing via 2 methods:
 * 1) retransmitting previous packets via RTX via [sendRedundantDataOverRtx].
 * 2) If RTX is not available, or, not enough packets to retransmit are available, we
 * can send empty media packets using the bridge's ssrc
 *
 */
class ProbingDataSender(
    private val packetCache: NewRawPacketCache,
    private val rtxDataSender: PacketHandler/*,
    private val garbageDataSender: PacketHandler TODO*/) {

    fun sendProbing(mediaSsrc: Long, numBytes: Int): Int {
        //TODO(brian): in the future we'll check if RTX is supported and, if not, send it using the
        // bridge's ssrc
        //TODO(brian): also, if we can't send the full amount of data using RTX (because there are not
        // the proper packets to retransmit to fill it) send data using bridge ssrc
        return sendRedundantDataOverRtx(mediaSsrc, numBytes)
    }
    /**
     * Using the RTX stream associated with [mediaSsrc], send [numBytes] of data
     * by re-transmitting previously sent packets from the outgoing packet cache.
     * Returns the number of bytes transmitted
     */
    private fun sendRedundantDataOverRtx(mediaSsrc: Long, numBytes: Int): Int {
        var bytesSent = 0
        val lastNPackets =
                packetCache.getMany(mediaSsrc, numBytes) ?: return bytesSent

        // XXX this constant is not great, however the final place of the stream
        // protection strategy is not clear at this point so I expect the code
        // will change before taking its final form.
        val packetsToResend = mutableListOf<PacketInfo>()
        for (i in 0 until 2) {
            val lastNPacketIter = lastNPackets.iterator();

            while (lastNPacketIter.hasNext())
            {
                val container = lastNPacketIter.next()
                val rawPacket = container.pkt
                // Containers are recycled/reused, so we must check if the
                // packet is still there.
                if (rawPacket != null)
                {
                    val len = rawPacket.length;
                    if (bytesSent + len > numBytes) {
                        // We don't have enough 'room' to send this packet.  We're done
                        break
                    }
                    bytesSent += len
                    // The node after this one will be the RetransmissionSender, which handles
                    // encapsulating packets as RTX (with the proper ssrc and payload type) so we
                    // just need to find the packets to retransmit and forward them to the next node
                    packetsToResend.add(PacketInfo(VideoRtpPacket(rawPacket.getByteBuffer())))
//                    Byte apt = rtx2apt.get(container.pkt.getPayloadType());
//
//                    // XXX if the client doesn't support RTX, then we can not
//                    // effectively ramp-up bwe using duplicates because they
//                    // would be dropped too early in the SRTP layer. So we are
//                    // forced to use the bridge's SSRC and thus increase the
//                    // probability of losses.
//
//                    if (bytes - len > 0 && apt != null)
//                    {
//                        retransmit(container.pkt, apt, this);
//                        bytes -= len;
//                    }
//                    else
//                    {
//                        // Don't break as we might be able to squeeze in the
//                        // next packet.
//                    }
                }
            }
        }
        //TODO(brian): we're in a thread context mess here.  we'll be sending these out from the bandwidthprobing
        // context (or whoever calls this) which i don't think we want.  Need look at getting all the pipeline
        // work posted to one thread so we don't have to worry about concurrency nightmares
        rtxDataSender.processPackets(packetsToResend)

        return bytesSent
    }

//    override fun handleEvent(event: Event) {
//        when(event) {
//            is RtpPayloadTypeAddedEvent -> {
//                if (event.payloadType is RtxPayloadType) {
//                    val rtxPt = event.payloadType.pt.toUInt()
//                    event.payloadType.parameters["apt"]?.toByte()?.toUInt()?.let {
//                        val associatedPt = it
//                        logger.cinfo { "Retransmission sender ${hashCode()} associating RTX payload type " +
//                                "$rtxPt with primary $associatedPt" }
//                        associatedPayloadTypes[associatedPt] = rtxPt
//                    } ?: run {
//                        logger.cerror { "Unable to parse RTX associated payload type from event: $event" }
//                    }
//                }
//            }
//            is RtpPayloadTypeClearEvent -> {
//                associatedPayloadTypes.clear()
//            }
//            is SsrcAssociationEvent -> {
//                if (event.type == SsrcAssociationType.RTX) {
//                    logger.cinfo { "Retransmission sender ${hashCode()} associating RTX ssrc " +
//                            "${event.secondarySsrc} with primary ${event.primarySsrc}" }
//                    associatedSsrcs[event.primarySsrc] = event.secondarySsrc
//                }
//            }
//        }
//        super.handleEvent(event)
//    }


}