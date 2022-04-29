package org.jitsi.nlj.rtp.codec.av1.dd

import org.jitsi.rtp.extensions.bytearray.toHex
import org.jitsi.rtp.rtp.RtpHeader
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.rtp.rtp.header_extensions.HeaderExtensionHelpers
import org.jitsi.rtp.util.getByteAsInt
import org.jitsi.rtp.util.getShortAsInt

class TwoBytesExtNormalizer {
    /**
     * strip off 2 bytes extensions
     */
    fun handle(rtpPacket: RtpPacket): List<TwoBytesExtension> {
        if (!rtpPacket.hasExtensions) {
            return emptyList()
        }

        val extensionBlockOffset = rtpPacket.offset + RtpHeader.FIXED_HEADER_SIZE_BYTES + rtpPacket.csrcCount * 4
        val profile = rtpPacket.buffer.getShortAsInt(extensionBlockOffset)

        // 0x1000
        if (profile != 4096) {
            return emptyList()
        }

        val extensionLength = HeaderExtensionHelpers.getExtensionsTotalLength(rtpPacket.buffer, extensionBlockOffset)
        val extensionDataLength = extensionLength - HeaderExtensionHelpers.TOP_LEVEL_EXT_HEADER_SIZE_BYTES

        if (extensionDataLength == 0) {
            return emptyList()
        }

        var curOffset = extensionBlockOffset + HeaderExtensionHelpers.TOP_LEVEL_EXT_HEADER_SIZE_BYTES
        val extensions = mutableListOf<TwoBytesExtension>()

        while (curOffset < rtpPacket.payloadOffset - 1) {
            val id = rtpPacket.buffer.getByteAsInt(curOffset)
            val payloadLength = rtpPacket.buffer.getByteAsInt(curOffset + 1)

            if (payloadLength > 0) {
                extensions.add(
                    TwoBytesExtension(
                        id = id,
                        payloadLength = payloadLength,
                        payload = rtpPacket.buffer.copyOfRange(curOffset + 2, curOffset + 2 + payloadLength)
                    )
                )
            }

            curOffset += (2 + payloadLength)
        }

        rtpPacket.removeHeaderExtensionsExcept(emptySet())
        extensions.forEach { extension ->
            if (extension.payloadLength <= 16) {
                val newExt = rtpPacket.addHeaderExtension(extension.id, extension.payloadLength)
                extension.payload.copyInto(newExt.currExtBuffer, newExt.currExtOffset + 1)
            }
        }
        rtpPacket.encodeHeaderExtensions()

        return extensions
    }

    class TwoBytesExtension(
        val id: Int,
        val payloadLength: Int,
        val payload: ByteArray
    ) {
        override fun toString(): String {
            return "TwoBytesExtension(id=$id, length=$payloadLength, data=${payload.toHex()})"
        }
    }
}
