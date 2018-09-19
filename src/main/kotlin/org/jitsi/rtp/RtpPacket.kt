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

import org.jitsi.rtp.extensions.clone
import org.jitsi.rtp.extensions.subBuffer
import org.jitsi.rtp.util.BufferView
import unsigned.toUInt
import java.nio.ByteBuffer

// would https://github.com/kotlin-graphics/kotlin-unsigned be useful?

// https://tools.ietf.org/html/rfc3550#section-5.1
// 0                   1                   2                   3
// 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
// +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
// |V=2|P|X|  CC   |M|     PT      |       sequence number         |
// +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
// |                           timestamp                           |
// +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
// |           synchronization source (SSRC) identifier            |
// +=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+
// |            contributing source (CSRC) identifiers             |
// |                             ....                              |
// +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+

open class RtpPacket : Packet {
    private var buf: ByteBuffer? = null
    var header: RtpHeader
    var payload: ByteBuffer
    override val size: Int
        get() = header.size + payload.limit()

    constructor(buf: ByteBuffer) {
        this.buf = buf
        this.header = RtpHeader(buf)
        this.payload = buf.subBuffer(header.size)
    }

    constructor(
        header: RtpHeader = RtpHeader(),
        payload: ByteBuffer = ByteBuffer.allocate(0)
    ) {
        this.header = header
        this.payload = payload
    }

    //TODO: need to include this
    fun getPaddingSize(): Int {
        return if (!this.header.hasPadding) {
            0
        } else {
            // The last octet of the padding contains a count of how many
            // padding octets should be ignored, including itself.

            // It's an 8-bit unsigned number.
            payload.get(payload.limit() - 1).toUInt()
        }
    }

    override fun getBuffer(): ByteBuffer {
        // TODO: i think this could be improved, as it gets a bit tricky
        // in the 'rtp header got bigger between parse and getBuffer'
        // scenario.  we use 'limit' being less than size to suggest to us
        // that things need to be moved and therefore we need to allocate
        // but we can probably do better (like RawPacket does when it checks
        // for available chunks in the backing array that can be used).  We
        // need to take into account the header and payload sizes/growths separately.
        if (this.buf == null || this.buf!!.limit() < this.size) {
            this.buf = ByteBuffer.allocate(this.size)
        }
        this.buf!!.limit(this.size)
        this.buf!!.rewind()
        this.buf!!.put(header.getBuffer())
        this.buf!!.put(payload)

        this.buf!!.rewind()
        return this.buf!!
    }

    override fun clone(): Packet {
        return RtpPacket(getBuffer().clone())
    }

    override fun toString(): String {
        return with (StringBuffer()) {
            appendln("RTP packet")
            appendln("size: $size")
            append(header.toString())
            appendln("payload size: ${payload.limit()}")
            toString()
        }
    }
}

