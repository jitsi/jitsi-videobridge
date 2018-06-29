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
    // We don't know how this will be initialized (from a buffer or from
    // created values) and in order to make construction from values convenient
    // (where they can be set progressively rather than requiring them all
    // at once) we can't enforce values be passed for all fields at creation.
    // However, setting default values is sometimes awkward and can lead
    // to issues (if someone forgot to set a field) so use Delegates.notNull
    // to delay instantiation but cause an error if an unset field is accessed.
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
    }
}
