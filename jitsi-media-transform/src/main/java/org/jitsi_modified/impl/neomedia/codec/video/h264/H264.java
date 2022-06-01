/*
 * Copyright @ 2015 - present 8x8, Inc.
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
package org.jitsi_modified.impl.neomedia.codec.video.h264;

/**
 * Common utilities and constants for H264.
 */
public class H264
{
    /**
     * The bytes to prefix any NAL unit to be output by this
     * <tt>DePacketizer</tt> and given to a H.264 decoder. Includes
     * start_code_prefix_one_3bytes. According to "B.1 Byte stream NAL unit
     * syntax and semantics" of "ITU-T Rec. H.264 Advanced video coding for
     * generic audiovisual services", zero_byte "shall be present" when "the
     * nal_unit_type within the nal_unit() is equal to 7 (sequence parameter
     * set) or 8 (picture parameter set)" or "the byte stream NAL unit syntax
     * structure contains the first NAL unit of an access unit in decoding
     * order".
     */
    static final byte[] NAL_PREFIX = {0, 0, 0, 1};

    /**
     * Constants used to detect H264 keyframes in rtp packet
     */
    static final byte kTypeMask = 0x1F; // Nalu
    static final byte kIdr = 5;
    static final byte kSei = 6;
    static final byte kSps = 7;
    static final byte kPps = 8;
    static final byte kStapA = 24;
    static final byte kFuA = 28; // Header sizes
    static final int kNalHeaderSize = 1;
    static final int kFuAHeaderSize = 2;
    static final int kLengthFieldSize = 2;
    static final int kStapAHeaderSize = kNalHeaderSize + kLengthFieldSize;
    static final int kNalUSize = 2;

    /**
     * Check if Single-Time Aggregation Packet (STAP-A) NAL unit is correctly formed.
     *
     * @param data            STAP-A payload
     * @param offset          Starting position of NAL unit
     * @param lengthRemaining Bytes left in STAP-A
     * @return True if STAP-A NAL Unit is correct
     */
    static boolean verifyStapANaluLengths(byte[] data, int offset, int lengthRemaining)
    {
        if (data.length < offset + lengthRemaining)
        {
            return false;
        }
        while (lengthRemaining != 0)
        {
            if (lengthRemaining < kNalUSize)
            {
                return false;
            }
            int naluSize = kNalUSize + getUint16(data, offset);
            offset += naluSize;
            lengthRemaining -= naluSize;
        }
        return true;
    }

    static int getUint16(byte[] data, int offset)
    {
        return ((data[offset] & 0xff) << 8) | (data[offset + 1] & 0xff);
    }
}
