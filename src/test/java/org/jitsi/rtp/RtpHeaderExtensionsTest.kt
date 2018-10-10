package org.jitsi.rtp

import io.kotlintest.matchers.maps.shouldContainKeys
import io.kotlintest.matchers.types.shouldBeTypeOf
import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec
import java.nio.ByteBuffer

internal class RtpHeaderExtensionsTest : ShouldSpec() {
    override fun isInstancePerTest(): Boolean = true

    private fun idLengthByte(id: Int, length: Int): Byte {
        return ((id shl 4) or length).toByte()
    }
    init {
        val oneByteHeaderExtBlock = ByteBuffer.wrap(byteArrayOf(
            0xBE.toByte(),                   0xDE.toByte(),  0x00,                          0x03,
            idLengthByte(1, 0),   0x42,           idLengthByte(2, 1), 0x42,
            0x42,                            0x00,           0x00,                          idLengthByte(3, 3),
            0x42,                            0x42,           0x42,                          0x42,
            // dummy payload
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
                val extensions = RtpHeaderExtensions(oneByteHeaderExtBlock)
                should("parse all the extensions") {
                    extensions.extensionMap.size shouldBe 3
                    extensions.extensionMap.shouldContainKeys(1, 2, 3)
                    extensions.extensionMap.values.forEach {
                        it.shouldBeTypeOf<RtpOneByteHeaderExtension>()
                    }
                }
                "and then serializing it" {
                    val buf = extensions.getBuffer()
                    should("have made sure it was word aligned") {
                        buf.limit() % 4 shouldBe 0
                    }
                    should("write it correctly") {
                        buf.rewind()
                        // Cookie
                        buf.getShort() shouldBe RtpOneByteHeaderExtension.COOKIE
                        // Length
                        buf.getShort().toInt() shouldBe 3
                    }
                }
            }
            "a two byte header extension block" {
                val extensions = RtpHeaderExtensions(twoByteHeaderExtBlock)
                should("parse all the extensions") {
                    extensions.extensionMap.size shouldBe 3
                    extensions.extensionMap.shouldContainKeys(1, 2, 3)
                    extensions.extensionMap.values.forEach {
                        it.shouldBeTypeOf<RtpTwoByteHeaderExtension>()
                    }
                }
                "and then serializing it" {
                    val buf = extensions.getBuffer()
                    should("have made sure it was word aligned") {
                        buf.limit() % 4 shouldBe 0
                    }
                    should("write it correctly") {
                        // Cookie
                        buf.getShort() shouldBe RtpTwoByteHeaderExtension.COOKIE
                        // Length
                        buf.getShort().toInt() shouldBe 3
                    }
                }
            }
        }
    }
}
