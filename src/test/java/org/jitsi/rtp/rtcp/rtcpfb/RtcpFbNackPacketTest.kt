package org.jitsi.rtp.rtcp.rtcpfb

import io.kotlintest.specs.ShouldSpec
import org.jitsi.rtp.util.byteBufferOf


internal class RtcpFbNackPacketTest : ShouldSpec() {
    val buf = byteBufferOf(
        0x81.toByte(), 0xcd.toByte(), 0x00.toByte(), 0x04.toByte(),
        0xc6.toByte(), 0x47.toByte(), 0x34.toByte(), 0x07.toByte(),
        0x12.toByte(), 0x95.toByte(), 0xf4.toByte(), 0x8f.toByte(),
        0x21.toByte(), 0x03.toByte(), 0xff.toByte(), 0xff.toByte(),
        0x21.toByte(), 0x14.toByte(), 0x00.toByte(), 0x7f.toByte()
    )

    init {
        "Parsing a NACK packet" {
            val nackPacket = RtcpFbNackPacket(buf)

            println(nackPacket.missingSeqNums)
        }
    }

}

