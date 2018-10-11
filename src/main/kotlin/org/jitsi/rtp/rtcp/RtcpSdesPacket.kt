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

import org.jitsi.rtp.Packet
import org.jitsi.rtp.Serializable
import org.jitsi.rtp.extensions.clone
import org.jitsi.rtp.extensions.compareToFromBeginning
import org.jitsi.rtp.extensions.subBuffer
import org.jitsi.rtp.extensions.toHex
import org.jitsi.rtp.util.ByteBufferUtils
import toUInt
import unsigned.toUByte
import unsigned.toUInt
import unsigned.toULong
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

enum class SdesItemType(val value: Int) {
    UNKNOWN(-1),
    EMPTY(0),
    CNAME(1);

    companion object {
        private val map = SdesItemType.values().associateBy(SdesItemType::value)
        fun fromInt(type: Int): SdesItemType = map.getOrDefault(type, UNKNOWN)
    }
}

/**
 * 0                   1                   2                   3
 * 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |    Type       |     length    |          data               ...
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 */
open class SdesItem : Serializable {
    private var buf: ByteBuffer? = null
    val type: SdesItemType
    val length: Int
        get() = data.limit()
    val data: ByteBuffer

    val size: Int
        get() {
            return if (this == SdesItem.EMPTY_ITEM) {
                // Empty item only has the type field
                1
            } else {
                1 /* type field */ +
                1 /* length field */ +
                length
            }
        }

    companion object {
        val EMPTY_ITEM = SdesItem(SdesItemType.EMPTY, ByteBufferUtils.EMPTY_BUFFER)
        const val SDES_ITEM_HEADER_SIZE = 2
        const val DATA_OFFSET = SDES_ITEM_HEADER_SIZE
        /**
         * [buf] position 0 should be at the start of the SDES item
         */
        fun getType(buf: ByteBuffer): SdesItemType = SdesItemType.fromInt(buf.get(0).toUInt())
        fun setType(buf: ByteBuffer, type: SdesItemType) {
            buf.put(type.value.toUByte())
        }

        fun getLength(buf: ByteBuffer): Int = buf.get(1).toUInt()
        fun setLength(buf: ByteBuffer, length: Int) {
            buf.put(1, length.toUByte())
        }

        fun getData(buf: ByteBuffer, lengthBytes: Int): ByteBuffer = buf.subBuffer(2, lengthBytes)
        fun setData(buf: ByteBuffer, dataBuf: ByteBuffer) {
            buf.position(DATA_OFFSET)
            buf.put(dataBuf)
            buf.rewind()
        }

        /**
         * When parsing from a buffer, this should be used instead of the buffer constructor so that if we parse
         * the 'empty' SDES item, we can return [EMPTY_ITEM]. [buf]'s position 0 should start at the beginning of
         * the SDES item
         */
        fun fromBuffer(buf: ByteBuffer): SdesItem {
            val type = getType(buf)
            return when (type) {
                SdesItemType.EMPTY -> EMPTY_ITEM
                SdesItemType.CNAME -> CnameSdesItem(buf)
                else -> SdesItem(buf)
            }
        }
    }

    protected constructor(buf: ByteBuffer) {
        type = getType(buf)
        data = getData(buf, getLength(buf))
        this.buf = buf.subBuffer(0, size)
    }

    constructor(
        type: SdesItemType,
        data: ByteBuffer
    ) {
        this.type = type
        this.data = data
    }

    override fun getBuffer(): ByteBuffer {
        val b = ByteBufferUtils.ensureCapacity(buf, size)
        b.rewind()
        b.limit(size)

        setType(b, type)
        if (this != EMPTY_ITEM) {
            setLength(b, length)
            setData(b, data)
        }

        b.rewind()
        this.buf = b
        return b
    }

    override fun toString(): String {
        return with (StringBuffer()) {
            appendln("SdesItem: $type ${data.toHex()}")

            toString()
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other?.javaClass != javaClass) {
            return false
        }
        other as SdesItem
        return (type == other.type &&
                data.compareToFromBeginning(other.data) == 0)
    }

