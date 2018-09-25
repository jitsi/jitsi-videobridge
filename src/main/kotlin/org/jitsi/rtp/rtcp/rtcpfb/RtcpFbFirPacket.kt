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
 * https://tools.ietf.org/html/rfc5104#section-4.3.1.1
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                              SSRC                             |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * | Seq nr.       |    Reserved                                   |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *
 * The SSRC field in the FIR FCI block is used to set the media sender
 * SSRC, the media source SSRC field in the RTCPFB header is unsed for FIR packets.
 */
class RtcpFbFirPacket : PayloadSpecificFbPacket {
    private var fci: Fir
    override fun getFci(): Fir = fci

    companion object {
        const val FMT = 4
    }

    constructor(buf: ByteBuffer) : super(buf) {
        fci = Fir(buf.subBuffer(RtcpFbPacket.FCI_OFFSET))
    }

    constructor(
        mediaSourceSsrc: Long = 0,
        seqNum: Int = 0
    // The media source ssrc in the feedback header for FIR is unused and should be 0
    ) : super(mediaSourceSsrc = 0) {
        fci = Fir(mediaSourceSsrc, seqNum)
    }

    override fun clone(): Packet {
        return RtcpFbFirPacket(getBuffer().clone())
    }
}
