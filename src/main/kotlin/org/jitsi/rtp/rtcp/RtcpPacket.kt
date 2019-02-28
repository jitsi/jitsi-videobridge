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

package org.jitsi.rtp.rtcp

import org.jitsi.rtp.extensions.clone
import org.jitsi.rtp.extensions.subBuffer
import org.jitsi.rtp.Packet
import org.jitsi.rtp.rtcp.rtcpfb.RtcpFbPacket
import org.jitsi.rtp.rtcp.sdes.RtcpSdesPacket
import org.jitsi.rtp.util.ByteBufferUtils
import java.nio.ByteBuffer

/**
 * When performing crypto-related operations (authentication,
 * encryption/decryption) we need to be able to operate on
 * both the packet's entire data or its payload as a single
 * buffer (and, in the case of encryption/decryption, be able
 * to modify it).  These fields (particularly a modifiable payload)
 * are not exposed in RTCP packets (as we model the
 * individual fields of each RTCP packet type) so this class is
 * designed to be used in those cases.  It can be obtained by
 * calling [RtcpPacket#prepareForCrypto].
 */
class RtcpPacketForCrypto(
    header: RtcpHeader = RtcpHeader(),
    private val payload: ByteBuffer = ByteBufferUtils.EMPTY_BUFFER,
    backingBuffer: ByteBuffer? = null
) : RtcpPacket(header, backingBuffer) {

    fun getPayload(): ByteBuffer {
        // We assume that if the payload is retrieved that it's being modified
        payloadModified()
        return payload.duplicate()
    }

    override val sizeBytes: Int
        get() = header.sizeBytes + payload.limit()

    override fun shouldUpdateHeaderAndAddPadding(): Boolean = false

    override fun clone(): Packet {
        return RtcpPacketForCrypto(header.clone(), payload.clone())
    }

    override fun serializeTo(buf: ByteBuffer) {
        super.serializeTo(buf)
        payload.rewind()
        buf.put(payload)
    }
}

abstract class RtcpPacket(
    val header: RtcpHeader,
    private var backingBuffer: ByteBuffer?
) : Packet() {
    private var dirty: Boolean = true

    /**
     * How many padding bytes are needed, if any
     * TODO: should sizeBytes be exposed publicly?  because it doesn't
     * include padding it could be misleading
     */
    private val numPaddingBytes: Int
        get() {
            //TODO: maybe we can only update this when dirty = true
            var paddingBytes = 0
            while ((sizeBytes + paddingBytes) % 4 != 0) {
                paddingBytes++
            }
            return paddingBytes
        }

    /**
     * Acquire an [RtcpPacketForCrypto] version of this RTCP packet which can be
     * operated on more easily for crypto-related operations (authentication,
     * encryption/decryption).  Note that this method does not return a cloned
     * version, so the [RtcpPacket] instance it's being called on should be
     * considered 'invalidated' after this call (and should no longer be
     * used).
     */
    fun prepareForCrypto(): RtcpPacketForCrypto {
        return RtcpPacketForCrypto(header, getBuffer().subBuffer(header.sizeBytes), backingBuffer)
    }

    /**
     * [sizeBytes] MUST including padding (i.e. it should be 32-bit word aligned)
     */
    private fun calculateLengthFieldValue(sizeBytes: Int): Int {
        if (sizeBytes % 4 != 0) {
            throw Exception("Invalid RTCP size value")
        }
        return (sizeBytes / 4) - 1
    }

    private fun updateHeaderFields() {
        header.hasPadding = numPaddingBytes > 0
        header.length = calculateLengthFieldValue(this@RtcpPacket.sizeBytes + numPaddingBytes)
    }

    protected fun payloadModified() {
        //TODO: do we want to call updateHeaderFields here?
        dirty = true
    }

    @Suppress("UNCHECKED_CAST")
    fun <OtherType : RtcpPacket>toOtherRtcpPacketType(factory: (RtcpHeader, backingBuffer: ByteBuffer?) -> RtcpPacket): OtherType
        = factory(header, backingBuffer) as OtherType

    //NOTE: This method should almost NEVER be overridden by subclasses.  The exceptions
    // are the SRTCP-related classes, whose header values will be inconsistent with the data due to
    // the auth tag and SRTCP index (and it should not be padded)
    protected open fun shouldUpdateHeaderAndAddPadding(): Boolean = true


    final override fun getBuffer(): ByteBuffer {
        if (dirty || header.dirty) {
            if (shouldUpdateHeaderAndAddPadding()) {
                updateHeaderFields()
            }
            val neededSize = if (shouldUpdateHeaderAndAddPadding()) sizeBytes + numPaddingBytes else sizeBytes
            val b = ByteBufferUtils.ensureCapacity(backingBuffer, neededSize)
            serializeTo(b)
            b.rewind()

            backingBuffer = b
            dirty = false
        }
        return backingBuffer!!
    }

    override fun serializeTo(buf: ByteBuffer) {
        header.serializeTo(buf)
    }

    companion object {
        fun parse(buf: ByteBuffer): RtcpPacket {
            val bufStartPosition = buf.position()
            val packetType = RtcpHeader.getPacketType(buf)
            val packetLengthBytes = (RtcpHeader.getLength(buf) + 1) * 4
            val packet = when (packetType) {
                RtcpSrPacket.PT -> RtcpSrPacket.fromBuffer(buf)
                RtcpRrPacket.PT -> RtcpRrPacket.fromBuffer(buf)
                RtcpSdesPacket.PT -> RtcpSdesPacket.fromBuffer(buf)
                RtcpByePacket.PT -> RtcpByePacket.create(buf)
                in RtcpFbPacket.PACKET_TYPES -> RtcpFbPacket.fromBuffer(buf)
                else -> throw Exception("Unsupported RTCP packet type $packetType")
            }
            if (buf.position() != bufStartPosition + packetLengthBytes) {
                throw Exception("Didn't parse until the end of the RTCP packet!")
            }
            return packet
        }
        fun addPadding(buf: ByteBuffer) {
            while (buf.position() % 4 != 0) {
                buf.put(0x00)
            }
        }

        fun consumePadding(buf: ByteBuffer) {
            while (buf.position() % 4 != 0) {
                buf.put(0x00)
            }
        }
    }
}
