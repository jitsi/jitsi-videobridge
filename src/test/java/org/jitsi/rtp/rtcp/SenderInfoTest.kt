package org.jitsi.rtp.rtcp

import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec
import org.jitsi.rtp.extensions.toHex
import toUInt
import java.nio.ByteBuffer

internal class SenderInfoTest : ShouldSpec() {
    override fun isInstancePerTest(): Boolean = true

    private val expectedNtpTimestamp: Long = 0x0123456789abcdef
    private val expectedRtpTimestamp: Long = 0xFFFFFFFF
    private val expectedSendersPacketCount: Long = 0xFFFFFFFF
    private val expectedSendersOctetCount: Long = 0xFFFFFFFF
    private val senderInfoBuf = with (ByteBuffer.allocate(20)) {
        putLong(expectedNtpTimestamp)
        putInt(expectedRtpTimestamp.toUInt())
        putInt(expectedSendersPacketCount.toUInt())
        putInt(expectedSendersOctetCount.toUInt())
        this.rewind() as ByteBuffer
    }

    init {
        "creation" {
            "from a buffer" {
                val senderInfo = SenderInfo(senderInfoBuf)
                should("read all values correctly") {
                    senderInfo.ntpTimestamp shouldBe expectedNtpTimestamp
                    senderInfo.rtpTimestamp shouldBe expectedRtpTimestamp
                    senderInfo.sendersPacketCount shouldBe expectedSendersPacketCount
                    senderInfo.sendersOctetCount shouldBe expectedSendersOctetCount
                }
            }
            "from values" {
                val senderInfo = SenderInfo(
                    ntpTimestamp = expectedNtpTimestamp,
                    rtpTimestamp = expectedRtpTimestamp,
                    sendersPacketCount = expectedSendersPacketCount,
                    sendersOctetCount = expectedSendersOctetCount
                )
                should("save all values correctly") {
                    senderInfo.ntpTimestamp shouldBe expectedNtpTimestamp
                    senderInfo.compactedNtpTimestamp shouldBe 0x456789ab
                    senderInfo.rtpTimestamp shouldBe expectedRtpTimestamp
                    senderInfo.sendersPacketCount shouldBe expectedSendersPacketCount
                    senderInfo.sendersOctetCount shouldBe expectedSendersOctetCount
                }
            }
        }
        "serialization" {
            val senderInfo = SenderInfo(
                ntpTimestamp = expectedNtpTimestamp,
                rtpTimestamp = expectedRtpTimestamp,
                sendersPacketCount = expectedSendersPacketCount,
                sendersOctetCount = expectedSendersOctetCount
            )
            val newBuf = senderInfo.getBuffer()
            should("write the data correctly") {
                newBuf.rewind() shouldBe senderInfoBuf.rewind()
            }
        }
    }
}
