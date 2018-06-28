package org.jitsi.rtp.rtcp

import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec
import org.jitsi.rtp.util.BitBuffer
import java.nio.ByteBuffer

internal class BitBufferRtcpHeaderTest : ShouldSpec() {
    override fun isInstancePerTest(): Boolean = true

    private val headerBuf = ByteBuffer.allocate(8)

    init {
        with(BitBuffer(headerBuf)) {
            putBits(2.toByte(), 2) // version = 2
            putBoolean(false) // padding
            putBits(1.toByte(), 5) // report count
            headerBuf.put(200.toByte()) // payload type
            headerBuf.putShort(0xFFFF.toShort()) // length
            headerBuf.putInt(0xFFFFFFFF.toInt()) // sender ssrc
            headerBuf
        }
        headerBuf.rewind()
        "parsing" {
            val header = BitBufferRtcpHeader(headerBuf)
            should("parse the values correctly") {
                header.version shouldBe 2
                header.hasPadding shouldBe false
                header.reportCount shouldBe 1
                header.payloadType shouldBe 200
                header.length shouldBe 0xFFFF
                header.senderSsrc shouldBe 0xFFFFFFFF.toLong()
            }
        }

    }

}
