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

import org.jitsi.rtp.util.BitBuffer
import org.jitsi.rtp.util.BufferView
import unsigned.toUInt
import java.nio.ByteBuffer

class Vp8PayloadDescriptor(bv: BufferView) {
    val extendedControlBitsPresent: Boolean
    val nonReferenceFrame: Boolean
    val startOfPartition: Boolean
    val partitionIndex: Int
    var pictureIdPresent: Boolean? = null
    var tl0PicIdxPresent: Boolean? = null
    var tidPresent: Boolean? = null
    var keyIdxPresent: Boolean? = null
    var sixteenBitPicId: Boolean? = null
    var picId: Int? = null
    var tl0PicIdx: Int? = null
    var temporalLayerId: Int? = null
    var layerSyncBit: Boolean? = null
    var keyFrameIndex: Int? = null

    // vp8 payload header
    // ....
    var isKeyFrame: Boolean? = null

    init {
        val buf = ByteBuffer.wrap(bv.array, bv.offset, bv.length)
        val bitBuffer = BitBuffer(buf)
        extendedControlBitsPresent = bitBuffer.getBitAsBoolean()
        bitBuffer.getBitAsBoolean() // reserved
        nonReferenceFrame = bitBuffer.getBitAsBoolean()
        startOfPartition = bitBuffer.getBitAsBoolean()
        bitBuffer.getBitAsBoolean() // reserved
        partitionIndex = bitBuffer.getBits(3).toUInt()

        if (extendedControlBitsPresent) {
            pictureIdPresent = bitBuffer.getBitAsBoolean()
            tl0PicIdxPresent = bitBuffer.getBitAsBoolean()
            tidPresent = bitBuffer.getBitAsBoolean()
            keyIdxPresent = bitBuffer.getBitAsBoolean()
            bitBuffer.getBits(4) // reserved
            if (pictureIdPresent!!) {
                sixteenBitPicId = bitBuffer.getBitAsBoolean()
                bitBuffer.rewindBits(1)
                picId = if (sixteenBitPicId!!) {
                    buf.getShort().toUInt()
                } else {
                    buf.get().toUInt()
                }
            }
            if (tl0PicIdxPresent!!) {
                tl0PicIdx = buf.get().toUInt()
            }

            if (tidPresent!! or keyIdxPresent!!) {
                temporalLayerId = bitBuffer.getBits(2).toUInt()
                layerSyncBit = bitBuffer.getBitAsBoolean()
                keyFrameIndex = bitBuffer.getBits(5).toUInt()
            }
        }

        // last bit of the next byte is the inverse key frame flag
        bitBuffer.getBits(7)
        isKeyFrame = bitBuffer.getBitAsBoolean()
    }

    override fun toString(): String {
        return with(StringBuffer()) {
            appendln("extendedControlBitsPresent: $extendedControlBitsPresent")
            appendln("nonReferenceFrame: $nonReferenceFrame")
            appendln(" startOfPartition: $startOfPartition")
            appendln("partitionIndex: $partitionIndex")
            appendln("pictureIdPresent: $pictureIdPresent")
            appendln("tl0PicIdxPresent: $tl0PicIdxPresent")
            appendln("tidPresent $tidPresent")
            appendln("keyIdxPresent $keyIdxPresent")
            appendln("sixteenBitPicId $sixteenBitPicId")
            appendln("picId $picId")
            appendln("tl0PicIdx $tl0PicIdx")
            appendln("temporalLayerId $temporalLayerId")
            appendln("layerSyncBit $layerSyncBit")
            appendln("keyFrameIndex $keyFrameIndex")
            appendln("isKeyFrame? $isKeyFrame")
            toString()
        }
    }
}
