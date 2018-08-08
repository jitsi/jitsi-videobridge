/*
 * Copyright @ 2018 Atlassian Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jitsi.rtp

import org.jitsi.rtp.rtcp.RtcpHeader
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.rtp.util.RtpProtocol
import toUInt
import unsigned.toUInt
import java.nio.ByteBuffer

abstract class Packet {
    abstract var buf: ByteBuffer
    abstract var size: Int
    val tags = mutableMapOf<String, Any>()

    companion object {
        //TODO: should we still have this here?
        private fun getPacketType(buf: ByteBuffer): Int = buf.get(1).toUInt()
        fun parse(buf: ByteBuffer): Packet {
            val packetType = getPacketType(buf)
            return when (packetType) {
                in 200..211 -> RtcpPacket.fromBuffer(buf)
                else -> RtpPacket.fromBuffer(buf)
            }
        }
    }
}

class UnparsedPacket(override var buf: ByteBuffer) : Packet() {
    override var size: Int = buf.limit()
}

open class SrtpProtocolPacket(override var buf: ByteBuffer) : Packet() {
    override var size: Int = 0
        get() = buf.limit()
//    fun getAuthTag(tagLength: Int): ByteBuffer = TODO()
//    fun setAuthTag(tag: ByteBuffer): Unit = TODO()
//    var mki: ByteBuffer
//    val ssrc: Int = TODO()
//    val seqNum: Int = TODO()
//    val payload: ByteBuffer = TODO()
}

// https://tools.ietf.org/html/rfc3711#section-3.1
class SrtpPacket(buf: ByteBuffer) : SrtpProtocolPacket(buf) {
    fun getAuthTag(tagLength: Int): ByteBuffer {
        println("BRIAN: getting auth tag, buf limit is: ${buf.limit()}, capacitt is: ${buf.capacity()}")
        buf.mark()
        buf.position(buf.limit() - tagLength)
        val authTag = buf.slice()
        buf.reset()
        //TODO: temp implementation!
        return ByteBuffer.allocate(tagLength).put(authTag)
    }
    fun setAuthTag(tag: ByteBuffer): Unit = TODO()
    val header = RtpHeader.fromBuffer(buf)
    val payload: ByteBuffer = buf.slice()
    val ssrc: Int = header.ssrc.toUInt()
    val seqNum: Int = header.sequenceNumber
}

// We can't have this derive from RtcpPacket since RtcpPacket is abstract (the
// particular type of RtcpPacket is needed).  Instead, it will take in an RtcpPacket
// instance (which could be an UnparsedRtcpPacket?)
// https://tools.ietf.org/html/rfc3711#section-3.4
class SrtcpPacket(buf: ByteBuffer) : SrtpProtocolPacket(buf) {
    val header = RtcpHeader.fromBuffer(buf)
    val payload: ByteBuffer = buf.slice()
    val ssrc: Int = header.senderSsrc.toUInt()
    fun getAuthTag(tagLength: Int): ByteBuffer {
        buf.mark()
        buf.position(buf.limit() - tagLength)
        val authTag = buf.slice()
        buf.reset()
        return ByteBuffer.allocate(tagLength).put(authTag)
    }
    fun setAuthTag(tag: ByteBuffer): Unit = TODO()
    fun getSrtcpIndex(tagLength: Int): Int {
        return buf.getInt(buf.limit() - (4 + tagLength)) and (0x80000000.inv()).toInt()
    }
    fun isEncrypted(tagLength: Int): Boolean {
        return buf.getInt(buf.limit() - (4 + tagLength)) and (0x80000000.inv()).toInt() == 0x80000000.toInt()
    }

}

public class RtpProtocolPacket(override var buf: ByteBuffer) : Packet() {
    val isRtp: Boolean = RtpProtocol.isRtp(buf)
    override var size: Int = TODO()
    var ssrc: Int
}

public class DtlsProtocolPacket(override var buf: ByteBuffer) : Packet() {
    override var size: Int = buf.limit()
}
