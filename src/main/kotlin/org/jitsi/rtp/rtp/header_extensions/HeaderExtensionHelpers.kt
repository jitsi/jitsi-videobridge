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
import unsigned.ushr
import kotlin.experimental.and
import kotlin.experimental.or

class HeaderExtensionHelpers {
    companion object {
        const val MINIMUM_EXT_SIZE_BYTES = 2
        // The size of a one-byte header extension header
        const val EXT_HEADER_SIZE_BYTES = 1
        // The size of the header extension block header
        const val TOP_LEVEL_EXT_HEADER_SIZE_BYTES = 4

        fun getId(buf: ByteArray, offset: Int): Int =
            ((buf.get(offset) and 0xF0.toByte()) ushr 4).toPositiveInt()

        fun setId(id: Int, buf: ByteArray, offset: Int) {
            // Clear the old extension ID
            buf[offset] = buf[offset] and 0x0F
            buf[offset] = buf[offset] or (id shl 4).toByte()
        }

        /**
         * Return the entire size, in bytes, of the extension in [buf] whose header
         * starts at [offset]
         */
        fun getEntireLengthBytes(buf: ByteArray, offset: Int): Int =
            getDataLengthBytes(buf, offset) + EXT_HEADER_SIZE_BYTES

        /**
         * Return the data size, in bytes, of the extension in [buf] whose header
         * starts at [offset].  The data field contains the amount of bytes of
         * data minus 1, so we add to get the real length.
         */
        fun getDataLengthBytes(buf: ByteArray, offset: Int): Int =
            ((buf.get(offset) and 0x0F.toByte())).toPositiveInt() + 1

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
    }
}
