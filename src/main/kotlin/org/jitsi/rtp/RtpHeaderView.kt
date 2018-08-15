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

import org.jitsi.rtp.extensions.getBitAsBool
import org.jitsi.rtp.extensions.getBits
import unsigned.toUInt
import unsigned.toULong
import java.nio.ByteBuffer

internal class RtpHeaderView(var buf: ByteBuffer) {
    val version: Int by lazy {
        buf.get(0).getBits(0, 2).toUInt()
    }
    val hasPadding: Boolean by lazy {
        buf.get(0).getBitAsBool(3)
    }
    val hasExtension: Boolean by lazy {
        buf.get(0).getBitAsBool(4)
    }
    val csrcCount: Int by lazy {
        buf.get(0).getBits(4, 4).toUInt()
    }
    val marker: Boolean by lazy {
        buf.get(1).getBitAsBool(0)
    }
    val payloadType: Int by lazy {
        buf.get(1).getBits(1, 7).toUInt()
    }
    val sequenceNumber: Int by lazy {
        buf.getShort(2).toUInt()
    }
    val timestamp: Long by lazy {
        buf.getInt(4).toULong()
    }
    val ssrc: Long by lazy {
        buf.getInt(8).toULong()
    }
    val csrcs: List<Long> by lazy {
        (0..csrcCount).map {
            buf.getInt(12 + (it * 4)).toULong()
        }.toList()
    }
    val extensions: Map<Int, RtpHeaderExtension> by lazy {
        if (hasExtension) {
            buf.mark()
            buf.position(12 + (4 * csrcCount))
            val exts = RtpHeaderExtensions.parse(buf)
            buf.reset()
            exts
        } else {
            mapOf<Int, RtpHeaderExtension>()
        }
    }
}
