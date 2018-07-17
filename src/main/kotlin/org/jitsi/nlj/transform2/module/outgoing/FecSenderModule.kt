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
package org.jitsi.nlj.transform2.module.outgoing

import org.jitsi.nlj.transform2.module.Module
import org.jitsi.rtp.Packet
import org.jitsi.rtp.RtpHeader
import org.jitsi.rtp.RtpPacket

class FecSenderModule : Module("FEC sender") {
    private var packetCount = 0
    override fun doProcessPackets(p: List<Packet>) {
        packetCount += p.size
        if (false && packetCount % 5 == 0) {
            // Generate a FEC packet
            val packet = RtpPacket.fromValues {
                header = RtpHeader.fromValues {
                    payloadType = 92
                    sequenceNumber = packetCount
                }
            }
            next(p + packet)
        } else {
            next(p)
        }

    }
}
