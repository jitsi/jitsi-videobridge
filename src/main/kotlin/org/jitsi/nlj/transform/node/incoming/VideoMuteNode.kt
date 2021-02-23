package org.jitsi.nlj.transform.node.incoming

import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.rtp.VideoRtpPacket
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.node.ObserverNode

class VideoMuteNode : ObserverNode("Video mute node") {

    private var numMutedPackets = 0
    var forceMute: Boolean = false

    override fun observe(packetInfo: PacketInfo) {
        val videoRtpPacket = packetInfo.packet as? VideoRtpPacket ?: return
        if (this.forceMute) {
            packetInfo.shouldDiscard = true
            numMutedPackets++
        }
    }

    override fun getNodeStats(): NodeStatsBlock = super.getNodeStats().apply {
        addNumber("num_video_packets_discarded", numMutedPackets)
    }

    override fun trace(f: () -> Unit) = f.invoke()
}
