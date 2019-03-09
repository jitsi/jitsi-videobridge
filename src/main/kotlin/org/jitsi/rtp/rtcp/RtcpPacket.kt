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

import org.jitsi.rtp.Packet
import org.jitsi.rtp.extensions.clone
import org.jitsi.rtp.extensions.subBuffer
import org.jitsi.rtp.rtcp.rtcpfb.RtcpFbPacket
import org.jitsi.rtp.rtcp.sdes.RtcpSdesPacket
import org.jitsi.rtp.util.BufferPool
import org.jitsi.rtp.util.RtpUtils
import java.nio.ByteBuffer

abstract class RtcpPacket(
    header: RtcpHeader = RtcpHeader(),
    private var backingBuffer: ByteBuffer = BufferPool.getBuffer(1500)
) : Packet() {
    /**
     * Denotes whether or not any data which is contained in the
     * payload has changed.  Subclasses can call [payloadDataModified]
     * to notify this parent class that their payload fields have changed
     */
    private var payloadDirty = true
    /**
     * Denotes whether or not any fields in the header have been modified
     * so that we know we need to reserialize it
     */
    private var headerDirty = true
    /**
     * The size of the data portion (everything after the header) for
     * this packet.  This value MUST include any needed padding.
     */
    protected abstract val payloadDataSize: Int

    /**
     * We hide the header as "_header" such that we can expose the
     * "header" to callers and set [headerDirty] to true (in case
     * they've modified the field).  Note that uses within this
     * class itself which only access (not modify) values in
     * header use the [_header] field directly.
     */
    private val _header: RtcpHeader = header
    val header: RtcpHeader
        get() {
            // We can't (currently) _know_ whether or not a header
            // field was changed here, but to be safe for now we
            // assume that something has changed (since it may
            // have been.  In the future we can look at being
            // smarter here. (This is an improvement over the
            // old scheme of using the header.dirty field, which
            // we could read but not write, so we couldn't reset
            // it)
            headerDirty = true
            return _header
        }

    /**
     * The size of this RTCP packet, including any needed padding.
     */
    final override val sizeBytes: Int
        get() = _header.sizeBytes + payloadDataSize

    /**
     * Whether or not the sub-type's payload needs padding.  The subtypes
     * themselves are responsible for adding the padding in
     * [serializePayloadDataInto].  It is open, but should almost never be
     * overridden.  The exception is [SrtcpPacket] which does not
     * word align due to the auth tag and SRTCP index.
     */
    protected open val hasPadding: Boolean = false

    /**
     * The length value that should be written into the RTCP header's
     * length field.  This is open so that [SrtcpPacket] can override
     * it (since its length should not include the auth tag and
     * SRTCP index)
     */
    protected open val lengthValue: Int
        get() = RtpUtils.calculateRtcpLengthFieldValue(sizeBytes)

    protected fun payloadDataModified() {
        payloadDirty = true
    }

    val mutablePayload: ByteBuffer
        get() {
            synchronizeDataToBackingBufferIfNeeded()
            // We don't need to set the dirty flag here, because the
            // changes will be reflected directly to the buffer
            return backingBuffer.subBuffer(_header.sizeBytes, payloadDataSize)
        }

    val payload: ByteBuffer get() = mutablePayload.asReadOnlyBuffer()

    /**
     * Subclasses must implement this function, which has them write
     * their RTCP payload (anything held after the header) into the
     * backing buffer starting at its current position.
     */
    abstract fun serializePayloadDataInto(backingBuffer: ByteBuffer)

    protected fun cloneBackingBuffer(): ByteBuffer = backingBuffer.clone()

    private fun updateHeaderFields() {
        header.hasPadding = hasPadding
        header.length = lengthValue
    }

    @Suppress("UNCHECKED_CAST")
    fun <OtherType : RtcpPacket>toOtherRtcpPacketType(factory: (RtcpHeader, backingBuffer: ByteBuffer?) -> RtcpPacket): OtherType {
        // It's possible we could convert to another type without the buffer ever being
        // requested, meaning that backingBuffer could be in a default state (without
        // the limit being set correctly), so make sure we set it here.  Also make sure we synchronize
        // the header.
        synchronizeDataToBackingBufferIfNeeded()
        return factory(_header, backingBuffer) as OtherType
    }

    /**
     * If something has changed since the last time we wrote the held
     * data to the backing buffer, re-write it to make sure everything is
     * up to date
     */
    private fun synchronizeDataToBackingBufferIfNeeded() {
        if (headerDirty || payloadDirty) {
            if (headerDirty) {
                updateHeaderFields()
                _header.serializeTo(backingBuffer)
                headerDirty = false
            }
            if (payloadDirty) {
                backingBuffer.position(_header.sizeBytes)
                serializePayloadDataInto(backingBuffer)
                // The header length never changes, so it
                // changing can't affect the length but
                // the payload's length does, so make sure we
                // update backingBuffer.limit() here
                backingBuffer.flip()
                payloadDirty = false
            }
            backingBuffer.rewind()
        }
    }

    final override fun getBuffer(): ByteBuffer {
        synchronizeDataToBackingBufferIfNeeded()
        return backingBuffer.duplicate()
    }

    final override fun serializeTo(buf: ByteBuffer) {
        // The header values themselves (length, hasPadding) may not be in
        // sync with the payload, so update them
        updateHeaderFields()
        _header.serializeTo(buf)
        buf.put(payload)
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