package org.jitsi.rtp.rtcp

import io.kotlintest.specs.ShouldSpec
import org.jitsi.rtp.extensions.toHex
import org.junit.jupiter.api.Assertions.*
import java.nio.ByteBuffer

internal class RtcpPacketTest : ShouldSpec() {
    init {
        "blah" {
            val buf = ByteBuffer.wrap(byteArrayOf(
                0x8F.toByte(), 0xCD.toByte(), 0x00.toByte(), 0x05.toByte(),
                0xFA.toByte(), 0x11.toByte(), 0x7D.toByte(), 0xF4.toByte(),
                0x06.toByte(), 0x8E.toByte(), 0x0C.toByte(), 0x00.toByte(),
                0x10.toByte(), 0x00.toByte(), 0x14.toByte(), 0x20.toByte()
            ))

            val packet = RtcpPacket.fromBuffer(buf)
            println(packet.getBuffer().toHex())
        }
    }
}
