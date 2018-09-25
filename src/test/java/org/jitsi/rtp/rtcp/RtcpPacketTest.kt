package org.jitsi.rtp.rtcp

import io.kotlintest.specs.ShouldSpec
import org.jitsi.rtp.extensions.toHex
import org.junit.jupiter.api.Assertions.*
import java.nio.ByteBuffer

internal class RtcpPacketTest : ShouldSpec() {
    init {
        "blah" {
            val buf = ByteBuffer.wrap(byteArrayOf(
                0x8F.toByte(), 0xCD.toByte(), 0x00.toByte(), 0x08.toByte(),
                0xBF.toByte(), 0x5D.toByte(), 0x09.toByte(), 0x07.toByte(),
                0x1F.toByte(), 0xD6.toByte(), 0x1B.toByte(), 0x0F.toByte(),
                0x00.toByte(), 0x01.toByte(), 0x00.toByte(), 0x13.toByte(),
                0x74.toByte(), 0x61.toByte(), 0xCF.toByte(), 0x00.toByte(),

                0xBF.toByte(), 0xF0.toByte(), 0xC0.toByte(), 0x50.toByte(),
                0xC8.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
                0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
                0x00.toByte(), 0x00.toByte(), 0x04.toByte(), 0x00.toByte()
            ))

            val packet = RtcpPacket.fromBuffer(buf)
            println(packet::class)
            println(packet.getBuffer().toHex())
            val iter = RtcpIterator(packet.getBuffer())
            iter.getAll()
        }
    }
}
