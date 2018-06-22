package org.jitsi.rtp

import io.kotlintest.matchers.collections.shouldContainInOrder
import io.kotlintest.matchers.containAll
import io.kotlintest.matchers.haveSize
import io.kotlintest.should
import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec
import java.nio.ByteBuffer
import kotlin.experimental.or

internal class BitBufferRtpHeaderTest : ShouldSpec() {
    // v=2, p=1, x=1, cc=3 = 0xB3
    // m=1, pt=96 = 0xE0
    // seqnum 4224 = 0x10 0x80
    // timestamp 98765 = 0x00 0x01 0x81 0xCD
    // ssrc 1234567 = 0x00 0x12 0xD6 0x87
    // csrc 1 = 0x00 0x00 0x00 0x01
    // csrc 2 = 0x00 0x00 0x00 0x02
    // csrc 3 = 0x00 0x00 0x00 0x03
    val headerData = ByteBuffer.wrap(byteArrayOf(
        0xB3.toByte(),  0xE0.toByte(),  0x10,           0x80.toByte(),
        0x00,           0x01,           0x81.toByte(),  0xCD.toByte(),
        0x00,           0x12,           0xD6.toByte(),  0x87.toByte(),
        0x00,           0x00,           0x00,           0x01,
        0x00,           0x00,           0x00,           0x02,
        0x00,           0x00,           0x00,           0x03
    ))

    init {
        "parsing" {
            "a header" {
                val header = BitBufferRtpHeader(headerData.asReadOnlyBuffer())
                header.version shouldBe 2
                header.hasPadding shouldBe true
                header.hasExtension shouldBe true
                header.csrcCount shouldBe 3
                header.marker shouldBe true
                header.payloadType shouldBe 96
                header.sequenceNumber shouldBe 4224
                header.timestamp shouldBe 98765
                header.ssrc shouldBe 1234567
                header.csrcs should haveSize(3)
                header.csrcs.shouldContainInOrder(listOf<Long>(1, 2, 3))
            }
        }
        "writing" {
            "should update the object's value" {
                val header = BitBufferRtpHeader(headerData.asReadOnlyBuffer())
                header.version = 10
                header.version shouldBe 10
                // We passed the buffer as readonly, so we know it hasn't been changed
            }
        }
        "serializing" {
            val header = BitBufferRtpHeader(headerData.asReadOnlyBuffer())
            val newBuf = ByteBuffer.allocate(headerData.limit())
            header.serializeToBuffer(newBuf)
            newBuf.rewind()
            headerData.rewind()
            newBuf.compareTo(headerData) shouldBe 0
        }
    }
}
