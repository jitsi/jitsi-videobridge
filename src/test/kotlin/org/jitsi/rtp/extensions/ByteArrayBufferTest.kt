package org.jitsi.rtp.extensions

import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec
import org.jitsi.rtp.UnparsedPacket

class ByteArrayBufferTest : ShouldSpec() {
    private val buffer = org.jitsi.rtp.extensions.bytearray.byteArrayOf(
        0x90, 0x6f, 0x16, 0xaf,
        0x65, 0xf3, 0xe8, 0xce,
        0x48, 0x0f, 0x22, 0x3a,
        0xbe, 0xde, 0x00, 0x01,
        0x10, 0xff, 0x00, 0x00
    )

    init {
        "ByteArrayBuffer.toHex" {
            should("format a full buffer correctly") {
                val packet = UnparsedPacket(buffer, 0, buffer.size)
                packet.toHex() shouldBe "906F16AF 65F3E8CE 480F223A BEDE0001\n10FF0000"
            }
            should("honor maxBytes") {
                val packet = UnparsedPacket(buffer, 0, buffer.size)
                packet.toHex(12) shouldBe "906F16AF 65F3E8CE 480F223A"
            }
            should("handle large maxBytes") {
                val packet = UnparsedPacket(buffer, 0, buffer.size)
                packet.toHex(40) shouldBe "906F16AF 65F3E8CE 480F223A BEDE0001\n10FF0000"
            }
            should("work correctly for offset packets") {
                val packet = UnparsedPacket(buffer, 4, buffer.size - 4)
                packet.toHex() shouldBe "65F3E8CE 480F223A BEDE0001 10FF0000"
            }
            should("honor maxBytes for offset packets") {
                val packet = UnparsedPacket(buffer, 4, buffer.size - 4)
                packet.toHex(12) shouldBe "65F3E8CE 480F223A BEDE0001"
            }
            should("handle large maxBytes for offset packets") {
                val packet = UnparsedPacket(buffer, 4, buffer.size - 4)
                packet.toHex(40) shouldBe "65F3E8CE 480F223A BEDE0001 10FF0000"
            }
            should("handle maxBytes large enough that maxBytes + offset wraps Int") {
                val packet = UnparsedPacket(buffer, 4, buffer.size - 4)
                packet.toHex(Int.MAX_VALUE - 2) shouldBe "65F3E8CE 480F223A BEDE0001 10FF0000"
            }
            should("work correctly for short packets") {
                val packet = UnparsedPacket(buffer, 0, buffer.size - 4)
                packet.toHex() shouldBe "906F16AF 65F3E8CE 480F223A BEDE0001"
            }
            should("honor maxBytes for short packets") {
                val packet = UnparsedPacket(buffer, 0, buffer.size - 4)
                packet.toHex(12) shouldBe "906F16AF 65F3E8CE 480F223A"
            }
            should("handle large maxBytes for short packets") {
                val packet = UnparsedPacket(buffer, 0, buffer.size - 4)
                packet.toHex(40) shouldBe "906F16AF 65F3E8CE 480F223A BEDE0001"
            }
            should("work correctly for short packets with offset") {
                val packet = UnparsedPacket(buffer, 4, buffer.size - 8)
                packet.toHex() shouldBe "65F3E8CE 480F223A BEDE0001"
            }
            should("honor maxBytes for short packets with offset") {
                val packet = UnparsedPacket(buffer, 4, buffer.size - 8)
                packet.toHex(8) shouldBe "65F3E8CE 480F223A"
            }
            should("handle large maxBytes for short packets with offset") {
                val packet = UnparsedPacket(buffer, 4, buffer.size - 8)
                packet.toHex(40) shouldBe "65F3E8CE 480F223A BEDE0001"
            }
            should("handle packets with invalidly large length values") {
                val packet = UnparsedPacket(buffer, 0, buffer.size + 40)
                packet.toHex() shouldBe "906F16AF 65F3E8CE 480F223A BEDE0001\n10FF0000"
            }
            should("honor maxBytes for packets with invalidly large length values") {
                val packet = UnparsedPacket(buffer, 0, buffer.size + 40)
                packet.toHex(40) shouldBe "906F16AF 65F3E8CE 480F223A BEDE0001\n10FF0000"
            }
        }
    }
}
