package org.jitsi.rtp.rtcp

import io.kotlintest.matchers.beOfType
import io.kotlintest.matchers.haveSize
import io.kotlintest.should
import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec
import org.jitsi.rtp.rtcp.rtcpfb.RtcpFbPacket
import java.nio.ByteBuffer

internal class RtcpIteratorTest : ShouldSpec() {
    override fun isInstancePerTest(): Boolean = true
    private val compoundPacketBuf = ByteBuffer.wrap(byteArrayOf(
        0x81.toByte(), 0xC9.toByte(), 0x00.toByte(), 0x4F.toByte(),
        0x66.toByte(), 0xAE.toByte(), 0xAB.toByte(), 0xAB.toByte(),
        0xB2.toByte(), 0xFE.toByte(), 0x30.toByte(), 0x5A.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x4C.toByte(), 0x8C.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x0B.toByte(), 0x0D.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x81.toByte(), 0xCD.toByte(), 0x00.toByte(), 0x03.toByte(),
        0x4F.toByte(), 0x66.toByte(), 0xAE.toByte(), 0xAB.toByte(),
        0xB2.toByte(), 0xFE.toByte(), 0x30.toByte(), 0x5A.toByte(),
        0x4C.toByte(), 0x8D.toByte(), 0x00.toByte(), 0x7F.toByte()
    ))

    private val singlePacketBuf = ByteBuffer.wrap(byteArrayOf(
        0x80.toByte(), 0xC8.toByte(), 0x00.toByte(), 0xE4.toByte(),
        0x36.toByte(), 0x13.toByte(), 0xF6.toByte(), 0xF6.toByte(),
        0xDF.toByte(), 0x21.toByte(), 0x7D.toByte(), 0xF0.toByte(),
        0x7F.toByte(), 0x5E.toByte(), 0x41.toByte(), 0xD5.toByte(),
        0x90.toByte(), 0x31.toByte(), 0x59.toByte(), 0xF3.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x08.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x01.toByte(), 0x14.toByte()
    ))

    init {
        "when parsing a compound packet" {
            val iter = RtcpIterator(compoundPacketBuf)
            "next" {
                should("get the next packet") {
                    iter.next() should beOfType<RtcpRrPacket>()
                    iter.next() should beOfType<RtcpFbPacket>()
                }
            }
            "hasNext" {
                should("correctly report if there is another packet") {
                    iter.hasNext() shouldBe true
                    iter.next()
                    iter.hasNext() shouldBe true
                    iter.next()
                    iter.hasNext() shouldBe false
                }
            }
            "getAll" {
                should("return all packets") {
                    val pkts = iter.getAll()
                    pkts should haveSize(2)
                    iter.hasNext() shouldBe false
                }

            }
        }
        "when parsing a packet with only a single RTCP packet" {
            val iter = RtcpIterator(singlePacketBuf)
            val pkt = iter.next()
            pkt should beOfType<RtcpSrPacket>()
            iter.hasNext() shouldBe false
        }
    }

}
