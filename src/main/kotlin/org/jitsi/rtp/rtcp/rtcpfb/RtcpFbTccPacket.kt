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
package org.jitsi.rtp.rtcp.rtcpfb

import org.jitsi.rtp.Packet
import org.jitsi.rtp.extensions.clone
import org.jitsi.rtp.extensions.subBuffer
import java.nio.ByteBuffer

class RtcpFbTccPacket : TransportLayerFbPacket {
    override var feedbackControlInformation: FeedbackControlInformation

    companion object {
        const val FMT = 15
    }

    constructor(buf: ByteBuffer) : super(buf) {
        feedbackControlInformation = Tcc(buf.subBuffer(RtcpFbPacket.FCI_OFFSET))
    }

    constructor(
        mediaSourceSsrc: Long = 0,
        referenceTime: Long = -1,
        feedbackPacketCount: Int = -1,
        packetInfo: PacketMap = PacketMap()
    ) : super(mediaSourceSsrc = mediaSourceSsrc) {
        feedbackControlInformation = Tcc(referenceTime, feedbackPacketCount, packetInfo)
    }

    override fun clone(): Packet {
        return RtcpFbTccPacket(getBuffer().clone())
    }
}
