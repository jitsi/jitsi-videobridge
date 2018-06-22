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

import java.nio.ByteBuffer


fun Short.isOneByteHeaderType(): Boolean = this.compareTo(RtpOneByteHeaderExtension.COOKIE) == 0
fun Short.isTwoByteHeaderType(): Boolean = this.compareTo(RtpTwoByteHeaderExtension.COOKIE) == 0


//TODO: handle one-byte header type 15:
//The local identifier value 15 is reserved for future extension and
//   MUST NOT be used as an identifier.  If the ID value 15 is
//   encountered, its length field should be ignored, processing of the
//   entire extension should terminate at that point, and only the
//   extension elements present prior to the element with ID 15
//   considered.

class RtpHeaderExtensions {
    companion object {
        // Buf position should be at the start of the extension block.  This method assumes
        // there are extensions present (i.e. the X bit was set)
        fun parse(buf: ByteBuffer): Map<Int, RtpHeaderExtension> {
            val headerExtensionType = buf.getShort()
            val headerExtensionParser = when {
                headerExtensionType.isOneByteHeaderType() -> ::RtpOneByteHeaderExtension
                headerExtensionType.isTwoByteHeaderType() -> ::RtpTwoByteHeaderExtension
                else -> TODO()
            }
            val lengthInWords = buf.getShort()
            val extensionStartPosition = buf.position()
            val extensionMap = mutableMapOf<Int, RtpHeaderExtension>()
            while (buf.position() < extensionStartPosition + (lengthInWords * 4)) {
                val ext = headerExtensionParser(buf)
                extensionMap[ext.id] = ext
            }
            return extensionMap
        }
    }
}
