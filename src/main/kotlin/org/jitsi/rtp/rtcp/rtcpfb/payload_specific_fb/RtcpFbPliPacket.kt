/*
 * Copyright @ 2018 - present 8x8, Inc.
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

package org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb

import org.jitsi.rtp.extensions.bytearray.cloneFromPool

/**
 * https://tools.ietf.org/html/rfc4585#section-6.3.1
 * PLI does not require parameters.  Therefore, the length field MUST be
 *  2, and there MUST NOT be any Feedback Control Information.
 */
class RtcpFbPliPacket(
    buffer: ByteArray,
    offset: Int,
    length: Int
) : PayloadSpecificRtcpFbPacket(buffer, offset, length) {

    override fun clone(): RtcpFbPliPacket {
        return RtcpFbPliPacket(buffer.cloneFromPool(), offset, length)
    }

    companion object {
        const val FMT = 1
    }
}