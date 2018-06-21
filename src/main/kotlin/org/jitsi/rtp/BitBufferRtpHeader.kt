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

class BitBufferRtpHeader(buf: ByteBuffer) : RtpHeader() {
    private val bitBuffer = BitBuffer(buf)
    override val version = bitBuffer.getBits(2).toInt()
    override val hasPadding = bitBuffer.getBitAsBoolean()
    override val hasExtension = bitBuffer.getBitAsBoolean()
    override val csrcCount = bitBuffer.getBits(4).toInt()
    override val marker = bitBuffer.getBitAsBoolean()
    override val payloadType = bitBuffer.getBits(7).toInt()
    override val sequenceNumber = buf.getShort().toInt()
    override val timestamp = buf.getInt().toLong()
    override val ssrc = buf.getInt().toLong()
    override val csrcs: List<Long>

    init {
        csrcs = (0 until csrcCount).map {
            buf.getInt().toLong()
        }
    }
}
