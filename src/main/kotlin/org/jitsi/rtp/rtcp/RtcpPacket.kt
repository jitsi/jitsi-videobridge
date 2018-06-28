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

import java.nio.ByteBuffer

abstract class RtcpPacket {
    abstract val header: RtcpHeader
    companion object {
        fun parse(buf: ByteBuffer): RtcpPacket {
            val header = RtcpHeader.create(buf)
            return when (header.payloadType) {
                200 -> RtcpSrPacket(header, buf)
                201 -> RtcpRrPacket(header, buf)
                else -> TODO()
            }
        }
    }
}
