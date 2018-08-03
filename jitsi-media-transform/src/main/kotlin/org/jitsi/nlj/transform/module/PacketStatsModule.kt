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
package org.jitsi.nlj.transform.module

import org.jitsi.nlj.util.appendLnIndent
import org.jitsi.rtp.Packet

class PacketStatsModule : Module("RX Packet stats") {
    var totalBytesRx = 0
    override fun doProcessPackets(p: List<Packet>) {
        p.forEach { pkt -> totalBytesRx += pkt.size }
        next(p)
    }

    override fun getStats(indent: Int): String {
        return with (StringBuffer()) {
            append(super.getStats(indent))
            appendLnIndent(indent + 2, "total bytes rx: $totalBytesRx")
            toString()
        }
    }
}
