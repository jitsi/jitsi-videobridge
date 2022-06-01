/*
 * Copyright @ 2019 - Present, 8x8 Inc
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

package org.jitsi.nlj.rtcp

import org.jitsi.nlj.transform.node.PacketParser
import org.jitsi.rtp.rtcp.CompoundRtcpPacket
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.utils.logging2.Logger

class CompoundRtcpParser(parentLogger: Logger) : PacketParser(
    "Compound RTCP parser", parentLogger,
    {
        CompoundRtcpPacket(it.buffer, it.offset, it.length).also { compoundPacket ->
            // Force packets to be evaluated to trigger any parsing errors
            compoundPacket.packets
        }
    }
) {
    override fun trace(f: () -> Unit) = f.invoke()
}

class SingleRtcpParser(parentLogger: Logger) : PacketParser(
    "Single RTCP parser", parentLogger,
    {
        RtcpPacket.parse(it.buffer, it.offset, it.length)
    }
) {
    override fun trace(f: () -> Unit) = f.invoke()
}
