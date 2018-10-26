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
 * https://tools.ietf.org/html/rfc4585#section-6.2.1
 */
class RtcpFbNackPacket : TransportLayerFbPacket {
    private var fci: NackFci
    val missingSeqNums
        get() = fci.missingSeqNums

    override fun getFci(): NackFci = fci

    companion object {
        const val FMT = 1
    }

    constructor(buf: ByteBuffer) : super(buf) {
        fci = NackFci(buf.subBuffer(RtcpFbPacket.FCI_OFFSET))
    }

    constructor(
        mediaSourceSsrc: Long = 0,
        missingSeqNums: List<Int> = listOf()
    ) : super(mediaSourceSsrc = mediaSourceSsrc) {
        fci = NackFci(missingSeqNums)
    }

    override fun clone(): Packet {
        return RtcpFbNackPacket(getBuffer().clone())
    }
}
