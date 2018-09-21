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
package org.jitsi.rtp.rtcp

import org.jitsi.rtp.extensions.getBitAsBool
import org.jitsi.rtp.extensions.getBits
import org.jitsi.rtp.extensions.putBitAsBoolean
import org.jitsi.rtp.extensions.putBits
import org.jitsi.rtp.extensions.subBuffer
import toUInt
import unsigned.toUInt
import unsigned.toULong
import unsigned.toUShort
import java.nio.ByteBuffer

// https://tools.ietf.org/html/rfc3550#section-6.1
//  0                   1                   2                   3
//  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
// +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
// |V=2|P|    RC   |   PT=SR=200   |             length            |
// +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
// |                         SSRC of sender                        |
// +=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+

open class RtcpHeader {
    private var buf: ByteBuffer? = null
    val size: Int = RtcpHeader.SIZE_BYTES
    var version: Int
    var hasPadding: Boolean
    var reportCount: Int
    var packetType: Int
    var length: Int
    var senderSsrc: Long

    constructor(buf: ByteBuffer) : super() {
        this.buf = buf.subBuffer(0, RtcpHeader.SIZE_BYTES)
        this.version = RtcpHeader.getVersion(buf)
        this.hasPadding = RtcpHeader.hasPadding(buf)
        this.reportCount = RtcpHeader.getReportCount(buf)
        this.packetType = RtcpHeader.getPacketType(buf)
        this.length = RtcpHeader.getLength(buf)
        this.senderSsrc = RtcpHeader.getSenderSsrc(buf)
    }

    @JvmOverloads
    constructor(
        version: Int = 2,
        hasPadding: Boolean = false,
        reportCount: Int = 0,
        packetType: Int = 0,
        length: Int = 0,
        senderSsrc: Long = 0
    ) : super() {
        this.version = version
        this.hasPadding = hasPadding
        this.reportCount = reportCount
        this.packetType = packetType
        this.length = length
        this.senderSsrc = senderSsrc
    }

    fun getBuffer(): ByteBuffer {
        if (this.buf == null) {
            this.buf = ByteBuffer.allocate(RtcpHeader.SIZE_BYTES)
        }
        //TODO: in the future we can use a dirty flag to check
        // if/which we need to sync
        RtcpHeader.setVersion(buf!!, version)
        RtcpHeader.setPadding(buf!!, hasPadding)
        RtcpHeader.setReportCount(buf!!, reportCount)
        RtcpHeader.setPacketType(buf!!, packetType)
        RtcpHeader.setLength(buf!!, length)
        RtcpHeader.setSenderSsrc(buf!!, senderSsrc)

        this.buf!!.rewind()
        //TODO: return as readonly?
        return this.buf!!
    }

    companion object {
        const val SIZE_BYTES = 8
        fun getVersion(buf: ByteBuffer): Int = buf.get(0).getBits(0, 2).toUInt()
        fun setVersion(buf: ByteBuffer, version: Int) = buf.putBits(0, 0, version.toByte(), 2)

        fun hasPadding(buf: ByteBuffer): Boolean = buf.get(0).getBitAsBool(2)
        fun setPadding(buf: ByteBuffer, hasPadding: Boolean) = buf.putBitAsBoolean(0, 2, hasPadding)

        fun getReportCount(buf: ByteBuffer): Int = buf.get(0).getBits(3, 5).toUInt()
        fun setReportCount(buf: ByteBuffer, reportCount: Int) = buf.putBits(0, 3, reportCount.toByte(), 5)

        fun getPacketType(buf: ByteBuffer): Int = buf.get(1).toUInt()
        fun setPacketType(buf: ByteBuffer, packetType: Int) {
            buf.put(1, packetType.toByte())
        }

        fun getLength(buf: ByteBuffer): Int = buf.getShort(2).toUInt()
        fun setLength(buf: ByteBuffer, length: Int) {
            buf.putShort(2, length.toUShort())
        }

        fun getSenderSsrc(buf: ByteBuffer): Long = buf.getInt(4).toULong()
        fun setSenderSsrc(buf: ByteBuffer, senderSsrc: Long) {
            buf.putInt(4, senderSsrc.toUInt())
        }
    }

    override fun toString(): String {
        return with(StringBuffer()) {
            appendln("version: $version")
            appendln("hasPadding: $hasPadding")
            appendln("reportCount: $reportCount")
            appendln("packetType: $packetType")
            appendln("length: ${this@RtcpHeader.length}")
            appendln("senderSsrc: $senderSsrc")
            this.toString()
        }
    }
}
