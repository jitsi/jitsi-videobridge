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
import java.nio.ByteBuffer


//TODO: handle one-byte header type 15:
//The local identifier value 15 is reserved for future extension and
//   MUST NOT be used as an identifier.  If the ID value 15 is
//   encountered, its length field should be ignored, processing of the
//   entire extension should terminate at that point, and only the
//   extension elements present prior to the element with ID 15
//   considered.

class RtpHeaderExtensions {
    companion object {
        const val GENERIC_HEADER_SIZE_BYTES = 4
        // Buf position should be at the start of the extension block.  This method assumes
        // there are extensions present (i.e. the X bit was set)
        fun parse(buf: ByteBuffer): MutableMap<Int, RtpHeaderExtension> {
            val headerExtensionType = buf.getShort()
            val headerExtensionParser = when {
                headerExtensionType.isOneByteHeaderType() -> ::RtpOneByteHeaderExtension
                headerExtensionType.isTwoByteHeaderType() -> ::RtpTwoByteHeaderExtension
                else -> throw Exception("unrecognized extension type: ${headerExtensionType.toString(16)}")
            }
            val lengthInWords = buf.getShort()
            val extensionStartPosition = buf.position()
            val extensionMap = mutableMapOf<Int, RtpHeaderExtension>()
            while (buf.position() < extensionStartPosition + (lengthInWords * 4)) {
                // Slice the buffer so the header extension receives a buffer whose 0 position starts
                // at the beginning of the extension itself
                val ext = headerExtensionParser(buf.slice())
                extensionMap[ext.id] = ext
                // Advance the buffer past the extension we just parsed and consume any padding after it
                buf.position(buf.position() + ext.size)
                consumePadding(buf)
            }
            return extensionMap
        }
        fun serialize(extMap: Map<Int, RtpHeaderExtension>, buf: ByteBuffer) {
            if (extMap.isEmpty()) {
                return
            }
            // Figure out what type of header extensions we have so we know which cookie
            // to write
            val cookie = when(extMap.values.stream().findFirst().get()) {
                is RtpOneByteHeaderExtension -> RtpOneByteHeaderExtension.COOKIE
                is RtpTwoByteHeaderExtension -> RtpTwoByteHeaderExtension.COOKIE
                else -> TODO()
            }
            buf.putShort(cookie)
            // We don't know the length yet so put 0 there for now
            val lengthPosition = buf.position()
            buf.putShort(0)

            // We'll use this start position to determine the length, but the
            // length doesn't include the generic extension header (the previous
            // 4 bytes), so mark the start after them
            val startPosition = buf.position()
            extMap.values.forEach {
                it.serializeToBuffer(buf)
            }
            while (buf.position() % 4 != 0) {
                // Add padding
                buf.put(0x00)
            }
            val length = (buf.position() - startPosition) / 4
            buf.putShort(lengthPosition, length.toShort())
        }

        private val Byte.isPadding: Boolean
            get() = this == 0.toByte()

        //TODO: do we need to put a size limit on this, in case the first byte
        // of whatever the next field is (the payload) is 0?
        // Returns the amount of padding consumed (in bytes)
        fun consumePadding(buf: ByteBuffer) {
            // At this point the buffer is at the end of the data.  Now we need
            // to (maybe) advance it further past any padding bytes.  Padding
            // bytes will always be 0
            var currByte: Byte = 0
            while (buf.hasRemaining() && currByte.isPadding) {
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
    }


}
