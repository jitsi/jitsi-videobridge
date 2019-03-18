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

package org.jitsi.rtp.rtp

///*
// * Copyright @ 2018 - present 8x8, Inc.
// *
// * Licensed under the Apache License, Version 2.0 (the "License");
// * you may not use this file except in compliance with the License.
// * You may obtain a copy of the License at
// *
// *     http://www.apache.org/licenses/LICENSE-2.0
// *
// * Unless required by applicable law or agreed to in writing, software
// * distributed under the License is distributed on an "AS IS" BASIS,
// * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// * See the License for the specific language governing permissions and
// * limitations under the License.
// */
//
//package org.jitsi.rtp2.rtp
//
//import org.jitsi.rtp.extensions.unsigned.toPositiveInt
//import org.jitsi.rtp.extensions.bytearray.byteArrayOf
//import org.jitsi.rtp.extensions.bytearray.getShort
//import org.jitsi.rtp.extensions.bytearray.putShort
//
///**
// *  Checks if this [Short] matches the cookie used by one-byte
// *  header extensions.
// *  See https://tools.ietf.org/html/rfc5285#section-4.3
// */
//fun Short.isOneByteHeaderType(): Boolean
//        = this.compareTo(RtpHeaderExtensions.ONE_BYTE_COOKIE) == 0
///**
// *  Checks if this [Short] matches the cookie used by two-byte
// *  header extensions.
// *  See https://tools.ietf.org/html/rfc5285#section-4.3
// */
//fun Short.isTwoByteHeaderType(): Boolean
//        = RtpHeaderExtensions.TWO_BYTE_COOKIE.compareTo(this.toInt() and 0xfff0) == 0
//
//
//enum class HeaderExtensionType(val value: Int) {
//    ONE_BYTE_HEADER_EXT(1),
//    TWO_BYTE_HEADER_EXT(2);
//
//    fun toCookie(): Short {
//        return when (value) {
//            ONE_BYTE_HEADER_EXT.value -> RtpHeaderExtensions.ONE_BYTE_COOKIE
//            TWO_BYTE_HEADER_EXT.value -> RtpHeaderExtensions.TWO_BYTE_COOKIE
//            else -> throw Exception("Invalid header extension type: $value")
//        }
//    }
//
//    companion object {
////        fun fromDataLength(length: Int): HeaderExtensionType {
////            return if (length > RtpHeaderExtension.MAX_ONE_BYTE_DATA_LENGTH) {
////                TWO_BYTE_HEADER_EXT
////            } else {
////                ONE_BYTE_HEADER_EXT
////            }
////        }
//        fun fromCookie(cookie: Short): HeaderExtensionType {
//            return when {
//                cookie.isOneByteHeaderType() -> ONE_BYTE_HEADER_EXT
//                cookie.isTwoByteHeaderType() -> TWO_BYTE_HEADER_EXT
//                else -> throw Exception("Invalid header extension cookie: $cookie")
//            }
//        }
//    }
//}
//
//class RtpHeaderExtensionsHelpers {
////    inner class Iterator : kotlin.collections.Iterator<RtpHeaderExtension> {
////        private var currOffset: Int = getHeaderExtensionsDataOffset(data)
////        private var currHeaderExtension = RtpHeaderExtension(data, currOffset)
////        private var remainingLen: Int = getHeaderExtensionsLength(data)
////
////        override fun hasNext(): Boolean = remainingLen >= RtpHeaderExtension.MIN_EXTENSION_SIZE_BYTES
////
////        override fun next(): RtpHeaderExtension {
////            val extLength = currHeaderExtension.extensionLength
////            currOffset += extLength
////            remainingLen -= extLength
////
////            return currHeaderExtension
////        }
////    }
//
////    fun addExtension(ext: RtpHeaderExtension) {
////        // The payload offset (before it is moved)
////        // is where we'll add the extension
////        val currPayloadOffset = payloadOffset
////        // Move the payload to make room
////        shiftPayload(ext.extensionLength)
////    }
//
//    companion object {
//        const val ONE_BYTE_COOKIE: Short = 0xBEDE.toShort()
//        val ONE_BYTE_COOKIE_BUF: ByteArray = byteArrayOf(0xBE, 0xDE)
//        const val TWO_BYTE_COOKIE: Short = 0x1000
//        const val RTP_HEADER_EXTENSIONS_HEADER_SIZE = 4
//
//        fun getHeaderExtensionsLength(buf: ByteArray): Int {
//            val headerOffset = getHeaderOffset(buf)
//            // Add 2 to move past the cookie
//            return buf.getShort(headerOffset + 2).toPositiveInt()
//        }
//
//        /**
//         * Assumes room has already been made for the extension, but since we can't know
//         * at what offset to add it, [destOffset] tells us.
//         */
//        fun addExtension(ext: RtpHeaderExtension, destBuf: ByteArray, destOffset: Int) {
//            System.arraycopy(ext.buf, ext.offset, destBuf, destOffset, ext.extensionLength)
//        }
//
//        /**
//         * Given [buf] which contains an RTP header, return the offset
//         * at which the header extension header will start (assuming
//         * there are extensions present)
//         */
//        fun getHeaderOffset(buf: ByteArray): Int =
//            RtpHeader.FIXED_HEADER_SIZE_BYTES + 4 * RtpHeader.getCsrcCount(buf)
//
//        /**
//         * The offset at which the extension data (past the extensions header) resides
//         */
//        fun getDataOffset(buf: ByteArray): Int =
//            getHeaderOffset(buf) + RTP_HEADER_EXTENSIONS_HEADER_SIZE
//
//        /**
//         * [offset] into [buf] is the start of the extensions header
//         */
//        fun setExtensionCookie(buf: ByteArray, offset: Int) =
//            System.arraycopy(ONE_BYTE_COOKIE_BUF, 0, buf, offset, 2)
//
//        /**
//         * [offset] into [buf] is the start of the extensions header
//         */
//        fun setExtensionsLength(buf: ByteArray, offset: Int, lengthValue: Int) =
//            buf.putShort(offset + 2, lengthValue.toShort())
//
//
//        /**
//         * Given [buf] which contains an RTP header, return the type of
//         * header extension (one or two byte header) contained within it.
//         */
////        fun getHeaderExtensionsType(buf: ByteArray): HeaderExtensionType {
////            val headerExtensionCookieOffset = RtpHeader.FIXED_HEADER_SIZE_BYTES +
////                    4 * RtpHeader.getCsrcCount(buf)
////            val cookie = buf.getShort(headerExtensionCookieOffset)
////            return HeaderExtensionType.fromCookie(cookie)
////        }
//    }
//}