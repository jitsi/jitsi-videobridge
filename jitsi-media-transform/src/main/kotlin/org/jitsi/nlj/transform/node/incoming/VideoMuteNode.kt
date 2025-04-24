package org.jitsi.nlj.transform.node.incoming

import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.rtp.VideoRtpPacket
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.node.ObserverNode

class VideoMuteNode : ObserverNode("VideoMuteNode") {

    private var numMutedPackets = 0
    var forceMute: Boolean = false

    override fun observe(packetInfo: PacketInfo) {
        if (packetInfo.packet !is VideoRtpPacket) return
        if (this.forceMute) {
            packetInfo.shouldDiscard = true
            numMutedPackets++
        }
    }

    override fun getNodeStats(): NodeStatsBlock = super.getNodeStats().apply {
        addNumber("num_video_packets_discarded", numMutedPackets)
        addBoolean("force_mute", forceMute)
    }

    override fun statsJson() = super.statsJson().apply {
        this["num_video_packets_discarded"] = numMutedPackets
        this["force_mute"] = forceMute
    }

    override fun trace(f: () -> Unit) = f.invoke()
}
