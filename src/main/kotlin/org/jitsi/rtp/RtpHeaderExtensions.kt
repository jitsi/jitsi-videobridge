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

import org.jitsi.rtp.extensions.rewindOneByte
import org.jitsi.rtp.extensions.subBuffer
import org.jitsi.rtp.util.ByteBufferUtils
import org.jitsi.rtp.util.ByteBufferUtils.Companion.EMPTY_BUFFER
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
class RtpHeaderExtensions : Serializable {
    // Exposing the extension map (as opposed to making it private) feels a little weird, but we'd have to implement
    // the entire MutableMap interface to keep things as convenient in order to make it private. Plus, no methods
    // rely on 'intercepting' calls to this map, they only care about whatever its current state is so at this point I
    // think it feels ok to expose it.
    val extensionMap: MutableMap<Int, RtpHeaderExtension>
    private val dataSizeBytes: Int
        get() {
            return if (extensionMap.isNotEmpty()) {
                EXTENSIONS_HEADER_SIZE + extensionMap.values.map { it.size }.sum()
            } else {
                0
            }
        }
    private val paddingSizeBytes: Int
        get() {
            var numPaddingBytes = 0
            while ((dataSizeBytes + numPaddingBytes) % 4 != 0) {
                numPaddingBytes++
            }
            return numPaddingBytes
        }
    /**
     * The size, in bytes, the extensions contained here will take up when serialized to a buffer
     */
    val size: Int
        get() = dataSizeBytes + paddingSizeBytes

    companion object {
        const val EXTENSIONS_HEADER_SIZE = 4
        /**
         * The offset, relative to the start of the entire header extensions block, where the first
         * actual extension starts
         */
        const val EXTENSION_START_OFFSET = EXTENSIONS_HEADER_SIZE

        val NO_EXTENSIONS = RtpHeaderExtensions(mutableMapOf())

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
        fun getHeaderExtensionType(buf: ByteBuffer): Short = buf.getShort()
        fun setHeaderExtensionType(buf: ByteBuffer, type: Short) {
            buf.putShort(type)
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
                headerExtensionType.isOneByteHeaderType() -> ::RtpOneByteHeaderExtension
                headerExtensionType.isTwoByteHeaderType() -> ::RtpTwoByteHeaderExtension
                else -> throw Exception("unrecognized extension type: ${headerExtensionType.toString(16)}")
            }
            val lengthBytes = getHeaderExtensionsLength(buf)
            buf.position(EXTENSION_START_OFFSET)
            val extensionMap = mutableMapOf<Int, RtpHeaderExtension>()
            while (buf.position() < lengthBytes) {
                val ext = headerExtensionParser(buf.subBuffer(buf.position()))
                extensionMap[ext.id] = ext
                buf.position(buf.position() + ext.size)
                // Consume any padding that may have been present after this extension
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
            extensions.forEach { buf.put(it.getBuffer()) }
            while (buf.position() % 4 != 0) {
                buf.put(PADDING_BYTE)
            }
            buf.rewind()
        }
    }

    constructor(buf: ByteBuffer) {
        extensionMap = getExtensions(buf)
    }

    constructor(extensions: MutableMap<Int, RtpHeaderExtension>) {
        this.extensionMap = extensions
    }

    override fun getBuffer(): ByteBuffer {
        if (extensionMap.isEmpty()) {
            return EMPTY_BUFFER
        }
        val buf = ByteBufferUtils.ensureCapacity(null, size)
        buf.rewind()
        buf.limit(size)
        // Figure out what type of header extensions we have so we know which cookie
        // to write
        val cookie = when(extensionMap.values.stream().findFirst().get()) {
            is RtpOneByteHeaderExtension -> RtpOneByteHeaderExtension.COOKIE
            is RtpTwoByteHeaderExtension -> RtpTwoByteHeaderExtension.COOKIE
            else -> throw Exception("unrecognized extension type")
        }
        setHeaderExtensionType(buf, cookie)
        setHeaderExtensionsLength(buf, size)
        setExtensionsAndPadding(buf, extensionMap.values)

        buf.rewind()
        return buf
    }

    override fun toString(): String = extensionMap.toString()
}

