package org.jitsi.nlj.rtcp

import org.jitsi.nlj.PacketInfo

interface RtcpListener {
    fun onRtcpPacket(packetInfo: PacketInfo)
}

