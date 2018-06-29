package org.jitsi.rtp.rtcp

import io.kotlintest.shouldBe
import io.kotlintest.shouldThrow
import io.kotlintest.specs.ShouldSpec
import org.jitsi.rtp.util.BitBuffer
import java.nio.ByteBuffer

internal class BitBufferRtcpHeaderTest : ShouldSpec() {
    override fun isInstancePerTest(): Boolean = true

    private val headerBuf = with(ByteBuffer.allocate(8)) {
        val bitBuffer = BitBuffer(this)
        bitBuffer.putBits(2.toByte(), 2) // version
        bitBuffer.putBoolean(false) // padding
        bitBuffer.putBits(1.toByte(), 5) // report count
        put(200.toByte()) // payload type
        putShort(0xFFFF.toShort()) // length
        putInt(0xFFFFFFFF.toInt()) // sender ssrc
        this.rewind() as ByteBuffer
    }

    init {
        "creation" {
            "from a buffer" {
                val header = BitBufferRtcpHeader.fromBuffer(headerBuf)
                should("fromBuffer the values correctly") {
                    header.version shouldBe 2
                    header.hasPadding shouldBe false
                    header.reportCount shouldBe 1
                    header.payloadType shouldBe 200
                    header.length shouldBe 0xFFFF
                    header.senderSsrc shouldBe 0xFFFFFFFF
                }
            }
            "from a complete set of values" {
                val header = BitBufferRtcpHeader.fromValues {
                    version = 2
                    hasPadding = false
                    reportCount = 1
                    payloadType = 200
                    length = 0xFFFF
                    senderSsrc = 0xFFFFFFFF
                }
                should("set everything correctly") {
                    header.version shouldBe 2
                    header.hasPadding shouldBe false
                    header.reportCount shouldBe 1
                    header.payloadType shouldBe 200
                    header.length shouldBe 0xFFFF
                    header.senderSsrc shouldBe 0xFFFFFFFF
                }
            }
            "from an incomplete set of values" {
                val header = BitBufferRtcpHeader.fromValues {
                    // version = 2 Don't set version
                    hasPadding = false
                    reportCount = 1
                    payloadType = 200
                    length = 0xFFFF
                    senderSsrc = 0xFFFFFFFF
                }
                should("throw when we try to access an unset value") {
                    shouldThrow<IllegalStateException> {
                        header.version
                    }
                }
            }
        }
        "serialization" {
            val newBuf = ByteBuffer.allocate(8)
            val header = BitBufferRtcpHeader.fromBuffer(headerBuf)
            header.serializeToBuffer(newBuf)
            should("write the correct data to the buffer") {
                newBuf.rewind() shouldBe headerBuf.rewind()
            }
        }
    }
}