    override fun hashCode(): Int = type.hashCode() + data.hashCode()
}

/**
 * https://tools.ietf.org/html/rfc3550#section-6.5.1
 * 0                   1                   2                   3
 * 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |    CNAME=1    |     length    | user and domain name        ...
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 */
class CnameSdesItem : SdesItem {
    val cname: String = String(data.array(), data.arrayOffset(), data.limit(), StandardCharsets.US_ASCII)

    constructor(buf: ByteBuffer) : super(buf)

    constructor(cname: String) : super(SdesItemType.CNAME, ByteBuffer.wrap(cname.toByteArray(StandardCharsets.US_ASCII)))

    override fun toString(): String = "CNAME: $cname"
}

/**
 *        0                   1                   2                   3
 *        0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 *        +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * header |V=2|P|    SC   |  PT=SDES=202  |             length            |
 *        +=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+
 * chunk  |                          SSRC/CSRC_1                          |
 *   1    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *        |                           SDES items                          |
 *        |                              ...                              |
 *        +=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+
 * chunk  |                          SSRC/CSRC_2                          |
 *   2    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *        |                           SDES items                          |
 *        |                              ...                              |
 *        +=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+
 */
class SdesChunk : Serializable {
    private var buf: ByteBuffer? = null
    val ssrc: Long
    val sdesItems: MutableList<SdesItem>
    private val dataSize: Int
        get() = 4 + sdesItems.map(SdesItem::size).sum() + SdesItem.EMPTY_ITEM.size
    private val paddingSize: Int
        get() {
            var paddingSize = 0
            while ((dataSize + paddingSize) % 4 != 0) {
                paddingSize++
            }
            return paddingSize
        }
    val size: Int
        get() = dataSize + paddingSize

    companion object {
        /**
         * The offset of the SDES items relative to the start of the chunk
         */
        const val SDES_ITEMS_OFFSET = 4
        /**
         * [buf]'s position 0 should be at the start of the SdesChunk
         */
        fun getSsrc(buf: ByteBuffer): Long {
            return buf.getInt(0).toULong()
        }
        fun setSsrc(buf: ByteBuffer, ssrc: Long) {
            buf.putInt(0, ssrc.toUInt())
        }

        /**
         * [buf]'s position 0 should be at the start of the SdesChunk.
         * We do NOT add the EMPTY_ITEM at the end of the parsed list: this item
         * is treated differently and is automatically included when calculating
         * the size and automatically appended in [setSdesItems]
         */
        fun getSdesItems(buf: ByteBuffer): MutableList<SdesItem> {
            val sdesItems = mutableListOf<SdesItem>()
            var currSdesItemBuf = buf.subBuffer(SDES_ITEMS_OFFSET)
            var item = SdesItem.fromBuffer(currSdesItemBuf)
            while (item != SdesItem.EMPTY_ITEM) {
                sdesItems.add(item)
                currSdesItemBuf = currSdesItemBuf.subBuffer(item.size)
                item = SdesItem.fromBuffer(currSdesItemBuf)
            }

            buf.rewind()

            return sdesItems
        }
        fun setSdesItems(buf: ByteBuffer, sdesItems: Collection<SdesItem>) {
            buf.position(SDES_ITEMS_OFFSET)
            sdesItems.forEach {
                buf.put(it.getBuffer())
            }
            buf.put(SdesItem.EMPTY_ITEM.getBuffer())
            while (buf.position() % 4 != 0) {
                buf.put(0x00)
            }
            buf.rewind()
        }
    }

    /**
     * [buf]'s position 0 should be at the start of the SDES chunk
     */
    constructor(buf: ByteBuffer) {
        this.ssrc = getSsrc(buf)
        this.sdesItems = getSdesItems(buf)
        this.buf = buf.subBuffer(size)
    }

