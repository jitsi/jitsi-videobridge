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
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.RtcpFbTccPacket
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.RtcpFbTccPacketBuilder
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.rtp.rtp.header_extensions.TccHeaderExtension
import unsigned.toUInt
import java.util.TreeMap

/**
 * Extract the TCC sequence numbers from each passing packet and generate
 * a TCC packet to send transmit to the sender.
 */
class TccGeneratorNode(
    private val onTccPacketReady: (RtcpPacket) -> Unit = {}
) : ObserverNode("TCC generator") {
    private var tccExtensionId: Int? = null
    private var currTccSeqNum: Int = 0
    private var lastTccSentTime: Long = 0
    // Tcc seq num -> arrival time in ms
    private val packetArrivalTimes = TreeMap<Int, Long>()
    // The first sequence number of the current tcc feedback packet
    private var windowStartSeq: Int = -1
    /**
     * SSRCs we've been told this endpoint will transmit on.  We'll use an
     * SSRC from this list for the RTCPFB mediaSourceSsrc field in the
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
            rtpPacket.getHeaderExtension(tccExtId)?.let { ext ->
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

    //TODO: we don't handle tcc seq num rollover here, but the old code didn't seem to either?
    private fun addPacket(tccSeqNum: Int, timestamp: Long, isMarked: Boolean) {
        println("${System.identityHashCode(this)}  received seq num $tccSeqNum with recv timestamp $timestamp")
        if (packetArrivalTimes.ceilingKey(windowStartSeq) == null) {
            // Packets in map are all older than the start of the next tcc feedback packet,
            // remove them
            //TODO: chrome does something more advanced. is this good enough?
            packetArrivalTimes.clear()
        }
        if (windowStartSeq == -1) {
            windowStartSeq = tccSeqNum
        } else if (tccSeqNum < windowStartSeq) {
            windowStartSeq = tccSeqNum
        }
        packetArrivalTimes.putIfAbsent(tccSeqNum, timestamp)
        if (isTccReadyToSend(isMarked)) {
            sendPeriodicFeedbacks()
        }
    }

    private fun sendPeriodicFeedbacks() {
        val tccBuilder = RtcpFbTccPacketBuilder(
            mediaSourceSsrc = mediaSsrcs.firstOr(-1L),
            feedbackPacketSeqNum = currTccSeqNum++
        )
        // windowStartSeq is the first sequence number to include in the current feedback, but we may not have
        // received it so the base time shall be the time of the first received packet which will be included
        // in this feedback
        val firstEntry = packetArrivalTimes.ceilingEntry(windowStartSeq)
        println("${System.identityHashCode(this)}  Creating new tcc.  window start seq is $windowStartSeq, first received packet is " +
            "${firstEntry.key} received at ${firstEntry.value}")
        tccBuilder.SetBase(windowStartSeq, firstEntry.value * 1000)

        var nextSequenceNumber = windowStartSeq
        val feedbackBlockPackets = packetArrivalTimes.tailMap(windowStartSeq)
        feedbackBlockPackets.forEach { (seqNum, timestampMs) ->
            println("${System.identityHashCode(this)}  adding seq num $seqNum with recv timestamp $timestampMs")
            if (!tccBuilder.AddReceivedPacket(seqNum, timestampMs * 1000)) {
                return@forEach
            }
            nextSequenceNumber = seqNum + 1
        }
        sendTcc(tccBuilder.build())

        // The next window will start with the sequence number after the last one we included in the previous
        // feedback
        windowStartSeq = nextSequenceNumber
    }

    private fun sendTcc(tccPacket: RtcpFbTccPacket) {
        onTccPacketReady(tccPacket)
        numTccSent++
        lastTccSentTime = System.currentTimeMillis()
    }

    private fun isTccReadyToSend(currentPacketMarked: Boolean): Boolean {
        val timeSinceLastTcc = if (lastTccSentTime == -1L) 0 else System.currentTimeMillis() - lastTccSentTime
        return timeSinceLastTcc >= 100 ||
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
