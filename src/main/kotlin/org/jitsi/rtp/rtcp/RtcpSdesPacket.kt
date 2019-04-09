/*
 * Copyright @ 2018 - present 8x8, Inc.
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

import org.jitsi.rtp.util.BufferPool
import org.jitsi.rtp.util.RtpUtils
import org.jitsi.rtp.extensions.bytearray.ByteArrayUtils
import org.jitsi.rtp.extensions.bytearray.toHex
import org.jitsi.rtp.util.getByteAsInt
import org.jitsi.rtp.util.getIntAsLong
import java.nio.charset.StandardCharsets

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
 *
 *
 */
class RtcpSdesPacket(
    buffer: ByteArray,
    offset: Int,
    length: Int
) : RtcpPacket(buffer, offset, length) {

    val sdesChunks: List<SdesChunk> by lazy {
        getSdesChunks(buffer, offset, length)
    }

    override fun clone(): RtcpPacket = RtcpSdesPacket(cloneBuffer(), offset, length)

    companion object {
        const val PT = 202
        const val CHUNKS_OFFSET = 4

        fun getSdesChunks(buf: ByteArray, baseOffset: Int, length: Int): List<SdesChunk> {
            var currOffset = baseOffset + CHUNKS_OFFSET
            val sdesChunks = mutableListOf<SdesChunk>()
            while (currOffset < length) {
                val sdesChunk = SdesChunk(buf, currOffset)
                sdesChunks.add(sdesChunk)
                currOffset += sdesChunk.sizeBytes
            }
            return sdesChunks
        }
    }
}

// Offset should point to the start of this sdes chunk
class SdesChunk(
    buffer: ByteArray,
    offset: Int
) {
    val sizeBytes: Int by lazy {
        val dataSize = 4 + sdesItems.map(SdesItem::sizeBytes).sum()
        dataSize + RtpUtils.getNumPaddingBytes(dataSize)
    }
    val ssrc: Long by lazy { getSsrc(buffer, offset) }

    val sdesItems: List<SdesItem> by lazy {
        getSdesItems(buffer, offset)
    }

    companion object {
        const val SSRC_OFFSET = 0
        const val SDES_ITEMS_OFFSET = 4

        fun getSsrc(buf: ByteArray, baseOffset: Int): Long =
            buf.getIntAsLong(baseOffset + SSRC_OFFSET)

        fun getSdesItems(buf: ByteArray, baseOffset: Int): List<SdesItem> {
            var currOffset = baseOffset + SDES_ITEMS_OFFSET
            val sdesItems = mutableListOf<SdesItem>()
            while (true) {
                val currItem = SdesItem.parse(buf, currOffset)
                if (currItem == EmptySdesItem) {
                    break
                }
                sdesItems.add(currItem)
                currOffset += currItem.sizeBytes
            }
            return sdesItems
        }
    }
}

enum class SdesItemType(val value: Int) {
    EMPTY(0),
    UNKNOWN(-1),
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
 *
 * length = the length of the data field
 */
abstract class SdesItem(
    val type: SdesItemType
) {
    abstract val sizeBytes: Int

    companion object {
        const val SDES_ITEM_HEADER_SIZE = 2
        const val TYPE_OFFSET = 0
        const val LENGTH_OFFSET = 1
        const val DATA_OFFSET = 2

        fun getType(buf: ByteArray, baseOffset: Int): Int =
            buf.getByteAsInt(baseOffset + TYPE_OFFSET)

        fun getLength(buf: ByteArray, baseOffset: Int): Int =
            buf.getByteAsInt(baseOffset + LENGTH_OFFSET)

        fun copyData(buf: ByteArray, baseOffset: Int, dataLength: Int): ByteArray {
            val copy = BufferPool.getArray(dataLength)
            System.arraycopy(buf, baseOffset + DATA_OFFSET, copy, 0, dataLength)
            return copy
        }

        /**
         * [buf]'s current position should be at the beginning of
         * the SDES item
         */
        fun parse(buf: ByteArray, offset: Int): SdesItem {
            val typeValue = getType(buf, offset)
            val type = SdesItemType.fromInt(typeValue)
            return when (type) {
                SdesItemType.EMPTY -> EmptySdesItem
                else -> {
                    val length = getLength(buf, offset)
                    val dataBuf = if (length > 0) {
                        copyData(buf, offset, length)
                    } else {
                        ByteArrayUtils.emptyByteArray
                    }
                    return when (type) {
                        SdesItemType.CNAME -> CnameSdesItem(dataBuf)
                        else -> UnknownSdesItem(typeValue, dataBuf)
                    }
                }
            }
        }
    }
}

class UnknownSdesItem(
    private val sdesTypeValue: Int,
    private val dataField: ByteArray
) : SdesItem(SdesItemType.UNKNOWN) {

    override val sizeBytes: Int = SDES_ITEM_HEADER_SIZE + dataField.size

    override fun toString(): String {
        return "Unknown SDES type($sdesTypeValue) data = ${dataField.toHex()}"
    }
}

object EmptySdesItem : SdesItem(SdesItemType.EMPTY) {
    override val sizeBytes: Int = 1
}

/**
 * https://tools.ietf.org/html/rfc3550#section-6.5.1
 * 0                   1                   2                   3
 * 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |    CNAME=1    |     length    | user and domain name        ...
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 */
class CnameSdesItem(
    private val dataField: ByteArray
) : SdesItem(SdesItemType.CNAME) {

    override val sizeBytes: Int = SDES_ITEM_HEADER_SIZE + dataField.size

    val cname: String by lazy {
        String(dataField, StandardCharsets.US_ASCII)
    }

    override fun toString(): String = "CNAME: $cname"
}
