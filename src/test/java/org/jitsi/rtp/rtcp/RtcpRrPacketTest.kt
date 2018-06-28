package org.jitsi.rtp.rtcp

import io.kotlintest.matchers.haveSize
import io.kotlintest.should
import io.kotlintest.specs.ShouldSpec
import org.jitsi.rtp.extensions.put3Bytes
import org.jitsi.rtp.util.BitBuffer
import java.nio.ByteBuffer

internal class RtcpRrPacketTest : ShouldSpec() {
    override fun isInstancePerTest(): Boolean = true

    private val ssrc1: Long = 12345
    private val fractionLost1: Int = 42
    private val cumulativeLost1: Int = 4242
    private val seqNumCycles1: Int = 1
    private val seqNum1: Int = 42
    private val interarrivalJitter1: Long = 4242
    private val lastSrTimestamp1: Long = 23456
    private val delaySinceLastSr1: Long = 34567

    private val ssrc2: Long = 23456
    private val fractionLost2: Int = 43
    private val cumulativeLost2: Int = 4343
    private val seqNumCycles2: Int = 2
    private val seqNum2: Int = 43
    private val interarrivalJitter2: Long = 4343
    private val lastSrTimestamp2: Long = 34567
    private val delaySinceLastSr2: Long = 45678

    init {
        // Header + 2 ReportBlocks
        val packetBuf = ByteBuffer.allocate(8 + 2 * 24)
        with (BitBuffer(packetBuf)) {
            // Header
            putBits(0x2, 2) // Version
            putBoolean(false) // Padding
            putBits(0x2, 5) // Report count
            packetBuf.put(200.toByte()) // Payload type
            packetBuf.putShort(0xFFFF.toShort()) // length
            packetBuf.putInt(0xFFFFFFFF.toInt()) // sender ssrc

            packetBuf.putInt(ssrc1.toInt())
            packetBuf.put(fractionLost1.toByte()); packetBuf.put3Bytes(cumulativeLost1)
            packetBuf.putShort(seqNumCycles1.toShort()); packetBuf.putShort(seqNum1.toShort())
            packetBuf.putInt(interarrivalJitter1.toInt())
            packetBuf.putInt(lastSrTimestamp1.toInt())
            packetBuf.putInt(delaySinceLastSr1.toInt())

            packetBuf.putInt(ssrc2.toInt())
            packetBuf.put(fractionLost2.toByte()); packetBuf.put3Bytes(cumulativeLost2)
            packetBuf.putShort(seqNumCycles2.toShort()); packetBuf.putShort(seqNum2.toShort())
            packetBuf.putInt(interarrivalJitter2.toInt())
            packetBuf.putInt(lastSrTimestamp2.toInt())
            packetBuf.putInt(delaySinceLastSr2.toInt())
            packetBuf.rewind() as ByteBuffer
        }
        "parsing" {
            val rrPacket = RtcpRrPacket(packetBuf)
            should("parse all values correctly") {
                rrPacket.reportBlocks should haveSize(2)
            }
        }
    }
}
