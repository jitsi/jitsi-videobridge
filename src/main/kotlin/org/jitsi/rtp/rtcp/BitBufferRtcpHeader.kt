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

import org.jitsi.rtp.util.BitBuffer
import unsigned.toUInt
import unsigned.toULong
import java.nio.ByteBuffer
import kotlin.properties.Delegates

internal class BitBufferRtcpHeader : RtcpHeader() {
    override var version: Int by Delegates.notNull()
    override var hasPadding: Boolean by Delegates.notNull()
    override var reportCount: Int by Delegates.notNull()
    override var payloadType: Int by Delegates.notNull()
    override var length: Int by Delegates.notNull()
    override var senderSsrc: Long by Delegates.notNull()

    companion object Create {
        fun fromBuffer(buf: ByteBuffer): RtcpHeader {
            val bitBuffer = BitBuffer(buf)
            return with (BitBufferRtcpHeader()) {
                version = bitBuffer.getBits(2).toUInt()
                hasPadding = bitBuffer.getBitAsBoolean()
                reportCount = bitBuffer.getBits(5).toUInt()
                payloadType = buf.get().toUInt()
                length = buf.getShort().toUInt()
                senderSsrc = buf.getInt().toULong()
                this
            }
        }

        fun fromValues(receiver: BitBufferRtcpHeader.() -> Unit): BitBufferRtcpHeader {
            return BitBufferRtcpHeader().apply(receiver)
        }
    }
}
