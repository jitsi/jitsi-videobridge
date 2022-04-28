package org.jitsi.nlj.rtp.codec.av1.dd

import org.apache.commons.compress.utils.BitInputStream
import org.jitsi.rtp.rtp.RtpPacket
import java.io.ByteArrayInputStream
import java.nio.ByteOrder

class BytesView(val bytes: ByteArray, val offset: Int, val length: Int) {
    private val bitStream = BitInputStream(ByteArrayInputStream(bytes, offset, length), ByteOrder.BIG_ENDIAN)
    constructor(ext: RtpPacket.HeaderExtension) : this(
        ext.currExtBuffer,
        // TODO what about 2 bytes header
        ext.currExtOffset + 1,
        ext.currExtLength - 1
    )

    fun readBoolean(): Boolean {
        return readInt(1) == 1
    }

    fun readInt(bitCount: Int): Int {
        return bitStream.readBits(bitCount).toInt()
    }

    fun readNonSymmetric(n: Int): Int {
        var w = 0
        var x = n
        while (x != 0) {
            x = x shr 1
            w++
        }
        val m = (1 shl w) - n
        val v = readInt(w - 1)
        if (v < m)
            return v
        val extraBit = readInt(1)
        return (v shl 1) - m + extraBit
    }
}
