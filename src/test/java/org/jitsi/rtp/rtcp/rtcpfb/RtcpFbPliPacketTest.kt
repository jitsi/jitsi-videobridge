package org.jitsi.rtp.rtcp.rtcpfb

import io.kotlintest.specs.ShouldSpec
import org.jitsi.rtp.extensions.toHex
import org.junit.jupiter.api.Assertions.*
import java.nio.ByteBuffer

internal class RtcpFbPliPacketTest : ShouldSpec() {

    init {
        "Parsing a PLI packet from a buffer" {
            val buf = ByteBuffer.wrap(byteArrayOf(
                0x81.toByte(), 0xCE.toByte(), 0x00.toByte(), 0x02.toByte(),
                0x70.toByte(), 0x2D.toByte(), 0x93.toByte(), 0xE4.toByte(),
                0xC1.toByte(), 0x2D.toByte(), 0xDB.toByte(), 0x96.toByte()
            ))

            val packet = RtcpFbPacket.fromBuffer(buf)
            println(packet.getBuffer().toHex())
        }
    }
}
