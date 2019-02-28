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

import org.jitsi.rtp.extensions.incrementPosition
import org.jitsi.rtp.extensions.rewindOneByte
import org.jitsi.rtp.extensions.subBuffer
import org.jitsi.rtp.Serializable
import unsigned.toUInt
import unsigned.toUShort
import java.nio.ByteBuffer

/**
 *
 * 0                   1                   2                   3
 * 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |            cookie             |           length              |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                      ...extension data...                     |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * Models a set of RTP header extensions.  Handles the parsing and serialization of the generic header
 * and individual extensions.
 *
 */
class RtpHeaderExtensions(
    private val extensionMap: MutableMap<Int, RtpHeaderExtension> = mutableMapOf()
) : Serializable(), Cloneable {
    private var sizeNeedsToBeRecalculated = true

    private var _sizeBytes: Int = 0

    override val sizeBytes: Int
        get() {
            if (sizeNeedsToBeRecalculated) {
                val dataSizeBytes = if (extensionMap.isNotEmpty()) {
                    EXTENSIONS_HEADER_SIZE + extensionMap.values.map { it.sizeBytes }.sum()
                } else {
                    0
                }
                var numPaddingBytes = 0
                while ((dataSizeBytes + numPaddingBytes) % 4 != 0) {
                    numPaddingBytes++
                }
                _sizeBytes = dataSizeBytes + numPaddingBytes
                sizeNeedsToBeRecalculated = false
            }
            return _sizeBytes
        }
    fun isEmpty(): Boolean = extensionMap.isEmpty()
    fun isNotEmpty(): Boolean = extensionMap.isNotEmpty()
    fun getExtension(id: Int): RtpHeaderExtension? = extensionMap.getOrDefault(id, null)
    fun addExtension(id: Int, extension: RtpHeaderExtension) {
        extensionMap[id] = extension
        sizeNeedsToBeRecalculated = true
    }

    override fun serializeTo(buf: ByteBuffer) {
        if (extensionMap.isEmpty()) {
            return
        }
        // The helpers below assume they should start writing to position 0
        // in the given buf, so create a temp buffer that start at where they
        // expect
        val absBuf = buf.subBuffer(buf.position())
        val cookie = when(extensionMap.values.stream().findFirst().get()) {
            is RtpOneByteHeaderExtension -> RtpOneByteHeaderExtension.COOKIE
            is RtpTwoByteHeaderExtension -> RtpTwoByteHeaderExtension.COOKIE
            else -> throw Exception("unrecognized extension type")
        }
        setHeaderExtensionType(absBuf, cookie)
        setHeaderExtensionsLength(absBuf, sizeBytes)
        setExtensionsAndPadding(absBuf, extensionMap.values)
        buf.incrementPosition(sizeBytes)
    }

    public override fun clone(): RtpHeaderExtensions =
        RtpHeaderExtensions(extensionMap.toMutableMap())

    override fun toString(): String = extensionMap.toString()

    companion object {
        const val EXTENSIONS_HEADER_SIZE = 4
        /**
         * The offset, relative to the start of the entire header extensions block, where the first
         * actual extension starts
         */
        const val EXTENSION_START_OFFSET = EXTENSIONS_HEADER_SIZE

        val NO_EXTENSIONS = RtpHeaderExtensions(mutableMapOf())

        fun fromBuffer(buf: ByteBuffer): RtpHeaderExtensions {
            val extensionsMap = getExtensions(buf)
            return RtpHeaderExtensions(extensionsMap)
        }

        private fun Short.isOneByteHeaderType(): Boolean
                = this.compareTo(RtpOneByteHeaderExtension.COOKIE) == 0
        /**
         *  Checks if this [Short] matches the IDs used by [RtpTwoByteHeaderExtension].
         *  See https://tools.ietf.org/html/rfc5285#section-4.3
         */
        fun Short.isTwoByteHeaderType(): Boolean
                = RtpTwoByteHeaderExtension.COOKIE.compareTo(this.toInt() and 0xfff0) == 0


        private const val PADDING_BYTE = 0x00.toByte()
        private fun Byte.isPadding(): Boolean = this == PADDING_BYTE
        /**
         * Move [buf]'s position past all padding (0) bytes, to a maxium position of [maxPosition].
         */
        fun consumePadding(buf: ByteBuffer, maxPosition: Int) {
            // At this point the buffer is at the end of the data.  Now we need
            // to (maybe) advance it further past any padding bytes.  Padding
            // bytes will always be 0
            var currByte: Byte = 0
            while (buf.hasRemaining() && buf.position() <= maxPosition && currByte.isPadding()) {
                currByte = buf.get()
            }
            if (currByte != 0.toByte()) {
                // We might have stopped because we reached the end of the buffer
                // or because we hit the payload after padding.  If we hit
                // the payload, the rewind the buffer by one so the next time
                // we read we get this next non-padding byte)
                // Now we've hit the ID of the next extension, so we need to rewind the buffer one
                // byte
                buf.rewindOneByte()
            }
        }
        fun getHeaderExtensionType(buf: ByteBuffer): Short = buf.getShort(0)
        fun setHeaderExtensionType(buf: ByteBuffer, type: Short) {
            buf.putShort(0, type)
        }

        /**
         * Return the length of the header extension block in bytes (NOTE that in the packet
         * it is represented as words and does not include the rtp extension header; this method
         * does the conversion to get that total length in bytes)
         */
        fun getHeaderExtensionsLength(buf: ByteBuffer): Int = (buf.getShort(2).toUInt() + 1) * 4
        fun setHeaderExtensionsLength(buf: ByteBuffer, lengthBytes: Int) {
            buf.putShort(2, (((lengthBytes + 3) / 4) - 1).toUShort())
        }
        /**
         * [buf] should start at the beginning of the extension block (i.e. the start of the generic
         * extension header).
         */
        fun getExtensions(buf: ByteBuffer): MutableMap<Int, RtpHeaderExtension> {
            val headerExtensionType = getHeaderExtensionType(buf)
            val headerExtensionParser: (ByteBuffer) -> RtpHeaderExtension = when {
                headerExtensionType.isOneByteHeaderType() -> RtpOneByteHeaderExtension.Companion::fromBuffer
                headerExtensionType.isTwoByteHeaderType() -> RtpTwoByteHeaderExtension.Companion::fromBuffer
                else -> throw Exception("unrecognized extension type: ${headerExtensionType.toString(16)}")
            }
            val lengthBytes = getHeaderExtensionsLength(buf)
            buf.position(EXTENSION_START_OFFSET)
            val extensionMap = mutableMapOf<Int, RtpHeaderExtension>()
            while (buf.position() < lengthBytes) {
                val ext = headerExtensionParser(buf.subBuffer(buf.position()))
                extensionMap[ext.id] = ext
                buf.position(buf.position() + ext.sizeBytes)
                // Consume any padding that may have been present after this extension:
                // "[Padding] may be placed between extension elements, if desired for
                // alignment, or after the last extension element, if needed for padding."
                // -https://tools.ietf.org/html/rfc5285#section-4.1
                consumePadding(buf, lengthBytes)
            }

            return extensionMap
        }

        /**
         * Serialize the given extensions to the given buffer.  [buf] is assumed to have position
         * 0 at the start of the entire extension block (i.e. at the start of the generic cookie
         * header), so the extensions will be written at EXTENSION_START_OFFSET.  When finished,
         * the buffer's position will be restored back to 0
         */
        fun setExtensionsAndPadding(buf: ByteBuffer, extensions: Collection<RtpHeaderExtension>) {
            buf.position(EXTENSION_START_OFFSET)
            extensions.forEach {
                it.serializeTo(buf)
            }
            while (buf.position() % 4 != 0) {
                buf.put(PADDING_BYTE)
            }
            buf.rewind()
        }
    }
}

