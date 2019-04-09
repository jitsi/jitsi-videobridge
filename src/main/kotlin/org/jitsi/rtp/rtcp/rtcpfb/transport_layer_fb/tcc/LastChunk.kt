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

package org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc

import kotlin.math.min

// NOTE(brian): We have to store Chunk as an Int to avoid sign issues
typealias Chunk = Int

/**
 * This class is a port of TransportFeedback::LastChunk in
 * transport_feedback.h/transport_feedback.cc in Chrome
 * https://cs.chromium.org/chromium/src/third_party/webrtc/modules/rtp_rtcp/source/rtcp_packet/transport_feedback.h?l=95&rcl=20393ee9b7ba622f254908646a9c31bf87349fc7
 *
 * Because of this, it explicitly does NOT try to conform
 * to Kotlin style or idioms, instead striving to match the
 * Chrome code as closely as possible in an effort to make
 * future updates easier.
 */
class LastChunk {
    fun Empty(): Boolean {
        return size_ == 0
    }

    fun Clear() {
        size_ = 0
        all_same_ = true
        has_large_delta_ = false
    }

    // Return if delta sizes still can be encoded into single chunk with added
    // |delta_size|.
    fun CanAdd(deltaSize: DeltaSize): Boolean {
        if (size_ < kMaxTwoBitCapacity)
            return true
        if (size_ < kMaxOneBitCapacity && !has_large_delta_ && deltaSize != kLarge)
            return true
        if (size_ < kMaxRunLengthCapacity && all_same_ && delta_sizes_[0] == deltaSize)
            return true
        return false
    }

    // Add |delta_size|, assumes |CanAdd(delta_size)|,
    fun Add(deltaSize: DeltaSize) {
        if (size_ < kMaxVectorCapacity)
            delta_sizes_[size_] = deltaSize
        size_++
        all_same_ = all_same_ && deltaSize == delta_sizes_[0]
        has_large_delta_ = has_large_delta_ || deltaSize == kLarge
    }

    // Encode chunk as large as possible removing encoded delta sizes.
    // Assume CanAdd() == false for some valid delta_size.
    fun Emit(): Chunk {
        if (all_same_) {
            val chunk = EncodeRunLength()
            Clear()
            return chunk
        }
        if (size_ == kMaxOneBitCapacity) {
            val chunk = EncodeOneBit()
            Clear()
            return chunk
        }
        val chunk = EncodeTwoBit(kMaxTwoBitCapacity)
        // Remove |kMaxTwoBitCapacity| encoded delta sizes:
        // Shift remaining delta sizes and recalculate all_same_ && has_large_delta_.
        size_ -= kMaxTwoBitCapacity
        all_same_ = true
        has_large_delta_ = false
        for (i in 0 until size_) {
            val deltaSize = delta_sizes_[kMaxTwoBitCapacity + i]
            delta_sizes_[i] = deltaSize
            all_same_ = all_same_ && deltaSize == delta_sizes_[0]
            has_large_delta_ = has_large_delta_ || deltaSize == kLarge
        }

        return chunk
    }

    // // Encode all stored delta_sizes into single chunk, pad with 0s if needed.
    fun EncodeLast(): Chunk {
        if (all_same_)
            return EncodeRunLength()
        if (size_ <= kMaxTwoBitCapacity)
            EncodeTwoBit(size_)
        return EncodeOneBit()
    }

    // Decode up to |max_size| delta sizes from |chunk|.
    fun Decode(chunk: Chunk, max_size: Int) {
        if ((chunk and 0x8000) == 0) {
            DecodeRunLength(chunk, max_size)
        } else if ((chunk and 0x4000) == 0) {
            DecodeOneBit(chunk, max_size)
        } else {
            DecodeTwoBit(chunk, max_size)
        }
    }

    // Appends content of the Lastchunk to |deltas|.
    fun AppendTo(deltas: MutableList<DeltaSize>) {
        if (all_same_) {
            repeat(size_) {
                deltas.add(delta_sizes_[0])
            }
        } else {
            for (i in 0 until size_) {
                deltas.add(delta_sizes_[i])
            }
        }
    }

    // private:

    /**
     *
     * Run Length Status Vector Chunk
     *
     * 0                   1
     * 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5
     * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     * |T| S |       Run Length        |
     * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     *
     * T = 0
     * S = symbol
     * Run Length = Unsigned integer denoting the run length of the symbol
     */
    private fun EncodeRunLength(): Chunk =
        ((delta_sizes_[0] shl 13) or size_)

    private fun DecodeRunLength(chunk: Chunk, max_count: Int) {
        size_ = min(chunk and 0x1fff, max_count)
        val delta_size = (chunk ushr 13) and 0x03
        has_large_delta_ = delta_size >= kLarge
        all_same_ = true
        // To make it consistent with Add function, populate delta_sizes beyound 1st.
        for (i in 0 until min(size_, kMaxVectorCapacity)) {
            delta_sizes_[i] = delta_size
        }
    }

    /**
     *  One Bit Status Vector Chunk
     *
     * 0                   1
     * 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5
     * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     * |T|S|       symbol list         |
     * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     *
     * T = 1
     * S = 0
     * Symbol list = 14 entries where 0 = not received, 1 = received 1-byte delta.
     */
    private fun EncodeOneBit(): Chunk {
        var chunk = 0x8000
        for (i in 0 until size_) {
            chunk = (chunk or (delta_sizes_[i] shl (kMaxOneBitCapacity - 1 - i)))
        }
        return chunk
    }

    private fun DecodeOneBit(chunk: Chunk, max_size: Int) {
        size_ = min(kMaxOneBitCapacity, max_size)
        has_large_delta_ = false
        all_same_ = false
        for (i in 0 until size_) {
            delta_sizes_[i] = (chunk ushr (kMaxOneBitCapacity - 1 - i)) and 0x01
        }
    }

    /**
     * Two Bit Status Vector Chunk
     *
     * 0                   1
     * 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5
     * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     * |T|S|       symbol list         |
     * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+

     * T = 1
     * S = 1
     * symbol list = 7 entries of two bits each.
     */
    private fun EncodeTwoBit(size: Int): Chunk {
        var chunk = 0xC000
        for (i in 0 until size) {
            chunk = (chunk or (delta_sizes_[i] shl (2 * (kMaxTwoBitCapacity - 1 - i))))
        }
        return chunk
    }

    private fun DecodeTwoBit(chunk: Chunk, max_size: Int) {
        size_ = min(kMaxTwoBitCapacity, max_size)
        has_large_delta_ = true
        all_same_ = false
        for (i in 0 until size_) {
            delta_sizes_[i] = (chunk ushr (2 * (kMaxTwoBitCapacity - 1 - i))) and 0x03
        }
    }

    private var size_: Int = 0
    private var all_same_: Boolean = true
    private var has_large_delta_: Boolean = false
    private val delta_sizes_ = Array<DeltaSize>(kMaxVectorCapacity) { 0 }

    companion object {
        private const val kMaxRunLengthCapacity = 0x1FFF
        private const val kMaxOneBitCapacity = 14
        private const val kMaxTwoBitCapacity = 7
        private const val kMaxVectorCapacity = kMaxOneBitCapacity
        private const val kLarge: DeltaSize = 2
    }
}