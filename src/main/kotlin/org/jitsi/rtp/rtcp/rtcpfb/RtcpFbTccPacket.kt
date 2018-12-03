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

/**
 * https://tools.ietf.org/html/draft-holmer-rmcat-transport-wide-cc-extensions-01#section-3.1
 */
class RtcpFbTccPacket : TransportLayerFbPacket {
    private var fci: Tcc
    override fun getFci(): Tcc = fci

    companion object {
        const val FMT = 15
    }

    constructor(buf: ByteBuffer) : super(buf) {
        fci = Tcc(buf.subBuffer(RtcpFbPacket.FCI_OFFSET))
    }

    constructor(
        mediaSourceSsrc: Long = 0,
        fci: Tcc = Tcc()
    ) : super(mediaSourceSsrc = mediaSourceSsrc) {
        this.fci = fci
    }

    /**
     * How many packets are currently represented by this TCC packets
     */
    fun numPackets(): Int = fci.numPackets()

    override fun clone(): Packet {
        return RtcpFbTccPacket(getBuffer().clone())
    }
}
