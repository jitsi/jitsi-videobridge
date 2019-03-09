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
package org.jitsi.rtp.rtp.header_extensions

import org.jitsi.rtp.Serializable
import org.jitsi.rtp.extensions.getBits
import org.jitsi.rtp.extensions.incrementPosition
import org.jitsi.rtp.extensions.putBits
import org.jitsi.rtp.extensions.subBuffer
import org.jitsi.rtp.extensions.unsigned.toPositiveInt
import org.jitsi.rtp.rtp.header_extensions.RtpHeaderExtensions.Companion.ONE_BYTE_COOKIE
import org.jitsi.rtp.rtp.header_extensions.RtpHeaderExtensions.Companion.TWO_BYTE_COOKIE
import java.nio.ByteBuffer

/**
 *  Checks if this [Short] matches the cookie used by one-byte
 *  header extensions.
 *  See https://tools.ietf.org/html/rfc5285#section-4.3
 */
fun Short.isOneByteHeaderType(): Boolean
        = this.compareTo(ONE_BYTE_COOKIE) == 0
/**
 *  Checks if this [Short] matches the cookie used by two-byte
 *  header extensions.
 *  See https://tools.ietf.org/html/rfc5285#section-4.3
 */
fun Short.isTwoByteHeaderType(): Boolean
        = TWO_BYTE_COOKIE.compareTo(this.toInt() and 0xfff0) == 0

enum class HeaderExtensionType(val value: Int) {
    ONE_BYTE_HEADER_EXT(1),
    TWO_BYTE_HEADER_EXT(2);

    fun toCookie(): Short {
        return when (value) {
            ONE_BYTE_HEADER_EXT.value -> ONE_BYTE_COOKIE
            TWO_BYTE_HEADER_EXT.value -> TWO_BYTE_COOKIE
            else -> throw Exception("Invalid header extension type: $value")
        }
    }

    companion object {
        fun fromDataLength(length: Int): HeaderExtensionType {
            return if (length > RtpHeaderExtension.MAX_ONE_BYTE_DATA_LENGTH) {
                TWO_BYTE_HEADER_EXT
            } else {
                ONE_BYTE_HEADER_EXT
            }
        }
        fun fromCookie(cookie: Short): HeaderExtensionType {
            return when {
                cookie.isOneByteHeaderType() -> ONE_BYTE_HEADER_EXT
                cookie.isTwoByteHeaderType() -> TWO_BYTE_HEADER_EXT
                else -> throw Exception("Invalid header extension cookie: $cookie")
            }
        }
    }
}

/**
 * https://tools.ietf.org/html/rfc5285#section-4.1
 *
 * There are two header extension formats:
 * 1) One-byte id/length
 * 2) Two-byte id/length
 *
 * The one-byte format is as follows:
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |  ID   | Length|     data...                                   |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * The length field represents the length of the data bytes that follow
 * minus 1 (i.e. a length value of 0 means there is 1 byte of data)
 *
 * The two-byte format is as follows:
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |      ID       |     L=1       |     data      |     ...       |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * The length field represents the length of the data bytes that follow
 * (i.e. a length of 0 means NO data bytes follow)
 */
