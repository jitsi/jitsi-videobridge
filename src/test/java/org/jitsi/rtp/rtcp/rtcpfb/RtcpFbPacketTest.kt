package org.jitsi.rtp.rtcp.rtcpfb

import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec
import org.jitsi.rtp.extensions.toHex
import org.jitsi.rtp.rtcp.RtcpIterator
import org.jitsi.rtp.rtcp.RtcpPacket
import java.nio.ByteBuffer

internal class RtcpFbPacketTest : ShouldSpec() {
    override fun isInstancePerTest(): Boolean = true

    private val tccPkt = ByteBuffer.wrap(byteArrayOf(
        // V=2, P=false, FMT=15, PT=205, length = 29
        0x8F.toByte(), 0xCD.toByte(), 0x00.toByte(), 0x1D.toByte(),
        // Sender ssrc = 16054943
        0xF4.toByte(), 0xFA.toByte(), 0x9F.toByte(), 0x42.toByte(),
        // Media source = 3709644252
        0xDD.toByte(), 0x1C.toByte(), 0xAD.toByte(), 0xDC.toByte(),
        // base seq num = 1, packet status count = 95
        0x00.toByte(), 0x01.toByte(), 0x00.toByte(), 0x5F.toByte(),
        // ref time = 10556007, fb pkt count = 0
        // vector chunk, 1 bit symbols,
        0xA1.toByte(), 0x12.toByte(),
        0x67.toByte(), 0x00.toByte(),
        0x20.toByte(), 0x5F.toByte(), 0xA8.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x04.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x04.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x04.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x04.toByte(),
        0x04.toByte(), 0x14.toByte(), 0x08.toByte(), 0x04.toByte(),
        0x18.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte()
    ))

    init {
        "Parsing a TCC packet" {
            val pkt = RtcpFbPacket(tccPkt)
            should ("parse the data correctly") {
                println("=====GETTING BUFFER======")
                println(pkt.getBuffer().toHex())
//                println("=====GETTING BUFFER AGAIN======")
//                println(pkt.getBuffer().toHex())
            }
//            val pkt = RtcpPacket.fromBuffer(tccPkt)
//            println(pkt)
//            val newPktBuf = pkt.getBuffer()
//            println("packet buff from parsed rtcpfbpacket:\n${newPktBuf.toHex()}")
//            val iter = RtcpIterator(newPktBuf)
//
//            iter.hasNext() shouldBe true
//            var nextPkt = iter.next()
//            println(nextPkt)

        }
//        println("original buffer limit: ${tccPkt.limit()}")
//        val iter = RtcpIterator(tccPkt)
//        var rtcpPacket = iter.next()
//        println("got packet: $rtcpPacket")
//        println("has next? ${iter.hasNext()}")
    }

}
