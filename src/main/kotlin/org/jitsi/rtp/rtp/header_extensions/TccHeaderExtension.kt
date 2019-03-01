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
package org.jitsi.rtp.rtp.header_extensions

import org.jitsi.rtp.extensions.incrementPosition
import org.jitsi.rtp.extensions.subBuffer
import org.jitsi.rtp.extensions.unsigned.toPositiveInt
import java.nio.ByteBuffer

/**
 * https://tools.ietf.org/html/draft-holmer-rmcat-transport-wide-cc-extensions-01#section-2.2
 */
class TccHeaderExtension(
    id: Int,
    val tccSeqNum: Int
) : RtpOneByteHeaderExtension(id) {

    override val sizeBytes: Int
        get() = HEADER_SIZE + 2

    override fun serializeTo(buf: ByteBuffer) {
        val absBuf = buf.subBuffer(buf.position())
        setId(absBuf, id)
        setLength(absBuf, 2)
        absBuf.incrementPosition(1)
        absBuf.putShort(tccSeqNum.toShort())
    }

    companion object {
        fun fromUnknown(id: Int, data: ByteBuffer): TccHeaderExtension {
            return TccHeaderExtension(id, data.getShort().toPositiveInt())
        }
    }
}
