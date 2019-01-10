package org.jitsi.nlj.rtcp

import org.jitsi.nlj.PacketInfo
import org.jitsi.rtp.rtcp.RtcpPacket

interface RtcpListener {
    fun onRtcpPacketReceived(packetInfo: PacketInfo) {}
    fun onRtcpPacketSent(packet: RtcpPacket) {}
}

