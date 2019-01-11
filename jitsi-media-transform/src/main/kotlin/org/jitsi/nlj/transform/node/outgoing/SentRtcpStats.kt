package org.jitsi.nlj.transform.node.outgoing

import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.forEachAs
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.node.Node
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.rtp.rtcp.rtcpfb.RtcpFbFirPacket
import org.jitsi.rtp.rtcp.rtcpfb.RtcpFbPliPacket

class SentRtcpStats : Node("Sent RTCP stats") {
    private var numPlisSent = 0
    private var numFirsSent = 0

    override fun doProcessPackets(p: List<PacketInfo>) {
        p.forEachAs<RtcpPacket> { packetInfo, expectedPacketType ->
            when (expectedPacketType) {
                is RtcpFbPliPacket -> numPlisSent++
                is RtcpFbFirPacket -> numFirsSent++
            }
        }
        next(p)
    }

    override fun getNodeStats(): NodeStatsBlock {
        val parentStats = super.getNodeStats()
        return NodeStatsBlock(name).apply {
            addAll(parentStats)
            addStat("num PLI packets tx: $numPlisSent")
            addStat("num FIR packets tx: $numFirsSent")
        }
    }
}