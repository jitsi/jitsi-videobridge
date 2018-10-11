package org.jitsi.rtp.rtcp

import io.kotlintest.matchers.collections.shouldContain
import io.kotlintest.matchers.collections.shouldHaveSize
import io.kotlintest.matchers.string.shouldBeEmpty
import io.kotlintest.specs.ShouldSpec
import org.jitsi.rtp.util.byteBufferOf

internal class RtcpByePacketTest : ShouldSpec() {
    override fun isInstancePerTest(): Boolean = true

    init {
        "Creating an RtcpByePacket" {
            "from a buffer" {
                val buf = byteBufferOf(
                    0x81.toByte(), 0xCB.toByte(), 0x00.toByte(), 0x01.toByte(),
                    // ssrc 3641474342
                    0xD9.toByte(), 0x0C.toByte(), 0x7D.toByte(), 0x26.toByte()
                )
                val packet = RtcpByePacket(buf)
                should("parse the values correctly") {
                    packet.ssrcs shouldHaveSize 1
                    packet.ssrcs shouldContain 3641474342
                    packet.reason.shouldBeEmpty()
                }
            }
        }
    }
}
