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
package org.jitsi.nlj.util

import org.jitsi.rtp.Packet
import org.jitsi.service.neomedia.RawPacket
import java.nio.ByteBuffer

fun Packet.toRawPacket(): RawPacket {
    val packetBuf = getBuffer()
    return RawPacket(packetBuf.array(), packetBuf.arrayOffset(), packetBuf.limit())
}

/**
 * Wrap this [RawPacket]'s buffer in a ByteBuffer such that:
 * 1) The ByteBuffer's position 0 will be at the offset position from RawPacket
 * 2) The ByteBuffer's limit will be the RawPacket's length
 */
fun RawPacket.getByteBuffer(): ByteBuffer {
    return ByteBuffer.wrap(buffer, offset, length).slice()
}
