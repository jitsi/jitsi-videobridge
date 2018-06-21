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


/**
 * Buf is a buffer which starts at the beginning of the RTCP header
 * https://tools.ietf.org/html/rfc3550#section-6.4.2
 */
class RtcpRrPacket(buf: ByteBuffer) {
    val header = RtcpHeader(buf)
    val reportBlocks = mutableListOf<ReportBlock>()

    init {
        var blockStartPositionBytes = 28
        for (i in 0 until header.reportCount) {
            val reportBlockBuf = (buf.position(blockStartPositionBytes) as ByteBuffer).slice()
            reportBlocks.add(ReportBlock(reportBlockBuf))
            blockStartPositionBytes += ReportBlock.SIZE_BYTES
        }
    }

}
