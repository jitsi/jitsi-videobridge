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

import org.jitsi.nlj.Event
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.ReceiveSsrcAddedEvent
import org.jitsi.nlj.ReceiveSsrcRemovedEvent
import org.jitsi.nlj.RtpExtensionAddedEvent
import org.jitsi.nlj.RtpExtensionClearEvent
import org.jitsi.nlj.forEachAs
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.node.Node
import org.jitsi.nlj.util.cinfo
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.rtp.rtcp.rtcpfb.RtcpFbTccPacket
import org.jitsi.rtp.rtcp.rtcpfb.fci.tcc.Tcc
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.service.neomedia.RTPExtension
import unsigned.toUInt

/**
 * Extract the TCC sequence numbers from each passing packet and generate
 * a TCC packet to send transmit to the sender.
 */
class TccGeneratorNode(
    private val onTccPacketReady: (RtcpPacket) -> Unit = {}
) : Node("TCC generator") {
    private var tccExtensionId: Int? = null
    private var currTccSeqNum: Int = 0
    private var currTcc: RtcpFbTccPacket = RtcpFbTccPacket(fci = Tcc(feedbackPacketCount = currTccSeqNum++))
    private var lastTccSentTime: Long = 0
    /**
     * Ssrc's we've been told this endpoint will transmit on.  We'll use an
     * ssrc from this list for the RTCPFB mediaSourceSsrc field in the
     * TCC packets we generate
     */
    private var mediaSsrcs: MutableSet<Long> = mutableSetOf()
    private var numTccSent: Int = 0

    override fun doProcessPackets(p: List<PacketInfo>) {
        tccExtensionId?.let { tccExtId ->
            p.forEachAs<RtpPacket> { pktInfo, pkt ->
                pkt.header.getExtension(tccExtId).let currPkt@ { tccExt ->
                    //TODO: check if it's a one byte or two byte ext?
                    // TODO: add a tcc ext type that handles the seq num parsing?
                    val tccSeqNum = tccExt?.data?.getShort(0)?.toUInt() ?: return@currPkt
                    addPacket(tccSeqNum, pktInfo.receivedTime)
                }
            }
        }
        next(p)
    }

    override fun handleEvent(event: Event) {
        when (event) {
            is RtpExtensionAddedEvent -> {
                if (RTPExtension.TRANSPORT_CC_URN.equals(event.rtpExtension.uri.toString())) {
                    tccExtensionId = event.extensionId.toUInt()
                    logger.cinfo { "TCC generator setting extension ID to $tccExtensionId" }
                }
            }
            is RtpExtensionClearEvent -> tccExtensionId = null
            is ReceiveSsrcAddedEvent -> mediaSsrcs.add(event.ssrc)
            is ReceiveSsrcRemovedEvent -> mediaSsrcs.remove(event.ssrc)
        }
    }

    private fun addPacket(tccSeqNum: Int, timestamp: Long) {
        currTcc.addPacket(tccSeqNum, timestamp)

        if (isTccReadyToSend()) {
            val mediaSsrc = if (mediaSsrcs.isNotEmpty()) mediaSsrcs.iterator().next() else -1L
            currTcc.mediaSourceSsrc = mediaSsrc
            onTccPacketReady(currTcc)
            numTccSent++
            // Create a new TCC instance for the next set of information
            currTcc = RtcpFbTccPacket(fci = Tcc(feedbackPacketCount = currTccSeqNum++))
        }
    }

    private fun isTccReadyToSend(): Boolean {
        return (System.currentTimeMillis() - lastTccSentTime >= 70) ||
            currTcc.numPackets >= 20
    }

    override fun getNodeStats(): NodeStatsBlock {
        val parentStats = super.getNodeStats()
        return NodeStatsBlock(name).apply {
            addAll(parentStats)
            addStat( "num tcc packets sent: $numTccSent")
        }
    }
}
