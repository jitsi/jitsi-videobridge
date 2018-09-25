package org.jitsi.rtp.rtcp

import io.kotlintest.matchers.beInstanceOf
import io.kotlintest.matchers.beOfType
import io.kotlintest.matchers.haveSize
import io.kotlintest.should
import io.kotlintest.shouldBe
import io.kotlintest.shouldThrow
import io.kotlintest.specs.ShouldSpec
import org.jitsi.rtp.rtcp.rtcpfb.RtcpFbPacket
import java.nio.ByteBuffer

internal class RtcpIteratorTest : ShouldSpec() {
    override fun isInstancePerTest(): Boolean = true
    private val compoundPacketBuf = ByteBuffer.wrap(byteArrayOf(
        0x81.toByte(), 0xC9.toByte(), 0x00.toByte(), 0x07.toByte(),
        0x50.toByte(), 0x63.toByte(), 0x13.toByte(), 0xDE.toByte(),
        0x48.toByte(), 0xCA.toByte(), 0xF9.toByte(), 0xD1.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x10.toByte(), 0x8C.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x04.toByte(), 0x68.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x81.toByte(), 0xCD.toByte(), 0x00.toByte(), 0x03.toByte(),
        0x50.toByte(), 0x63.toByte(), 0x13.toByte(), 0xDE.toByte(),
        0x48.toByte(), 0xCA.toByte(), 0xF9.toByte(), 0xD1.toByte(),
        0x10.toByte(), 0x8D.toByte(), 0x00.toByte(), 0x00.toByte()
    ))

    private val singlePacketBuf = ByteBuffer.wrap(byteArrayOf(
        0x80.toByte(), 0xC8.toByte(), 0x00.toByte(), 0x06.toByte(),
        0x36.toByte(), 0x13.toByte(), 0xF6.toByte(), 0xF6.toByte(),
        0xDF.toByte(), 0x21.toByte(), 0x7D.toByte(), 0xF0.toByte(),
        0x7F.toByte(), 0x5E.toByte(), 0x41.toByte(), 0xD5.toByte(),
        0x90.toByte(), 0x31.toByte(), 0x59.toByte(), 0xF3.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x08.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x01.toByte(), 0x14.toByte()
    ))

    private val blah = ByteBuffer.wrap(byteArrayOf(
        0x8F.toByte(), 0xCD.toByte(), 0x00.toByte(), 0x21.toByte(),
        0x72.toByte(), 0x45.toByte(), 0x7C.toByte(), 0xE5.toByte(),
        0x03.toByte(), 0xA3.toByte(), 0x76.toByte(), 0x0E.toByte(),
        0x00.toByte(), 0x01.toByte(), 0x00.toByte(), 0x58.toByte(),
        0x11.toByte(), 0xE8.toByte(), 0xC2.toByte(), 0x00.toByte(),
        0xD5.toByte(), 0x55.toByte(), 0xD5.toByte(), 0x55.toByte(),
        0xD5.toByte(), 0x55.toByte(), 0xD5.toByte(), 0x55.toByte(),
        0xD5.toByte(), 0x55.toByte(), 0xD5.toByte(), 0x55.toByte(),
        0xD5.toByte(), 0x55.toByte(), 0xD5.toByte(), 0x55.toByte(),
        0xD5.toByte(), 0x55.toByte(), 0xD5.toByte(), 0x55.toByte(),
        0xD5.toByte(), 0x55.toByte(), 0xD5.toByte(), 0x55.toByte(),
        0xD5.toByte(), 0x40.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte()
    ))

    init {
        "when parsing a compound packet" {
            val iter = RtcpIterator(compoundPacketBuf)
            "next" {
                should("get the next packet") {
                    iter.next() should beInstanceOf<RtcpRrPacket>()
                    iter.next() should beInstanceOf<RtcpFbPacket>()
                    shouldThrow<Exception> {
                        iter.next()
                    }
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
