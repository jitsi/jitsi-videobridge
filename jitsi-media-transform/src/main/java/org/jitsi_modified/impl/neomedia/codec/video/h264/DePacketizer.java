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

import org.jitsi.utils.logging2.*;

import static org.jitsi_modified.impl.neomedia.codec.video.h264.H264.*;

/**
 * Implements <tt>Codec</tt> to represent a depacketizer of H.264 RTP packets
 * into NAL units.
 *
 * @author Lyubomir Marinov
 * @author Damian Minkov
 */
public class DePacketizer
{
    /**
     * The <tt>Logger</tt> used by the <tt>DePacketizer</tt> class and its
     * instances for logging output.
     */
    private static final Logger logger = new LoggerImpl(DePacketizer.class.toString());

    /**
     * Returns true if the buffer contains a H264 key frame at offset
     * <tt>offset</tt>.
     *
     * @param buf the byte buffer to check
     * @param off the offset in the byte buffer where the actuall data starts
     * @param len the length of the data in the byte buffer
     * @return true if the buffer contains a H264 key frame at offset
     * <tt>offset</tt>.
     */
    public static boolean isKeyFrame(byte[] buf, int off, int len)
    {
      if (buf == null || buf.length < off + Math.max(len, 1))
      {
          return false;
      }

      int nalType =  buf[off] & kTypeMask;
      // Single NAL Unit Packet
      if (nalType == kFuA)
      {
          // Fragmented NAL units (FU-A).
          if (parseFuaNaluForKeyFrame(buf, off, len))
          {
              return true;
          }
      }
      else
      {
          if (parseSingleNaluForKeyFrame(buf, off, len))
          {
              return true;
          }
      }

      return false;
    }

    /**
     * Checks if a fragment of a NAL unit from a specific FU-A RTP packet
     * payload is keyframe or not.
     */
    private static boolean parseFuaNaluForKeyFrame(byte[] buf, int off, int len) {
      if (len < kFuAHeaderSize)
      {
          return false;
      }
      return ((buf[off + 1] & kTypeMask) == kIdr);
    }

    /**
     * Checks if a fragment of a NAL unit from a specific FU-A RTP packet
     * payload is keyframe or not.
     */
    private static boolean parseSingleNaluForKeyFrame(byte[] buf, int off, int len)
    {
        int naluStart = off + kNalHeaderSize;
        int naluLength = len - kNalHeaderSize;
        int nalType = buf[off] & kTypeMask;
        if (nalType == kStapA)
        {
            // Skip the StapA header (StapA nal type + length).
            if (len <= kStapAHeaderSize)
            {
                logger.error("StapA header truncated.");
                return false;
            }
            if (!verifyStapANaluLengths(buf, naluStart, naluLength))
            {
                logger.error("StapA packet with incorrect NALU packet lengths.");
                return false;
            }
            nalType = buf[off + kStapAHeaderSize] & kTypeMask;
        }
        return (nalType == kIdr || nalType == kSps ||
                nalType == kPps || nalType == kSei);
    }
}
