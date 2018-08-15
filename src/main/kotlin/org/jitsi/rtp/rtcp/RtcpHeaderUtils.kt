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
package org.jitsi.rtp.rtcp

import org.jitsi.rtp.extensions.getBitAsBool
import org.jitsi.rtp.extensions.getBits
import org.jitsi.rtp.extensions.putBitAsBoolean
import org.jitsi.rtp.extensions.putBits
import toUInt
import unsigned.toUInt
import unsigned.toULong
import unsigned.toUShort
import java.nio.ByteBuffer

class RtcpHeaderUtils {
    companion object {
        fun getVersion(buf: ByteBuffer): Int = buf.get(0).getBits(0, 2).toUInt()
        fun setVersion(buf: ByteBuffer, version: Int) = buf.putBits(0, 0, version.toByte(), 2)

        fun hasPadding(buf: ByteBuffer): Boolean = buf.get(0).getBitAsBool(2)
        fun setPadding(buf: ByteBuffer, hasPadding: Boolean) = buf.putBitAsBoolean(0, 3, hasPadding)

        fun getReportCount(buf: ByteBuffer): Int = buf.get(0).getBits(3, 5).toUInt()
        fun setReportCount(buf: ByteBuffer, reportCount: Int) = buf.putBits(0, 3, reportCount.toByte(), 5)

        fun getPayloadType(buf: ByteBuffer): Int = buf.get(1).toUInt()
        fun setPayloadType(buf: ByteBuffer, payloadType: Int) {
            buf.put(1, payloadType.toByte())
        }

        fun getLength(buf: ByteBuffer): Int = buf.getShort(2).toUInt()
        fun setLength(buf: ByteBuffer, length: Int) {
            buf.putShort(2, length.toUShort())
        }

        fun getSenderSsrc(buf: ByteBuffer): Long = buf.getInt(3).toULong()
        fun setSenderSsrc(buf: ByteBuffer, senderSsrc: Long) {
            buf.putInt(3, senderSsrc.toUInt())
        }
    }
}

