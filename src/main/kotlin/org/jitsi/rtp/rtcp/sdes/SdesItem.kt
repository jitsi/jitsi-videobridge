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

package org.jitsi.rtp.rtcp.sdes

import org.jitsi.rtp.extensions.compareToFromBeginning
import org.jitsi.rtp.extensions.incrementPosition
import org.jitsi.rtp.extensions.subBuffer
import org.jitsi.rtp.extensions.toHex
import org.jitsi.rtp.extensions.unsigned.toPositiveInt
import org.jitsi.rtp.Serializable
import org.jitsi.rtp.util.ByteBufferUtils
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Objects

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
 */
abstract class SdesItem(
    val type: SdesItemType = SdesItemType.UNKNOWN,
    val data: ByteBuffer = ByteBufferUtils.EMPTY_BUFFER
) : Serializable(), Cloneable {
    val length: Int
        get() = data.limit()

    override val sizeBytes: Int
        get() = SDES_ITEM_HEADER_SIZE + data.limit()

    override fun serializeTo(buf: ByteBuffer) {
        buf.put(type.value.toByte())
        buf.put(length.toByte())
        data.rewind()
        buf.put(data)
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

    override fun hashCode(): Int = Objects.hash(type, data)

    companion object {
        const val SDES_ITEM_HEADER_SIZE = 2

        /**
         * [buf]'s current position should be at the beginning of
         * the SDES item
         */
        fun fromBuffer(buf: ByteBuffer): SdesItem {
            val type = buf.get().toPositiveInt()
            val typeValue = SdesItemType.fromInt(type)
            return when (typeValue) {
                SdesItemType.EMPTY -> EmptySdesItem
                else -> {
                    val length = buf.get().toPositiveInt()
                    val dataBuf = if (length > 0) {
                        buf.subBuffer(buf.position(), length)
                    } else {
                        ByteBufferUtils.EMPTY_BUFFER
                    }
                    buf.incrementPosition(length)
                    return when (typeValue) {
                        SdesItemType.CNAME -> CnameSdesItem.fromDataBuf(dataBuf)
                        else -> UnknownSdesItem(type, dataBuf)
                    }
                }
            }
        }
    }
}

class UnknownSdesItem(
    private val sdesTypeValue: Int,
    data: ByteBuffer = ByteBufferUtils.EMPTY_BUFFER
) : SdesItem(SdesItemType.UNKNOWN, data) {


    override fun toString(): String {
        return "Unknown SDES type($sdesTypeValue) data = ${data.toHex()}"
    }

    companion object {
        fun fromBuffer(buf: ByteBuffer): UnknownSdesItem {
            val type = buf.get().toPositiveInt()
            val length = buf.get().toPositiveInt()
            val data = if (length > 0) {
                buf.subBuffer(buf.position(), length)
            } else {
                ByteBufferUtils.EMPTY_BUFFER
            }
            return UnknownSdesItem(type, data)
        }
    }
}

object EmptySdesItem : SdesItem(SdesItemType.EMPTY) {
    // Has no length field
    override val sizeBytes: Int = 1

    override fun serializeTo(buf: ByteBuffer) {
        buf.put(type.value.toByte())
    }
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
    val cname: String = ""
) : SdesItem(SdesItemType.CNAME, ByteBuffer.wrap(cname.toByteArray(StandardCharsets.US_ASCII))) {

    override fun toString(): String = "CNAME: $cname"

    companion object {
        /**
         * [buf] here isn't the entire SDES chunk buf, it's just the data
         * field
         */
        fun fromDataBuf(buf: ByteBuffer): CnameSdesItem {
            return CnameSdesItem(String(buf.array(), buf.arrayOffset(), buf.limit(), StandardCharsets.US_ASCII))
        }
    }
}
