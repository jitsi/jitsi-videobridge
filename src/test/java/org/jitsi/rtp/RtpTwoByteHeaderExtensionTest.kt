package org.jitsi.rtp

import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec
import org.junit.jupiter.api.Assertions.*
import java.nio.ByteBuffer

internal class RtpTwoByteHeaderExtensionTest : ShouldSpec() {
    override fun isInstancePerTest(): Boolean = true
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
        "serializing" {
            val buf = ByteBuffer.allocate(48)
            // Read past the cookie and length
            extensionBlock.position(4)
            "an extension with length 0" {
                val ext = RtpTwoByteHeaderExtension(extensionBlock)
                ext.serializeToBuffer(buf)
                should("have written the correct amount of data") {
                    buf.position() shouldBe 2
                }
                should("have written the right id and length") {
                    // Id
                    buf.get(0).toInt() shouldBe 1
                    // Length
                    buf.get(1).toInt() shouldBe 0
                }
            }
            "an extension with length 1" {
                extensionBlock.position(6)
                val ext = RtpTwoByteHeaderExtension(extensionBlock)
                ext.serializeToBuffer(buf)
                should("have written the correct amount of data") {
                    buf.position() shouldBe 3
                }
                buf.rewind()
                should("have written the right id, length and data") {
                    // Id
                    buf.get(0).toInt() shouldBe 2
                    // Length
                    buf.get(1).toInt() shouldBe 1
                    // Data
                    buf.get(2) shouldBe 0x42.toByte()
                }
            }
            "an extension with length 4" {
                extensionBlock.position(10)
                val ext = RtpTwoByteHeaderExtension(extensionBlock)
                ext.serializeToBuffer(buf)
                should("have written the correct amount of data") {
                    buf.position() shouldBe 6
                }
                buf.rewind()
                should("have written the right id, length and data") {
                    // Id
                    buf.get(0).toInt() shouldBe 3
                    // Length
                    buf.get(1).toInt() shouldBe 4
                    // Data
                    for (i in 2 until 6) {
                        buf.get(i) shouldBe 0x42.toByte()
                    }
                }
            }
        }
    }
}