    constructor(
        ssrc: Long = 0,
        sdesItems: MutableList<SdesItem> = mutableListOf()
    ) {
        this.ssrc = ssrc
        this.sdesItems = sdesItems
    }

    override fun getBuffer(): ByteBuffer {
        val b = ByteBufferUtils.ensureCapacity(buf, size)
        b.rewind()
        b.limit(size)

        setSsrc(b, ssrc)
        setSdesItems(b, sdesItems)

        b.rewind()
        buf = b
        return b
    }

    override fun toString(): String {
        return with (StringBuffer()) {
            appendln("SDES chunk $ssrc")
            sdesItems.forEach { appendln(it.toString()) }

            toString()
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other?.javaClass != javaClass) {
            return false
        }
        other as SdesChunk
        return (ssrc == other.ssrc &&
                sdesItems.size == other.sdesItems.size &&
                sdesItems.containsAll(other.sdesItems))
    }

    override fun hashCode(): Int = ssrc.hashCode() + sdesItems.hashCode()
}

/**
 * https://tools.ietf.org/html/rfc3550#section-6.5
 *        0                   1                   2                   3
 *        0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 *        +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * header |V=2|P|    SC   |  PT=SDES=202  |             length            |
 *        +=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+
 * chunk  |                          SSRC/CSRC_1                          |
 *   1    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *        |                           SDES items                          |
 *        |                              ...                              |
 *        +=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+
 * chunk  |                          SSRC/CSRC_2                          |
 *   2    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *        |                           SDES items                          |
 *        |                              ...                              |
 *        +=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+
 */
class RtcpSdesPacket : RtcpPacket {
    // When calculating the size, we take the size of the header but subtract 4, since 4 bytes of the
    // 'header' are actually part of the first chunk
    override val size: Int
        get() = header.size - 4 + chunks.map(SdesChunk::size).sum()
    private var buf: ByteBuffer? = null
    override var header: RtcpHeader
    val chunks: MutableList<SdesChunk>

    companion object {
        const val PT: Int = 202
        const val CHUNK_OFFSET: Int = 4

        /**
         * [buf]'s position 0 should start at the beginning of the RTCP SDES packet
         * (the start of the header)
         */
        fun getChunks(buf: ByteBuffer, chunkCount: Int): MutableList<SdesChunk> {
            buf.position(CHUNK_OFFSET)
            val chunks = mutableListOf<SdesChunk>()
            repeat (chunkCount) {
                val chunk = SdesChunk(buf.subBuffer(buf.position()))
                chunks.add(chunk)
                buf.position(buf.position() + chunk.size)
            }

            buf.rewind()
            return chunks
        }

        /**
         * [buf]'s position 0 should start at the beginning of the RTCP SDES packet
         * (the start of the header)
         */
        fun setChunks(buf: ByteBuffer, chunks: Collection<SdesChunk>) {
            buf.position(CHUNK_OFFSET)
            chunks.forEach {
                buf.put(it.getBuffer())
            }
        }
    }

    constructor(buf: ByteBuffer) {
        this.header = RtcpHeader(buf)
        this.chunks = getChunks(buf, header.reportCount)
        this.buf = buf.subBuffer(0, size)
    }

    constructor(
        header: RtcpHeader = RtcpHeader(),
        chunks: MutableList<SdesChunk> = mutableListOf()
    ) {
        this.header = header
        this.chunks = chunks
    }

    override fun getBuffer(): ByteBuffer {
        val b = ByteBufferUtils.ensureCapacity(buf, size)
        b.rewind()
        b.limit(size)

        header.length = lengthValue
        header.reportCount = chunks.size
        b.put(header.getBuffer())
        setChunks(b, chunks)

        b.rewind()
        buf = b
        return b
    }

    override fun toString(): String {
        return with (StringBuffer()) {
            appendln("RTCP SDES packet")
            appendln(header.toString())
            chunks.forEach { appendln(it.toString())}

            toString()
        }
    }

    override fun clone(): Packet = RtcpSdesPacket(getBuffer().clone())
}
