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
import kotlin.reflect.KProperty

//TODO: is this even necessary? doing it this way, what difference is there between
// this scheme and just initializing (normal) variables with the buffer value and
// then changing them to a manually set value later?
// it will have a value for the payload, at least (which we wouldn't parse and copy
// values from, just refer to)
class CopyOnWriteDelegate<T>(private val bufferValue: T) {
    private var overriddenValue: T? = null
    operator fun getValue(thisRef: Any?, property: KProperty<*>): T {
        return overriddenValue ?: bufferValue
    }

    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        overriddenValue = value
    }
}

class BitBufferRtpHeader(buf: ByteBuffer) : RtpHeader() {
    private val bitBuffer = BitBuffer(buf)
    override var version: Int by CopyOnWriteDelegate(bitBuffer.getBits(2).toInt())
    override var hasPadding: Boolean by CopyOnWriteDelegate(bitBuffer.getBitAsBoolean())
    override var hasExtension: Boolean by CopyOnWriteDelegate(bitBuffer.getBitAsBoolean())
    override var csrcCount: Int by CopyOnWriteDelegate(bitBuffer.getBits(4).toInt())
    override var marker: Boolean by CopyOnWriteDelegate(bitBuffer.getBitAsBoolean())
    override var payloadType: Int by CopyOnWriteDelegate(bitBuffer.getBits(7).toInt())
    override var sequenceNumber: Int by CopyOnWriteDelegate(buf.getShort().toInt())
    override var timestamp: Long by CopyOnWriteDelegate(buf.getInt().toLong())
    override var ssrc: Long by CopyOnWriteDelegate(buf.getInt().toLong())
    override var csrcs: List<Long> by CopyOnWriteDelegate(listOf())
    override var extensions: Map<Int, RtpHeaderExtension> by CopyOnWriteDelegate(mapOf())

    init {
        csrcs = (0 until csrcCount).map {
            buf.getInt().toLong()
        }
        extensions = if (hasExtension) RtpHeaderExtensions.parse(buf) else mapOf()
    }
}