abstract class RtpHeaderExtension(
    val id: Int
) : Serializable() {
    /**
     * The type of extension (one-byte or two-byte) this [RtpHeaderExtension]
     * should be, based on the size of its data.
     *
     * NOTE that we may not necessarily serialize it as this type, since
     * all extensions need to be serialized as the same type.
     */
    val type: HeaderExtensionType
        get() = HeaderExtensionType.fromDataLength(dataSizeBytes)

    /**
     * In the case of [RtpHeaderExtension], this represented the amount of bytes
     * necessary to serialize this specific extension (including the id, length
     * and data fields).  Note that this may not be how it ends up being serialized
     * (as we must use the same type for all extensions).
     */
    final override val sizeBytes: Int
        get() = sizeBytesAs(type)

    /**
     * How many bytes it would take to serialize this extension using the
     * given [HeaderExtensionType] format
     */
    fun sizeBytesAs(type: HeaderExtensionType): Int {
        return when (type) {
            HeaderExtensionType.ONE_BYTE_HEADER_EXT -> 1 + dataSizeBytes
            HeaderExtensionType.TWO_BYTE_HEADER_EXT -> 2 + dataSizeBytes
        }
    }

    /**
     * Serialize this extension to [buf] using the format determined by the given
     * header extension [type]
     */
    fun serializeToAs(type: HeaderExtensionType, buf: ByteBuffer) {
        when (type) {
            HeaderExtensionType.ONE_BYTE_HEADER_EXT -> {
                if (this.type == HeaderExtensionType.TWO_BYTE_HEADER_EXT) {
                    throw Exception("Can't serialize extension: data too long for one-byte style")
                }
                writeOneByteIdLen(id, dataSizeBytes, buf)
            }
            HeaderExtensionType.TWO_BYTE_HEADER_EXT -> writeTwoByteIdLen(id, dataSizeBytes, buf)
        }
        serializeData(buf)
    }

    /**
     * We can't serialize an extension without being told the format, so this method
     * should not be used.  See [serializeTo].
     */
    final override fun serializeTo(buf: ByteBuffer) =
        throw Exception("serializeTo is not usable for RtpHeaderExtension. Use #serializeToAs")

    /**
     * The size of the actual data content, in bytes
     */
    protected abstract val dataSizeBytes: Int

    /**
     * Subclasses must implement this function and write their data
     * into [buf] starting at [buf]'s current position.
     */
    protected abstract fun serializeData(buf: ByteBuffer)

    companion object {
        const val MAX_ONE_BYTE_DATA_LENGTH = 0xF
        /**
         * Sets the id and length of the data chunk of this extension, in bytes.  The length given
         * should be the logical length; this method will translate it into the proper value
         * (logical length - 1)
         */
        fun writeOneByteIdLen(id: Int, length: Int, buf: ByteBuffer) {
            buf.putBits(buf.position(), 0, id.toByte(), 4)
            val lengthValue = length - 1
            buf.putBits(buf.position(), 4, lengthValue.toByte(), 4)
            buf.incrementPosition(1)
        }

        fun writeTwoByteIdLen(id: Int, length: Int, buf: ByteBuffer) {
            buf.put(id.toByte())
            buf.put(length.toByte())
        }
    }
}

/**
 * An [UnparsedHeaderExtension] is one that reads just the id and data
 * from a buffer and stores them.  Because this class does not know which
 * header extensions map to which IDs, we do not try to parse them to
 * a specific extension.  Instead, we rely on callers to use
 * [RtpHeader#getExtensionAs] to get an extension and parse it as the
 * desired type
 */
class UnparsedHeaderExtension(
    id: Int,
    data: ByteBuffer
) : RtpHeaderExtension(id) {

    private val _data: ByteBuffer = (data.rewind() as ByteBuffer)
    val data: ByteBuffer
        get() = _data.duplicate().asReadOnlyBuffer()


    override val dataSizeBytes: Int
        get() = _data.limit()

    override fun serializeData(buf: ByteBuffer) {
        buf.put(data)
    }

    companion object {
        fun fromBuffer(buf: ByteBuffer, type: HeaderExtensionType): UnparsedHeaderExtension {
            val (id, length) = when(type) {
                HeaderExtensionType.ONE_BYTE_HEADER_EXT -> {
                    val id = buf.get(buf.position()).getBits(0, 4).toPositiveInt()
                    val length = buf.get(buf.position()).getBits(4, 4).toPositiveInt() + 1
                    buf.incrementPosition(1)
                    Pair(id, length)
                }
                HeaderExtensionType.TWO_BYTE_HEADER_EXT -> {
                    val id = buf.get().toPositiveInt()
                    val length = buf.get().toPositiveInt()
                    Pair(id, length)
                }
            }
            val data = buf.subBuffer(buf.position(), length)
            buf.incrementPosition(data.limit())
            return UnparsedHeaderExtension(id, data)
        }
    }
}

