package org.jitsi.rtp

import io.kotlintest.matchers.maps.shouldContain
import io.kotlintest.matchers.maps.shouldContainAll
import io.kotlintest.matchers.maps.shouldContainKeys
import io.kotlintest.matchers.types.shouldBeTypeOf
import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec
import java.nio.ByteBuffer

internal class RtpHeaderExtensionsTest : ShouldSpec() {
    private fun idLengthByte(id: Int, length: Int): Byte {
        return ((id shl 4) or length).toByte()
    }
    init {
        val oneByteHeaderExtBlock = ByteBuffer.wrap(byteArrayOf(
            0xBE.toByte(),                   0xDE.toByte(),  0x00,                          0x03,
            idLengthByte(1, 0),   0x42,           idLengthByte(2, 1), 0x42,
            0x42,                            0x00,           0x00,                          idLengthByte(3, 3),
            0x42,                            0x42,           0x42,                          0x42,
            // Fake payload
            0x12,                            0x34,           0x56,                          0x78
        ))
        val twoByteHeaderExtBlock = ByteBuffer.wrap(byteArrayOf(
            0x10,           0x00,           0x00,           0x03,
            0x01,           0x00,           0x02,           0x01,
            0x42.toByte(),  0x00,           0x03,           0x04,
            0x42.toByte(),  0x42.toByte(),  0x42.toByte(),  0x42.toByte(),
            // dummy payload
            0x12,           0x34,           0x56,           0x78
        ))

        "parsing" {
            "a one byte header extension block" {
                val extMap = RtpHeaderExtensions.parse(oneByteHeaderExtBlock)
                should("parse all the extensions") {
                    extMap.size shouldBe 3
                    extMap.shouldContainKeys(1, 2, 3)
                    for ((k, v) in extMap) {
                        v.shouldBeTypeOf<RtpOneByteHeaderExtension>()
                    }
                }
            }
            "a two byte header extension block" {
                val extMap = RtpHeaderExtensions.parse(twoByteHeaderExtBlock)
                should("parse all the extensions") {
                    extMap.size shouldBe 3
                    extMap.shouldContainKeys(1, 2, 3)
                    for ((k, v) in extMap) {
                        v.shouldBeTypeOf<RtpTwoByteHeaderExtension>()
                    }
                }
            }
        }
    }
}
