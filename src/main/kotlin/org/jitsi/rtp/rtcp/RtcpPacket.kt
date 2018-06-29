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
    abstract var header: RtcpHeader
    companion object {
        fun fromBuffer(buf: ByteBuffer): RtcpPacket {
            val header = RtcpHeader.fromBuffer(buf)
            return when (header.payloadType) {
                200 -> RtcpSrPacket.fromBuffer(header, buf)
                201 -> RtcpRrPacket.fromBuffer(header, buf)
                else -> TODO()
            }
        }
    }
    abstract fun serializeToBuffer(buf: ByteBuffer)
}
