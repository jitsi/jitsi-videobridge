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
import org.jitsi.nlj.rtp.RtpExtensionType.TRANSPORT_CC
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.node.ObserverNode
import org.jitsi.nlj.util.cinfo
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.RtcpFbTccPacketBuilder
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.rtp.rtp.header_extensions.TccHeaderExtension
import unsigned.toUInt

/**
 * Extract the TCC sequence numbers from each passing packet and generate
 * a TCC packet to send transmit to the sender.
 */
//TODO: if, after sending a tcc feedback packet the next sequence number(s) are lost, we need
// to make sure they get represented as not received
class TccGeneratorNode(
    private val onTccPacketReady: (RtcpPacket) -> Unit = {}
) : ObserverNode("TCC generator") {
    private var tccExtensionId: Int? = null
    private var currTccSeqNum: Int = 0
    private var currTccBuilder: RtcpFbTccPacketBuilder = RtcpFbTccPacketBuilder(
        feedbackPacketCount = currTccSeqNum++
    )
    private var lastTccSentTime: Long = 0
    /**
     * Ssrc's we've been told this endpoint will transmit on.  We'll use an
     * ssrc from this list for the RTCPFB mediaSourceSsrc field in the
     * TCC packets we generate
     */
    private var mediaSsrcs: MutableSet<Long> = mutableSetOf()
    private fun <T>MutableSet<T>.firstOr(defaultValue: T): T {
        val iter = iterator()
        return if (iter.hasNext()) iter.next() else defaultValue
    }
    private var numTccSent: Int = 0

    override fun observe(packetInfo: PacketInfo) {
        tccExtensionId?.let { tccExtId ->
            val rtpPacket = packetInfo.packetAs<RtpPacket>()
            rtpPacket.getHeaderExtension(tccExtId.toByte())?.let { ext ->
                val tccSeqNum = TccHeaderExtension.getSequenceNumber(ext)
                addPacket(tccSeqNum, packetInfo.receivedTime, rtpPacket.isMarked)
            }
        }
    }

    override fun handleEvent(event: Event) {
        when (event) {
            is RtpExtensionAddedEvent -> {
                if (event.rtpExtension.type == TRANSPORT_CC) {
                    tccExtensionId = event.rtpExtension.id.toUInt()
                    logger.cinfo { "TCC generator setting extension ID to $tccExtensionId" }
                }
            }
            is RtpExtensionClearEvent -> tccExtensionId = null
            is ReceiveSsrcAddedEvent -> mediaSsrcs.add(event.ssrc)
            is ReceiveSsrcRemovedEvent -> mediaSsrcs.remove(event.ssrc)
        }
    }

    private fun addPacket(tccSeqNum: Int, timestamp: Long, isMarked: Boolean) {
        currTccBuilder.addPacket(tccSeqNum, timestamp)

        if (isTccReadyToSend(isMarked)) {
            val mediaSsrc = mediaSsrcs.firstOr(-1L)
            currTccBuilder.mediaSourceSsrc = mediaSsrc
            onTccPacketReady(currTccBuilder.build())
            numTccSent++
            lastTccSentTime = System.currentTimeMillis()
            // Create a new TCC instance for the next set of information
            currTccBuilder = RtcpFbTccPacketBuilder(feedbackPacketCount = currTccSeqNum++)
        }
    }

    private fun isTccReadyToSend(currentPacketMarked: Boolean): Boolean {
        val timeSinceLastTcc = if (lastTccSentTime == -1L) 0 else System.currentTimeMillis() - lastTccSentTime
        return timeSinceLastTcc >= 100 ||
            currTccBuilder.numPackets >= 100 ||
            ((timeSinceLastTcc >= 20) && currentPacketMarked)
    }

    override fun getNodeStats(): NodeStatsBlock {
        val parentStats = super.getNodeStats()
        return NodeStatsBlock(name).apply {
            addAll(parentStats)
            addStat( "num tcc packets sent: $numTccSent")
        }
    }
}
