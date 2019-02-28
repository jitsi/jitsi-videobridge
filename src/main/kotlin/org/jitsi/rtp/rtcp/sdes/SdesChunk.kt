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

package org.jitsi.rtp.rtcp.sdes

import org.jitsi.rtp.extensions.unsigned.toPositiveLong
import org.jitsi.rtp.Serializable
import java.nio.ByteBuffer
import java.util.Objects

/**
 *        0                   1                   2                   3
 *        0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 *        +=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+
 * chunk  |                          SSRC/CSRC_1                          |
 *   1    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *        |                           SDES items                          |
 *        |                              ...                              |
 *        +=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+
 */
class SdesChunk(
    val ssrc: Long = -1,
    val sdesItems: List<SdesItem> = listOf()
) : Serializable() {

    private val dataSize: Int = 4 + sdesItems.map(SdesItem::sizeBytes).sum() + EmptySdesItem.sizeBytes

    private val paddingSize: Int
        get() {
            var paddingSize = 0
            while ((dataSize + paddingSize) % 4 != 0) {
                paddingSize++
            }
            return paddingSize
        }

    override val sizeBytes: Int
        get() = dataSize + paddingSize

    override fun serializeTo(buf: ByteBuffer) {
        buf.putInt(ssrc.toInt())
        sdesItems.forEach {
            buf.put(it.getBuffer())
        }
        buf.put(EmptySdesItem.getBuffer())
        while (buf.position() % 4 != 0) {
            buf.put(0x00)
        }
    }

    override fun toString(): String {
        return with (StringBuffer()) {
            appendln("SDES chunk $ssrc")
            sdesItems.forEach { appendln(it.toString()) }

            toString()
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other?.javaClass != javaClass) {
            return false
        }
        other as SdesChunk
        return (ssrc == other.ssrc &&
                sdesItems.size == other.sdesItems.size &&
                sdesItems.containsAll(other.sdesItems))
    }

    override fun hashCode(): Int = Objects.hash(ssrc, sdesItems)

    companion object {
        fun fromBuffer(buf: ByteBuffer): SdesChunk {
            val ssrc = buf.getInt().toPositiveLong()

            val sdesItems = mutableListOf<SdesItem>()
            var item = SdesItem.fromBuffer(buf)
            while (item != EmptySdesItem) {
                sdesItems.add(item)
                item = SdesItem.fromBuffer(buf)
            }
            // Consume any padding
            while (buf.position() % 4 != 0) {
                buf.get()
            }
            return SdesChunk(ssrc, sdesItems)
        }
    }
}
