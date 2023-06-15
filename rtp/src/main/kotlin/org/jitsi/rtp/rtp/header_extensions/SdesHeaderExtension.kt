/*
 * Copyright @ 2021 - present 8x8, Inc.
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

import org.jitsi.rtp.rtp.RtpPacket
import java.nio.charset.StandardCharsets

/**
 * https://datatracker.ietf.org/doc/html/rfc7941#section-4.1.1
 * Note: this is only the One-Byte Format, because we don't support Two-Byte yet.
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |  ID   |  len  | SDES item text value ...                      |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 */
class SdesHeaderExtension {
    companion object {
        fun getTextValue(ext: RtpPacket.HeaderExtension): String =
            getTextValue(ext.buffer, ext.dataOffset, ext.dataLengthBytes)
        fun setTextValue(ext: RtpPacket.HeaderExtension, sdesValue: String) {
            assert(ext.dataLengthBytes == sdesValue.length) { "buffer size doesn't match SDES value length" }
            setTextValue(ext.buffer, ext.dataOffset, sdesValue)
        }

        private fun getTextValue(buf: ByteArray, offset: Int, dataLength: Int): String {
            /* RFC 7941 says the value in RTP is UTF-8. But we use this for MID and RID values
             * which are define for SDP in RFC 5888 and RFC 4566 as ASCII only. Thus we don't
             * support UTF-8 to keep things simpler. */
            return String(buf, offset, dataLength, StandardCharsets.US_ASCII)
        }

        private fun setTextValue(buf: ByteArray, offset: Int, sdesValue: String) {
            System.arraycopy(
                sdesValue.toByteArray(StandardCharsets.US_ASCII),
                0,
                buf,
                offset,
                sdesValue.length
            )
        }
    }
}
