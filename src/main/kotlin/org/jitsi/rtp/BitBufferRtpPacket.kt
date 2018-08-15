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

import org.jitsi.rtp.util.BufferView
import java.nio.ByteBuffer
import kotlin.properties.Delegates


internal class BitBufferRtpPacket : RtpPacket() {
    override var buf: ByteBuffer by Delegates.notNull()
    override var header: RtpHeader by Delegates.notNull()
    override var payload: BufferView by Delegates.notNull()

    companion object {
        /**
         * [buf] should be a ByteBuffer whose position
         * starts at the beginning of the RTP packet and whose
         * limit is the end of the RTP packet.
         */
        fun fromBuffer(buf: ByteBuffer): RtpPacket {
            return BitBufferRtpPacket().apply {
                this.buf = buf.slice()
                header = RtpHeader.fromBuffer(buf)
                payload = BufferView(
                    buf.array(),
                    // Since we've just parsed the header, the current position should now
                    // be at the start of the payload
                    buf.position(),
                    buf.limit() - buf.position())
            }
        }
        fun fromValues(receiver: BitBufferRtpPacket.() -> Unit) = BitBufferRtpPacket().apply(receiver)
    }
}
