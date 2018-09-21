package org.jitsi.rtp

import io.kotlintest.specs.ShouldSpec
import io.kotlintest.should
import io.kotlintest.shouldBe
import java.nio.ByteBuffer

internal class RtxPacketTest : ShouldSpec() {
    private val osn = 0x179d;

    val pkt = ByteBuffer.wrap(byteArrayOf(
        // RTP Header
        0x90.toByte(), 0x60.toByte(), 0x43.toByte(), 0xCC.toByte(),
        0x29.toByte(), 0xAB.toByte(), 0xC0.toByte(), 0xFE.toByte(),
        0x13.toByte(), 0xE9.toByte(), 0x20.toByte(), 0xDC.toByte(),

        // Extension
        0xBE.toByte(), 0xDE.toByte(), 0x00.toByte(), 0x01.toByte(),
        0x51.toByte(), 0x00.toByte(), 0x0B.toByte(), 0x00.toByte(),

        // RTP payload (OSN)
        (osn shr 8).toByte(), osn.toByte(),

        // RTP payload (remainder)
        0x90.toByte(), 0x80.toByte(),
        0xE1.toByte(), 0x14.toByte(), 0x10.toByte(), 0xE0.toByte(),
        0x00.toByte(), 0x9D.toByte(), 0x01.toByte(), 0x2A.toByte(),
        0x80.toByte(), 0x02.toByte(), 0x68.toByte(), 0x01.toByte(),
        0x39.toByte(), 0x2D.toByte(), 0x00.toByte(), 0x10.toByte(),
        0x1C.toByte(), 0x22.toByte(), 0x16.toByte(), 0x16.toByte(),
        0x22.toByte(), 0x66.toByte(), 0x12.toByte(), 0x20.toByte(),
        0x58.toByte(), 0x0E.toByte(), 0xEF.toByte(), 0xBF.toByte(),
        0xEC.toByte(), 0xAC.toByte(), 0x2D.toByte(), 0x4D.toByte(),
        0x80.toByte(), 0x1D.toByte(), 0x6E.toByte(), 0xE6.toByte(),
        0x98.toByte(), 0x98.toByte(), 0xC8.toByte(), 0x14.toByte(),
        0x83.toByte(), 0x9A.toByte(), 0xBA.toByte(), 0x02.toByte(),
        0x6E.toByte(), 0x07.toByte(), 0xDC.toByte(), 0xD3.toByte(),
        0xBD.toByte(), 0x41.toByte(), 0xE8.toByte(), 0xAB.toByte(),
        0x58.toByte(), 0x73.toByte(), 0xFB.toByte(), 0x3B.toByte(),
        0xE1.toByte(), 0x0F.toByte(), 0xF9.toByte(), 0x2F.toByte(),
        0xF6.toByte(), 0xFF.toByte(), 0x6C.toByte(), 0x3E.toByte(),
        0xAB.toByte(), 0xFE.toByte(), 0x7B.toByte(), 0xEE.toByte(),
        0x61.toByte(), 0xA2.toByte(), 0xBF.toByte(), 0xFA.toByte(),
        0x5E.toByte(), 0x4D.toByte(), 0x3A.toByte(), 0xD7.toByte(),
        0xFE.toByte(), 0xDF.toByte(), 0xFA.toByte(), 0x0F.toByte(),
        0x53.toByte(), 0x7F.toByte(), 0x98.toByte(), 0xFE.toByte(),
        0x22.toByte(), 0xFD.toByte(), 0xDF.toByte(), 0xF7.toByte(),
        0x8F.toByte(), 0x6D.toByte(), 0x9D.toByte(), 0xBF.toByte(),
        0xF0.toByte(), 0x0E.toByte(), 0xFC.toByte(), 0xB3.toByte(),
        0xFA.toByte(), 0x67.toByte(), 0xEB.toByte(), 0x87.toByte(),
        0x13.toByte(), 0x2D.toByte(), 0xC3.toByte(), 0xFD.toByte(),
        0x80.toByte(), 0xF6.toByte(), 0x26.toByte(), 0xBE.toByte(),
        0xDF.toByte(), 0xEF.toByte(), 0xFC.toByte(), 0xFD.toByte(),
        0xFB.toByte(), 0x5F.toByte(), 0xEC.toByte(), 0x03.toByte(),
        0xFC.toByte(), 0xFB.toByte(), 0xFB.toByte(), 0x27.toByte(),
        0xFD.toByte(), 0xAF.toByte(), 0x5F.toByte(), 0xFF.toByte(),
        0xE5.toByte(), 0xF9.toByte(), 0x10.toByte(), 0xFA.toByte(),
        0x17.toByte(), 0xB0.toByte(), 0x6F.toByte(), 0xF5.toByte(),
        0x0F.toByte(), 0xF5.toByte(), 0x1E.toByte(), 0xB1.toByte(),
        0x7F.toByte(), 0xE5.toByte(), 0x7E.toByte(), 0xDC.toByte(),
        0xFA.toByte(), 0x75.toByte(), 0xFA.toByte(), 0xBB.toByte(),
        0xF6.toByte(), 0xAF.toByte(), 0xE0.toByte(), 0x6B.toByte()
    ))

    init {
        "parsing an rtx packet" {
            val rtxPacket = RtxPacket(pkt)
            should("parse OSN correctly") {
                rtxPacket.originalSequenceNumber shouldBe osn
            }
        }
    }
}
