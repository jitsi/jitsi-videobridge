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
//import org.jitsi.rtp.extensions.getBits
//import org.jitsi.rtp.extensions.unsigned.toPositiveInt
//
////TODO: support 2 byte extensions
//class RtpHeaderExtension(
//    var buf: ByteArray,
//    /**
//     * The offset into [buf] at which this header extension starts
//     */
//    var offset: Int
//) {
//    fun updateOffsetLength(offset: Int, length: Int) {
//        this.offset = offset
//    }
//
//    val id: Int get() = getId(buf, offset)
//
//    val extensionLength: Int get() = dataLength + 1
//
//    val dataLength: Int get() = getLength(buf, offset)
//
//    /**
//     * Returns the offset into [buf] at which the data held by the extension resides
//     */
//    fun getDataOffset(): Int = offset + 1
//
//    companion object {
//        const val MIN_EXTENSION_SIZE_BYTES = 2
//
//        fun getId(buf: ByteArray, offset: Int): Int =
//            buf.get(offset).getBits(0, 4).toPositiveInt()
//
//        fun getLength(buf: ByteArray, offset: Int): Int =
//            buf.get(offset).getBits(4, 4).toPositiveInt() + 1
//    }
//}