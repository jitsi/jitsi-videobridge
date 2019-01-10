package org.jitsi.nlj.stats

import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.rtcp.RtcpListener
import org.jitsi.nlj.util.cinfo
import org.jitsi.nlj.util.getLogger
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.rtp.rtcp.RtcpReportBlock
import org.jitsi.rtp.rtcp.RtcpRrPacket
import org.jitsi.rtp.rtcp.RtcpSrPacket
import java.util.concurrent.ConcurrentHashMap

/**
 * This class models stats which comes from SRs
 */
class DownlinkStreamStats : RtcpListener {
    // Maps the compacted NTP timestamp found in an SR SenderInfo to the clock time (in milliseconds)
    //  at which it was transmitted
    private val srSentTimes: MutableMap<Long, Long> = ConcurrentHashMap()
    protected val logger = getLogger(this.javaClass)

    override fun onRtcpPacketReceived(packetInfo: PacketInfo) {
        val packet = packetInfo.packet
        when (packet) {
            is RtcpSrPacket -> {
                logger.cinfo { "Received SR packet with ${packet.reportBlocks} report blocks" }
                packet.reportBlocks.forEach { reportBlock -> processReportBlock(packetInfo.receivedTime, reportBlock) }
            }
            is RtcpRrPacket -> {
                logger.cinfo { "Received RR packet with ${packet.reportBlocks} report blocks" }
                packet.reportBlocks.forEach { reportBlock -> processReportBlock(packetInfo.receivedTime, reportBlock) }
            }
        }
    }

    override fun onRtcpPacketSent(packet: RtcpPacket) {
        when (packet) {
            is RtcpSrPacket -> {
                logger.cinfo { "Tracking sent SR packet with NTP timestamp ${packet.senderInfo.ntpTimestamp} and " +
                        "compacted timestamp ${packet.senderInfo.compactedNtpTimestamp}" }
                srSentTimes[packet.senderInfo.compactedNtpTimestamp] =
                        System.currentTimeMillis()
            }
        }
    }

    private fun processReportBlock(receivedTime: Long, reportBlock: RtcpReportBlock) {
        if (reportBlock.lastSrTimestamp > 0 && reportBlock.delaySinceLastSr > 0) {
            // We need to know when we sent the last SR
            val srSentTime = srSentTimes.getOrDefault(reportBlock.lastSrTimestamp, -1)
            if (srSentTime > 0) {
                // The delaySinceLastSr value is given in 1/65536ths of a second, so divide it by 65.536 to get it
                // in milliseconds
                val remoteProcessingDelayMs = reportBlock.delaySinceLastSr / 65.536
                //TODO: store the RTT
                val rtt = receivedTime - srSentTime - remoteProcessingDelayMs
            }
        }
    }
}