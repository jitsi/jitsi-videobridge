package org.jitsi.rtp

import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec
import org.junit.jupiter.api.Assertions.*
import java.nio.ByteBuffer

internal class RtpTwoByteHeaderExtensionTest : ShouldSpec() {
    init {
        //  0                   1                   2                   3
        //  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
        // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
        // |       0x10    |    0x00       |           length=3            |
        // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
        // |      ID       |     L=0       |     ID        |     L=1       |
        // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
        // |       data    |    0 (pad)    |       ID      |      L=4      |
        // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
        // |                          data                                 |
        // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
        val extensionBlock = ByteBuffer.wrap(byteArrayOf(
            0x10,           0x00,           0x00,           0x03,
            0x01,           0x00,           0x02,           0x01,
            0x42.toByte(),  0x00,           0x03,           0x04,
            0x42.toByte(),  0x42.toByte(),  0x42.toByte(),  0x42.toByte(),
            // dummy payload
            0x12,           0x34,           0x56,           0x78
        ))
        "parsing" {
            // Read past the cookie and length
            extensionBlock.position(4)
            "an extension with length 0" {
                val ext = RtpTwoByteHeaderExtension(extensionBlock)
                should("have the right id, size and data") {
                    ext.id shouldBe 1
                    ext.data.limit() shouldBe 0
                }
                should("have left the buffer in the right position") {
                    extensionBlock.position() shouldBe 6
                }
            }
            "an extension with length 1 and padding" {
                extensionBlock.position(6)
                val ext = RtpTwoByteHeaderExtension(extensionBlock)
                should("have the right id, size and data") {
                    ext.id shouldBe 2
                    ext.data.limit() shouldBe 1
                    ext.data.get() shouldBe 0x42.toByte()
                }
                should("have left the buffer in the right position") {
                    extensionBlock.position() shouldBe 10
                }
            }
            "an extension with length 4" {
                extensionBlock.position(10)
                val ext = RtpTwoByteHeaderExtension(extensionBlock)
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
        }
    }
}
