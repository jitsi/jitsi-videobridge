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

import org.jitsi.rtp.extensions.unsigned.toPositiveInt
import org.jitsi.rtp.util.getShortAsInt
import kotlin.experimental.and
import kotlin.experimental.or

/** An abstract parser for header extensions. */
abstract class HeaderExtensionParser {
    abstract val headerExtensionLabel: Int

    abstract val extHeaderSizeBytes: Int
    abstract val minimumExtSizeBytes: Int

    /** Determine whether this header extension parser can parse header extensions with the given
     * "defined by profile" field.
     */
    abstract fun isMatchingType(profileField: Int): Boolean

    abstract fun getId(buf: ByteArray, offset: Int): Int

    /**
     * Write the given ID and length to [buf] at [offset]
     */
    abstract fun writeIdAndLength(id: Int, dataLength: Int, buf: ByteArray, offset: Int)

    /**
     * Return the entire size, in bytes, of the extension in [buf] whose header
     * starts at [offset]
     */
    fun getEntireLengthBytes(buf: ByteArray, offset: Int): Int = getDataLengthBytes(buf, offset) + extHeaderSizeBytes

    /**
     * Return the data size, in bytes, of the extension in [buf] whose header
     * starts at [offset].
     */
    abstract fun getDataLengthBytes(buf: ByteArray, offset: Int): Int
}

object OneByteHeaderExtensionParser : HeaderExtensionParser() {
    override val headerExtensionLabel = 0xBEDE

    override val extHeaderSizeBytes = 1
    override val minimumExtSizeBytes = 2

    override fun isMatchingType(profileField: Int): Boolean = profileField == headerExtensionLabel

    override fun getId(buf: ByteArray, offset: Int): Int = (buf[offset].toInt() ushr 4) and 0x0F

    override fun writeIdAndLength(id: Int, dataLength: Int, buf: ByteArray, offset: Int) {
        require(id in 1..14)
        require(dataLength in 1..16)
        buf[offset] = ((id and 0x0F) shl 4).toByte() or ((dataLength - 1) and 0x0F).toByte()
    }

    override fun getDataLengthBytes(buf: ByteArray, offset: Int): Int =
        ((buf[offset] and 0x0F.toByte())).toPositiveInt() + 1
}

object TwoByteHeaderExtensionParser : HeaderExtensionParser() {
    /* We don't support "value 256", in the low four bits of the "defined by profile" field. */
    override val headerExtensionLabel = 0x1000
    private const val HEADER_EXTENSION_MASK = 0xFFF0

    override val extHeaderSizeBytes = 2
    override val minimumExtSizeBytes = 2

    override fun isMatchingType(profileField: Int): Boolean =
        (profileField and HEADER_EXTENSION_MASK) == headerExtensionLabel

    override fun getId(buf: ByteArray, offset: Int): Int = buf[offset].toInt()

    override fun writeIdAndLength(id: Int, dataLength: Int, buf: ByteArray, offset: Int) {
        require(id in 1..255)
        require(dataLength in 0..255)

        buf[offset] = id.toByte()
        buf[offset + 1] = dataLength.toByte()
    }

    override fun getDataLengthBytes(buf: ByteArray, offset: Int): Int = buf[offset + 1].toPositiveInt()
}

private val headerExtensionParsers = arrayOf(OneByteHeaderExtensionParser, TwoByteHeaderExtensionParser)

class HeaderExtensionHelpers {
    companion object {
        // The size of the header extension block header
        const val TOP_LEVEL_EXT_HEADER_SIZE_BYTES = 4

        /**
         * Return the "defined by profile" header extension type field, as an integer.
         *
         * [offset] points to the start of the header extensions block.  Should
         * only be called if it's been verified that the header held in [buf]
         * actually contains extensions
         */
        fun getExtensionsProfileType(buf: ByteArray, offset: Int) = buf.getShortAsInt(offset)

        /**
         * Return the length of the entire header extensions block, including
         * the header, in bytes.
         *
         * [offset] points to the start of the header extensions block.  Should
         * only be called if it's been verified that the header held in [buf]
         * actually contains extensions
         */
        fun getExtensionsTotalLength(buf: ByteArray, offset: Int) =
            TOP_LEVEL_EXT_HEADER_SIZE_BYTES + buf.getShortAsInt(offset + 2) * 4

        /**
         * Return a header extension parser for header extensions with the given "defined by profile" field
         * value, or null if none match (i.e. either cryptex-encrypted or proprietary header extensions).
         * Does the right thing for the invalid value -1 returned by [RtpHeader.getExtensionsProfileType]
         * when no extensions are present.
         */
        fun getHeaderExtensionParser(profileField: Int): HeaderExtensionParser? =
            headerExtensionParsers.firstOrNull { it.isMatchingType(profileField) }
    }
}
