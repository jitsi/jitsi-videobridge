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

package org.jitsi.rtp.rtcp.rtcpfb.fci.tcc

import org.jitsi.rtp.Serializable
import java.nio.ByteBuffer

abstract class ReceiveDelta : Serializable() {
    abstract var deltaMs: Double //TODO: should we be able to hold this as a long? don't think a double makes sense?

    final override fun getBuffer(): ByteBuffer {
        val b = ByteBuffer.allocate(sizeBytes)
        serializeTo(b)

        return b.rewind() as ByteBuffer
    }

    companion object {
        fun parse(buf: ByteBuffer, deltaSizeBytes: Int): ReceiveDelta {
            return when (deltaSizeBytes) {
                EightBitReceiveDelta.SIZE_BYTES -> EightBitReceiveDelta(buf)
                SixteenBitReceiveDelta.SIZE_BYTES -> SixteenBitReceiveDelta(buf)
                else -> throw Exception("Unsupported receive delta size: $deltaSizeBytes bytes")
            }
        }
        fun create(delta: Double): ReceiveDelta {
            return when (delta) {
                in 0.0..63.75 -> EightBitReceiveDelta(delta)
                in -8192.0..8191.75 -> SixteenBitReceiveDelta(delta)
                else -> throw Exception("Unsupported delta value: $delta")
            }
        }
    }

    override fun toString(): String {
        return "delta: ${deltaMs}ms"
    }
}

/**
 * https://tools.ietf.org/html/draft-holmer-rmcat-transport-wide-cc-extensions-01#section-3.1.5
 * If the "Packet received, small delta" symbol has been appended to
 * the status list, an 8-bit unsigned receive delta will be appended
 * to recv delta list, representing a delta in the range [0, 63.75]
 * ms.
 */
class EightBitReceiveDelta : ReceiveDelta {
    override var deltaMs: Double

    override val sizeBytes: Int = SIZE_BYTES

    companion object {
        const val SIZE_BYTES = 1

        /**
         * The value written in the field is represented as multiples of 250us
         */
        private fun getDeltaMs(buf: ByteBuffer): Double {
            val uSecMultiple = buf.get().toInt()
            val uSecs = uSecMultiple * 250.0
            return uSecs / 1000.0
        }
        private fun putDeltaMs(buf: ByteBuffer, deltaMs: Double) {
            val uSecs = deltaMs * 1000.0
            val uSecMultiple = uSecs / 250.0
            buf.put(uSecMultiple.toByte())
        }
    }

    constructor(buf: ByteBuffer) {
        this.deltaMs = getDeltaMs(buf)
    }

    constructor(delta: Double = 0.0) {
        this.deltaMs = delta
    }

    override fun serializeTo(buf: ByteBuffer) {
        putDeltaMs(buf, deltaMs)
    }
}

/**
 * If the "Packet received, large or negative delta" symbol has been
 * appended to the status list, a 16-bit signed receive delta will be
 * appended to recv delta list, representing a delta in the range
 * [-8192.0, 8191.75] ms.
 */
class SixteenBitReceiveDelta : ReceiveDelta {
    override var deltaMs: Double

    override val sizeBytes: Int = SIZE_BYTES

    companion object {
        const val SIZE_BYTES = 2

        /**
         * The value written in the field is represented as multiples of 250us
         */
        fun getDeltaMs(buf: ByteBuffer): Double {
            val uSecMultiple = buf.short.toInt()
            val uSecs = uSecMultiple * 250.0
            return uSecs / 1000.0
        }
        fun setDeltaMs(buf: ByteBuffer, deltaMs: Double) {
            val uSecs = deltaMs * 1000.0
            val uSecMultiple = uSecs / 250.0
            buf.putShort(uSecMultiple.toShort())
        }
    }

    constructor(buf: ByteBuffer) {
        this.deltaMs = getDeltaMs(buf)
    }

    constructor(delta: Double = 0.0) {
        this.deltaMs = delta
    }

    override fun serializeTo(buf: ByteBuffer) {
        setDeltaMs(buf, deltaMs)
    }
}
