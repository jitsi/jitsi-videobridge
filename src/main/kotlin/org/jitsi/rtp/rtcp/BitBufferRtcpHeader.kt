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
import java.nio.ByteBuffer

fun Byte.toUInt(): Int {
    return this.toInt() and 0xFF
}

internal class BitBufferRtcpHeader(buf: ByteBuffer) : RtcpHeader() {
    private val bitBuffer = BitBuffer(buf)
    override var version = bitBuffer.getBits(2).toInt()
    override var hasPadding = bitBuffer.getBitAsBoolean()
    override var reportCount = bitBuffer.getBits(5).toInt()
    override var payloadType = buf.get().toUInt()
    override var length = buf.getShort().toInt()
    override var senderSsrc = buf.getInt().toLong()
}
