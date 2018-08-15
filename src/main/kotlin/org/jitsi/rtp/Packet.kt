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
import org.jitsi.rtp.util.BufferView
import org.jitsi.rtp.util.RtpProtocol
import toUInt
import unsigned.toUInt
import java.nio.ByteBuffer

abstract class Packet {
    abstract val buf: ByteBuffer
    abstract val size: Int
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

/**
 * Basically just a wrapper around a buffer that inherits from [Packet]
 * so that it can be passed to logic which will further handle it.
 * [buf] must be sized to matched the data within (i.e. [buf.limit()]
 * should return the length of the data in the buffer).
 */
class UnparsedPacket(override val buf: ByteBuffer) : Packet() {
    override val size: Int = buf.limit()
}

open class SrtpProtocolPacket(override val buf: ByteBuffer) : Packet() {
    override val size: Int
        get() = buf.limit()
}

// https://tools.ietf.org/html/rfc3711#section-3.1
class SrtpPacket(buf: ByteBuffer) : SrtpProtocolPacket(buf) {
    override val size: Int
        get() {
            return header.size + payload.length
        }

    fun getAuthTag(tagLength: Int): BufferView {
        return BufferView(buf.array(), buf.limit() - tagLength, tagLength)
    }
    fun removeAuthTag(tagLength: Int) {
        buf.limit(buf.limit() - tagLength)
    }
    private val header = RtpHeader.fromBuffer(buf)
    // The size of the payload may change depending on whether or not the auth tag has been
    //  removed, but we know it always occupies the space between the end of the header
    //  and the end of the buffer.
    val payload: BufferView
        get() = BufferView(buf.array(), header.size, buf.limit() - header.size)
    val ssrc: Int = header.ssrc.toUInt()
    val seqNum: Int = header.sequenceNumber
}

// We can't have this derive from RtcpPacket since RtcpPacket is abstract (the
// particular type of RtcpPacket is needed).  Instead, it will take in an RtcpPacket
// instance (which could be an UnparsedRtcpPacket?)
// https://tools.ietf.org/html/rfc3711#section-3.4
class SrtcpPacket(buf: ByteBuffer) : SrtpProtocolPacket(buf) {
    val header = RtcpHeader(buf)
    val payload = BufferView(buf.array(), buf.position(), buf.limit() - buf.position())
    val ssrc: Int = header.senderSsrc.toUInt()
    fun getAuthTag(tagLength: Int): BufferView {
        return BufferView(buf.array(), buf.limit() - tagLength, tagLength)
    }
    fun setAuthTag(tag: ByteBuffer): Unit = TODO()
    fun getSrtcpIndex(tagLength: Int): Int {
        return buf.getInt(buf.limit() - (4 + tagLength)) and (0x80000000.inv()).toInt()
    }
    fun isEncrypted(tagLength: Int): Boolean {
        return buf.getInt(buf.limit() - (4 + tagLength)) and (0x80000000.inv()).toInt() == 0x80000000.toInt()
    }

}

//TODO: rtppacket/rtcppacket derive from this
class RtpProtocolPacket(override var buf: ByteBuffer) : Packet() {
    val isRtp: Boolean = RtpProtocol.isRtp(buf)
    override val size: Int = TODO()
    var ssrc: Int
}

class DtlsProtocolPacket(override var buf: ByteBuffer) : Packet() {
    override val size: Int = buf.limit()
}
