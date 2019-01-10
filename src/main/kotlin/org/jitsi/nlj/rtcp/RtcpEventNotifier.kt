package org.jitsi.nlj.rtcp

import org.jitsi.nlj.PacketInfo
import org.jitsi.rtp.rtcp.RtcpPacket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A central place to allow the publishing of when RTCP packets are recieved or sent.  We're
 * interested in both of these scenarios for things like SRs, RRs and for RTT calculations
 */
//TODO(brian): maybe post the notifcations to another pool, so we don't hold up the caller?
class RtcpEventNotifier {
    private val rtcpListeners: MutableList<RtcpListener> = CopyOnWriteArrayList<RtcpListener>()

    fun addRtcpEventListener(listener: RtcpListener) {
        rtcpListeners.add(listener)
    }

    fun notifyRtcpReceived(packetInfo: PacketInfo) {
        rtcpListeners.forEach { it.onRtcpPacketReceived(packetInfo) }
    }

    fun notifyRtcpSent(rtcpPacket: RtcpPacket) {
        rtcpListeners.forEach { it.onRtcpPacketSent(rtcpPacket) }
    }
}