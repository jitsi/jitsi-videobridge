package org.jitsi.nlj

import io.kotlintest.specs.ShouldSpec
import io.pkts.Pcap
import io.pkts.packet.UDPPacket
import io.pkts.packet.rtcp.RtcpPacket
import io.pkts.protocol.Protocol
import org.jitsi.nlj.transform2.RtpReceiver
import org.jitsi.nlj.transform2.RtpReceiverImpl
import org.jitsi.nlj.transform2.RtpSenderImpl
import org.jitsi.rtp.Packet
import org.jitsi.rtp.RtpPacket
import java.lang.Thread.sleep
import java.nio.ByteBuffer
import java.util.concurrent.Executors

fun getSsrcsInPcap(filePath: String): Set<Long> {
    val pcap = Pcap.openStream(filePath)
    val ssrcs = mutableSetOf<Long>()
    pcap.loop { pkt ->
        if (pkt.hasProtocol(Protocol.UDP)) {
            val udpPacket = pkt.getPacket(Protocol.UDP) as UDPPacket
            val buf = ByteBuffer.wrap(udpPacket.payload.array)
            try {
                val p = Packet.parse(buf)
                val ssrc = when (p) {
                    is RtpPacket -> p.header.ssrc
                    is org.jitsi.rtp.rtcp.RtcpPacket -> p.header.senderSsrc
                    else -> throw Exception()
                }
                ssrcs.add(ssrc)
            } catch (e: Exception) {
//                println(e.message)
            } catch (e: Error) {
//                println(e.message)
            }
        }
        true
    }
    return ssrcs
}

internal class ThreadingmainKtTest : ShouldSpec() {
    init {
        val trackExecutor = Executors.newFixedThreadPool(4)
        val b = Bridge(trackExecutor)

        val pcap = Pcap.openStream("/Users/bbaldino/2_participants_sim_sending_to_bridge.pcap")

        val ssrcs = getSsrcsInPcap("/Users/bbaldino/2_participants_sim_sending_to_bridge.pcap")
        println("ssrc in trace: $ssrcs")
        val receivers = ssrcs.map { ssrc ->
            ssrc to RtpReceiverImpl(ssrc, trackExecutor, b::onIncomingPackets)
        }.toMap()
        val senders = ssrcs.map { ssrc ->
            ssrc to RtpSenderImpl(ssrc, trackExecutor)
        }.toMap()

        senders.forEach(b::addSender)

        pcap.loop { pkt ->
            if (pkt.hasProtocol(Protocol.UDP)) {
                val udpPacket = pkt.getPacket(Protocol.UDP) as UDPPacket
                val buf = ByteBuffer.wrap(udpPacket.payload.array)
                try {
                    val p = Packet.parse(buf)
//                    println(p)
                    val ssrc = when(p) {
                        is RtpPacket -> p.header.ssrc
                        is org.jitsi.rtp.rtcp.RtcpPacket -> p.header.senderSsrc
                        else -> throw Exception("can't get ssrc of packet")
                    }
                    receivers[ssrc]?.enqueuePacket(p)
                } catch (e: Exception) {
//                    println(e.message)
                } catch (e: Error) {
//                    println(e.message)
                }
            }
            true
        }
        println("Finished writing packets")

        sleep(10000)

        receivers.forEach { _, receiver -> receiver.running = false }
        senders.forEach { _, sender -> sender.running = false }

        receivers.forEach { _, receiver ->
            println(receiver.getStats())
        }
        println("=======")
        println("Bridge:")
        println("  received ${b.numIncomingPackets} packets")
        println("  read ${b.numIncomingPackets} packets from queue")
        println("  forwarded ${b.numForwardedPackets} packets")
        println("      per packet ssrc: ${b.processedPacketsPerSsrc}")
        println("      per destination ssrc: ${b.packetsPerDestination}")

        senders.forEach { _, sender ->
            println(sender.getStats())
        }
    }

}
