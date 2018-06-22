package org.jitsi.rtp

import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec
import java.nio.ByteBuffer

internal class RtpOneByteHeaderExtensionTest : ShouldSpec() {
    override fun isInstancePerTest(): Boolean = true
    private fun idLengthByte(id: Int, length: Int): Byte {
        return ((id shl 4) or length).toByte()
    }

    init {
        val extensionBlock = ByteBuffer.wrap(byteArrayOf(
            0xBE.toByte(),                   0xDE.toByte(),  0x00,                          0x03,
            idLengthByte(1, 0),   0x42,           idLengthByte(2, 1), 0x42,
            0x42,                            0x00,           0x00,                          idLengthByte(3, 3),
            0x42,                            0x42,           0x42,                          0x42,
            // Fake payload
            0x12,                            0x34,           0x56,                          0x78
        ))

        "parsing" {
            // Read past the cookie and length
            extensionBlock.position(4)
            "an extension with length 0" {
                val ext = RtpOneByteHeaderExtension(extensionBlock)
                should("have the right id, size, and data") {
                    ext.id shouldBe 1
                    ext.data.limit() shouldBe 1
                    ext.data.get() shouldBe 0x42.toByte()
                }
                should("leave the buffer position in the right palce") {
                    extensionBlock.position() shouldBe 6
                }
            }
            "an extension with padding" {
                extensionBlock.position(6)
                val ext = RtpOneByteHeaderExtension(extensionBlock)
                should("have the right id, size, and data") {
                    ext.id shouldBe 2
                    ext.data.limit() shouldBe 2
                    repeat(ext.data.limit()) {
                        ext.data.get() shouldBe 0x42.toByte()
                    }
                }
                should("have left the buffer position to after the padding") {
                    extensionBlock.position() shouldBe 11
                }
            }
            "an extension with length 3" {
                extensionBlock.position(11)
                val ext = RtpOneByteHeaderExtension(extensionBlock)
                should("have the right id, size and data") {
                    ext.id shouldBe 3
                    ext.data.limit() shouldBe 4
                    repeat(ext.data.limit()) {
                        ext.data.get() shouldBe 0x42.toByte()
                    }
                }
                should("have left the buffer in the right position") {
                    extensionBlock.position() shouldBe 16
                }
            }
            "an extension with id 15" {
                //TODO: should throw so we know to stop parsing
            }
        }
    }
}
