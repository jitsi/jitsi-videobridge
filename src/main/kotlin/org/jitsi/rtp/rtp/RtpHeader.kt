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
//import org.jitsi.rtp.extensions.getBitAsBool
//import org.jitsi.rtp.extensions.getBits
//import org.jitsi.rtp.extensions.unsigned.toPositiveInt
//import org.jitsi.rtp.extensions.unsigned.toPositiveLong
//import org.jitsi.rtp.extensions.bytearray.getInt
//import org.jitsi.rtp.extensions.bytearray.getShort
//import org.jitsi.rtp.extensions.bytearray.putBitAsBoolean
//import org.jitsi.rtp.extensions.bytearray.putBits
//import org.jitsi.rtp.extensions.bytearray.putInt
//import org.jitsi.rtp.extensions.bytearray.putShort
//
///**
// * [RtpHeader] exists only as a set of helper methods to retrieve and set fields inside of
// * a [ByteArray].
// *
// * It covers the RTP header fields until where the variability starts (the CSRCs list)
// *
// *
// * https://tools.ietf.org/html/rfc3550#section-5.1
// *  0                   1                   2                   3
// *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
// * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
// * |V=2|P|X|  CC   |M|     PT      |       sequence number         |
// * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
// * |                           timestamp                           |
// * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
// * |           synchronization source (SSRC) identifier            |
// * +=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+
// * |            contributing source (CSRC) identifiers             |
// * |                             ....                              |
// * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
// * |              ...extensions (if present)...                    |
// * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
// */
//class RtpHeader {
//    companion object {
//        const val FIXED_HEADER_SIZE_BYTES = 12
//        const val CSRCS_OFFSET = 12
//
//        fun getVersion(buf: ByteArray): Int = buf[0].getBits(0, 2).toPositiveInt()
//        fun setVersion(buf: ByteArray, version: Int) {
//            buf.putBits(0, 0, version.toByte(), 2)
//        }
//
//        fun hasPadding(buf: ByteArray): Boolean = buf.get(0).getBitAsBool(2)
//        fun setPadding(buf: ByteArray, hasPadding: Boolean) = buf.putBitAsBoolean(0, 2, hasPadding)
//
//        fun hasExtensions(buf: ByteArray): Boolean = buf.get(0).getBitAsBool(3)
//        fun setHasExtensions(buf: ByteArray, hasExtension: Boolean) =
//            buf.putBitAsBoolean(0, 3, hasExtension)
//
//        fun getCsrcCount(buf: ByteArray): Int = buf.get(0).getBits(4, 4).toPositiveInt()
//        fun setCsrcCount(buf: ByteArray, csrcCount: Int) {
//            buf.putBits(0, 4, csrcCount.toByte(), 4)
//        }
//
//        fun getMarker(buf: ByteArray): Boolean = buf.get(1).getBitAsBool(0)
//        fun setMarker(buf: ByteArray, isSet: Boolean) {
//            buf.putBitAsBoolean(1, 0, isSet)
//        }
//
//        fun getPayloadType(buf: ByteArray): Int = buf.get(1).getBits(1, 7).toPositiveInt()
//        fun setPayloadType(buf: ByteArray, payloadType: Int) {
//            buf.putBits(1, 1, payloadType.toByte(), 7)
//        }
//
//        fun getSequenceNumber(buf: ByteArray): Int = buf.getShort(2).toPositiveInt()
//        fun setSequenceNumber(buf: ByteArray, sequenceNumber: Int) {
//            buf.putShort(2, sequenceNumber.toShort())
//        }
//
//        fun getTimestamp(buf: ByteArray): Long = buf.getInt(4).toPositiveLong()
//        fun setTimestamp(buf: ByteArray, timestamp: Long) {
//            buf.putInt(4, timestamp.toInt())
//        }
//
//        fun getSsrc(buf: ByteArray): Long = buf.getInt(8).toPositiveLong()
//        fun setSsrc(buf: ByteArray, ssrc: Long) {
//            buf.putInt(8, ssrc.toInt())
//        }
//
//        fun getCsrcs(buf: ByteArray): List<Long> {
//            val numCsrcs = getCsrcCount(buf)
//            return (1..numCsrcs).map { buf.getInt(CSRCS_OFFSET + (4 * it)).toPositiveLong() }.toList()
//        }
//
//        /**
//         * Assumes there is already proper room for the CSRCS.  Also updates
//         * the CSRC count field.
//         */
//        fun setCsrcs(buf: ByteArray, csrcs: List<Long>) {
//            csrcs.forEachIndexed { index, csrc ->
//                buf.putInt(CSRCS_OFFSET + (4 * index), csrc.toInt())
//            }
//            setCsrcCount(buf, csrcs.size)
//        }
//
//        /**
//         * Given a buffer which contains the RTP header, return
//         * the length of the entire header (including header extensions)
//         */
//        fun getHeaderLength(buf: ByteArray): Int {
//            var headerLength = FIXED_HEADER_SIZE_BYTES + 4 * getCsrcCount(buf)
//
//            headerLength += if (hasExtensions(buf)) {
//                RtpHeaderExtensions.getHeaderExtensionsLength(buf)
//            } else {
//                0
//            }
//
//            return headerLength
//        }
//    }
//}