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
package org.jitsi_modified.impl.neomedia.codec.video.vp8;

import org.jitsi.utils.*;
import org.jitsi.rtp.extensions.ByteKt;

/**
 * A depacketizer from VP8.
 * See {@link "http://tools.ietf.org/html/rfc7741"}
 *
 * @author Boris Grozev
 * @author George Politis
 * @author Jonathan Lennox
 */
public class DePacketizer
{
    /**
     * Returns true if the buffer contains a VP8 key frame at offset
     * <tt>offset</tt>.
     *
     * @param buf the byte buffer to check
     * @param off the offset in the byte buffer where the actual data starts
     * @param len the length of the data in the byte buffer
     * @return true if the buffer contains a VP8 key frame at offset
     * <tt>offset</tt>.
     */
    public static boolean isKeyFrame(byte[] buf, int off, int len)
    {
        // Check if this is the start of a VP8 partition in the payload
        // descriptor.
        if (!DePacketizer.VP8PayloadDescriptor.isValid(buf, off, len))
        {
            return false;
        }

        if (!DePacketizer.VP8PayloadDescriptor.isStartOfFrame(buf, off))
        {
            return false;
        }

        int szVP8PayloadDescriptor = DePacketizer
            .VP8PayloadDescriptor.getSize(buf, off, len);

        return DePacketizer.VP8PayloadHeader.isKeyFrame(
                buf, off + szVP8PayloadDescriptor);
    }

    /**
     * A class that represents the VP8 Payload Descriptor structure defined
     * in {@link "https://tools.ietf.org/html/rfc7741"}
     */
    public static class VP8PayloadDescriptor
    {
        /**
         * The bitmask for the TL0PICIDX field.
         */
        public static final int TL0PICIDX_MASK = 0xff;

        /**
         * The bitmask for the extended picture id field.
         */
        public static final int EXTENDED_PICTURE_ID_MASK = 0x7fff;

        /**
         * I bit from the X byte of the Payload Descriptor.
         */
        private static final byte I_BIT = (byte) 0x80;

        /**
         * K bit from the X byte of the Payload Descriptor.
         */
        private static final byte K_BIT = (byte) 0x10;

        /**
         * L bit from the X byte of the Payload Descriptor.
         */
        private static final byte L_BIT = (byte) 0x40;

        /**
         * M bit from the I byte of the Payload Descriptor.
         */
        private static final byte M_BIT = (byte) 0x80;

        /**
         * Maximum length of a VP8 Payload Descriptor.
         */
        public static final int MAX_LENGTH = 6;

        /**
         * S bit from the first byte of the Payload Descriptor.
         */
        private static final byte S_BIT = (byte) 0x10;

        /**
         * T bit from the X byte of the Payload Descriptor.
         */
        private static final byte T_BIT = (byte) 0x20;

        /**
         * X bit from the first byte of the Payload Descriptor.
         */
        private static final byte X_BIT = (byte) 0x80;

        /**
         * N bit from the first byte of the Payload Descriptor.
         */
        private static final byte N_BIT = (byte) 0x20;

        /**
         * Gets the temporal layer index (TID), if that's set.
         *
         * @param buf the byte buffer that holds the VP8 packet.
         * @param off the offset in the byte buffer where the VP8 packet starts.
         * @param len the length of the VP8 packet.
         * @return the temporal layer index (TID), if that's set, -1 otherwise.
         */
        public static int getTemporalLayerIndex(byte[] buf, int off, int len)
        {
            if (buf == null || buf.length < off + len || len < 2)
            {
                return -1;
            }

            if ((buf[off] & X_BIT) == 0 || (buf[off + 1] & T_BIT) == 0)
            {
                return -1;
            }

            int sz = getSize(buf, off, len);
            if (buf.length < off + sz || sz < 1)
            {
                return -1;
            }

            return (buf[off + sz - 1] & 0xc0) >> 6;
        }

        /**
         * Sets the temporal layer index (TID), if there's room for it.
         *
         * @param buf the byte buffer that holds the VP8 packet.
         * @param off the offset in the byte buffer where the VP8 packet starts.
         * @param len the length of the VP8 packet.
         * @param tid the temporal layer value to
         * @return true if the layer was set successfully, false otherwise.
         */
        public static boolean setTemporalLayerIndex(byte[] buf, int off, int len, int tid)
        {
            if (tid < 0 || tid > 3)
            {
                throw new IllegalArgumentException("Bad tid value " + tid);
            }

            if (buf == null || buf.length < off + len || len < 2)
            {
                return false;
            }

            if ((buf[off] & X_BIT) == 0 || (buf[off + 1] & T_BIT) == 0)
            {
                return false;
            }

            int sz = getSize(buf, off, len);
            if (buf.length < off + sz || sz < 1)
            {
                return false;
            }

            buf[off + sz - 1] = (byte)((tid << 6) | buf[off + sz - 1] & 0x3f);

            return true;
        }



        /**
         * Returns a simple Payload Descriptor, with PartID = 0, the 'start
         * of partition' bit set according to <tt>startOfPartition</tt>, and
         * all other bits set to 0.
         *
         * @param startOfPartition whether to 'start of partition' bit should be
         *                         set
         * @return a simple Payload Descriptor, with PartID = 0, the 'start
         * of partition' bit set according to <tt>startOfPartition</tt>, and
         * all other bits set to 0.
         */
        public static byte[] create(boolean startOfPartition)
        {
            byte[] pd = new byte[1];
            pd[0] = startOfPartition ? (byte) 0x10 : 0;
            return pd;
        }

        /**
         * The size in bytes of the Payload Descriptor at offset
         * <tt>offset</tt> in <tt>input</tt>. The size is between 1 and 6.
         *
         * @param baf the <tt>ByteArrayBuffer</tt> that holds the VP8 payload
         *            descriptor.
         * @return The size in bytes of the Payload Descriptor at offset
         * <tt>offset</tt> in <tt>input</tt>, or -1 if the input is not a valid
         * VP8 Payload Descriptor. The size is between 1 and 6.
         */
        public static int getSize(ByteArrayBuffer baf)
        {
            if (baf == null)
            {
                return -1;
            }

            return getSize(baf.getBuffer(), baf.getOffset(), baf.getLength());
        }

        /**
         * The size in bytes of the Payload Descriptor at offset
         * <tt>offset</tt> in <tt>input</tt>. The size is between 1 and 6.
         *
         * @param input  input
         * @param offset offset
         * @param length length
         * @return The size in bytes of the Payload Descriptor at offset
         * <tt>offset</tt> in <tt>input</tt>, or -1 if the input is not a valid
         * VP8 Payload Descriptor. The size is between 1 and 6.
         */
        public static int getSize(byte[] input, int offset, int length)
        {
            if (!isValid(input, offset, length))
                return -1;

            if ((input[offset] & X_BIT) == 0)
                return 1;

            int size = 2;
            if ((input[offset + 1] & I_BIT) != 0)
            {
                size++;
                if ((input[offset + 2] & M_BIT) != 0)
                    size++;
            }
            if ((input[offset + 1] & L_BIT) != 0)
                size++;
            if ((input[offset + 1] & (T_BIT | K_BIT)) != 0)
                size++;

            return size;
        }

        /**
         * Determines whether the VP8 payload specified in the buffer that is
         * passed as an argument has a picture ID or not.
         *
         * @param buf the byte buffer that contains the VP8 payload.
         * @param off the offset in the byte buffer where the VP8 payload
         *            starts.
         * @param len the length of the VP8 payload in the byte buffer.
         * @return true if the VP8 payload contains a picture ID, false
         * otherwise.
         */
        public static boolean hasPictureId(byte[] buf, int off, int len)
        {
            return isValid(buf, off, len)
                && (buf[off] & X_BIT) != 0 && (buf[off + 1] & I_BIT) != 0;
        }

        /**
         * Determines whether the VP8 payload specified in the buffer that is
         * passed as an argument has an extended picture ID or not.
         *
         * @param buf the byte buffer that contains the VP8 payload.
         * @param off the offset in the byte buffer where the VP8 payload
         *            starts.
         * @param len the length of the VP8 payload in the byte buffer.
         * @return true if the VP8 payload contains an extended picture ID,
         * false otherwise.
         */
        public static boolean hasExtendedPictureId(byte[] buf, int off, int len)
        {
            return hasPictureId(buf, off, len) && (buf[off + 2] & M_BIT) != 0;
        }

        /**
         * Gets the value of the PictureID field of a VP8 Payload Descriptor.
         *
         * @param input
         * @param offset
         * @return the value of the PictureID field of a VP8 Payload Descriptor,
         * or -1 if the fields is not present.
         */
        public static int getPictureId(byte[] input, int offset)
        {
            if (input == null
                || !hasPictureId(input, offset, input.length - offset))
            {
                return -1;
            }

            boolean isLong = (input[offset + 2] & M_BIT) != 0;
            if (isLong)
                return (input[offset + 2] & 0x7f) << 8
                    | (input[offset + 3] & 0xff);
            else
                return input[offset + 2] & 0x7f;

        }

        /**
         * Sets the extended picture ID for the VP8 payload specified in the
         * buffer that is passed as an argument.
         *
         * @param buf the byte buffer that contains the VP8 payload.
         * @param off the offset in the byte buffer where the VP8 payload
         *            starts.
         * @param len the length of the VP8 payload in the byte buffer.
         * @return true if the operation succeeded, false otherwise.
         */
        public static boolean setExtendedPictureId(
            byte[] buf, int off, int len, int val)
        {
            if (!hasExtendedPictureId(buf, off, len))
            {
                return false;
            }

            buf[off + 2] = (byte) (0x80 | (val >> 8) & 0x7F);
            buf[off + 3] = (byte) (val & 0xFF);

            return true;
        }

        /**
         * Sets the TL0PICIDX field for the VP8 payload specified in the
         * buffer that is passed as an argument.
         *
         * @param buf the byte buffer that contains the VP8 payload.
         * @param off the offset in the byte buffer where the VP8 payload
         *            starts.
         * @param len the length of the VP8 payload in the byte buffer.
         * @return true if the operation succeeded, false otherwise.
         */
        public static boolean setTL0PICIDX(byte[] buf, int off, int len,
            int val)
        {
            int offL = getLByteOffset(buf, off, len);
            if (offL < 0)
            {
                return false;
            }

            buf[off + offL] = (byte) val;
            return true;
        }

        /**
         * Checks whether the arguments specify a valid buffer.
         *
         * @param buf
         * @param off
         * @param len
         * @return true if the arguments specify a valid buffer, false
         * otherwise.
         */
        public static boolean isValid(byte[] buf, int off, int len)
        {
            return buf != null && buf.length >= off + len && off > -1
                && len > 0;
        }

        /**
         * Checks whether the '<tt>start of partition</tt>' bit is set in the
         * VP8 Payload Descriptor at offset <tt>offset</tt> in <tt>input</tt>.
         *
         * @param input  input
         * @param offset offset
         * @return <tt>true</tt> if the '<tt>start of partition</tt>' bit is set,
         * <tt>false</tt> otherwise.
         */
        public static boolean isStartOfPartition(byte[] input, int offset)
        {
            return (input[offset] & S_BIT) != 0;
        }

        /**
         * Sets the '<tt>start of partition</tt>' bit in the
         * VP8 Payload Descriptor at offset <tt>offset</tt> in <tt>input</tt>.
         *
         * @param input  input
         * @param offset offset
         * @param start whether it is start or not.
         */
        public static void setStartOfPartition(byte[] input, int offset, boolean start)
        {
            input[offset] = ByteKt.putBitWithMask(input[offset], S_BIT, start);
        }


        /**
         * Returns <tt>true</tt> if both the '<tt>start of partition</tt>' bit
         * is set and the <tt>PID</tt> fields has value 0 in the VP8 Payload
         * Descriptor at offset <tt>offset</tt> in <tt>input</tt>.
         *
         * @param input
         * @param offset
         * @return <tt>true</tt> if both the '<tt>start of partition</tt>' bit
         * is set and the <tt>PID</tt> fields has value 0 in the VP8 Payload
         * Descriptor at offset <tt>offset</tt> in <tt>input</tt>.
         */
        public static boolean isStartOfFrame(byte[] input, int offset)
        {
            return isStartOfPartition(input, offset)
                && getPartitionId(input, offset) == 0;
        }

        /**
         * Returns the value of the <tt>PID</tt> (partition ID) field of the
         * VP8 Payload Descriptor at offset <tt>offset</tt> in <tt>input</tt>.
         *
         * @param input
         * @param offset
         * @return the value of the <tt>PID</tt> (partition ID) field of the
         * VP8 Payload Descriptor at offset <tt>offset</tt> in <tt>input</tt>.
         */
        public static int getPartitionId(byte[] input, int offset)
        {
            return input[offset] & 0x07;
        }

        /**
         * Gets a boolean that indicates whether or not the non-reference bit is
         * set.
         *
         * @param buf the byte buffer that holds the VP8 payload descriptor.
         * @param off the offset in the byte buffer where the payload descriptor
         *            starts.
         * @param len the length of the payload descriptor in the byte buffer.
         * @return true if the non-reference bit is NOT set, false otherwise.
         */
        public static boolean isReference(byte[] buf, int off, int len)
        {
            return (buf[off] & N_BIT) == 0;
        }

        /**
         * Gets the TL0PICIDX from the payload descriptor.
         *
         * @param buf the byte buffer that holds the VP8 payload descriptor.
         * @param off the offset in the byte buffer where the payload descriptor
         *            starts.
         * @param len the length of the payload descriptor in the byte buffer.
         * @return the TL0PICIDX from the payload descriptor, or -1 if the packet
         *  does not have one.
         */
        public static int getTL0PICIDX(byte[] buf, int off, int len)
        {
            int offL = getLByteOffset(buf, off, len);
            if (offL < 0)
            {
                return -1;
            }

            return buf[off + offL] & 0xff;
        }

        /**
         * Return the offset of the {@code L} byte relative to the start of
         * the payload descriptor, or -1 if the PR has no {@code L} byte.
         *
         * @param buf the byte buffer that holds the VP8 payload descriptor.
         * @param off the offset in the byte buffer where the payload descriptor
         *            starts.
         * @param len the length of the payload descriptor in the byte buffer.
         * @return
         */
        private static int getLByteOffset(byte[] buf, int off, int len)
        {
            if (!isValid(buf, off, len)
                    || (buf[off] & X_BIT) == 0 || (buf[off + 1] & L_BIT) == 0)
            {
                return -1;
            }

            // We could use hasPictureId(), but it would unnecessarily repeat
            // the isValid() check.
            int offL = 2;
            if ((buf[off + 1] & I_BIT) != 0)
            {
                offL++;
                if ((buf[off + 2] & M_BIT) != 0)
                {
                    offL++;
                }
            }

            return offL;
        }
    }


    /**
     * A class that represents the VP8 Payload Header structure described
     * in {@link "http://tools.ietf.org/html/rfc7741"}
     */
    public static class VP8PayloadHeader
    {
        /**
         * S bit of the Payload Header.
         */
        private static final byte S_BIT = (byte) 0x01;

        /**
         * Returns true if the <tt>P</tt> (inverse key frame flag) field of the
         * VP8 Payload Header at offset <tt>offset</tt> in <tt>input</tt> is 0.
         *
         * @return true if the <tt>P</tt> (inverse key frame flag) field of the
         * VP8 Payload Header at offset <tt>offset</tt> in <tt>input</tt> is 0,
         * false otherwise.
         */
        public static boolean isKeyFrame(byte[] input, int offset)
        {
            // When set to 0 the current frame is a key frame.  When set to 1
            // the current frame is an interframe. Defined in [RFC6386]

            return (input[offset] & S_BIT) == 0;
        }

        /* Sets the <tt>P</tt> (inverse key frame flag) field of the
          * VP8 Payload header at offset <tt>offset</tt> in <tt>input</tt>.
           * Sets P to 0 if keyFrame is false, or to 1 if keyFrame is true.
         */
        public static void setKeyFrame(byte[] input, int offset, boolean keyFrame)
        {
            input[offset] = ByteKt.putBitWithMask(input[offset], S_BIT, !keyFrame);
        }
    }


    /**
     * A class that represents a keyframe header structure (see RFC 6386,
     * paragraph 9.1).
     *
     * @author George Politis
     */
    public static class VP8KeyframeHeader
    {
        // From RFC 6386, the keyframe header has this format.
        //
        // Start code byte 0     0x9d
        // Start code byte 1     0x01
        // Start code byte 2     0x2a
        //
        // 16 bits      :     (2 bits Horizontal Scale << 14) | Width (14 bits)
        // 16 bits      :     (2 bits Vertical Scale << 14) | Height (14 bits)

        /**
         * @return the height of this instance.
         */
        public static int getHeight(byte[] buf, int off)
        {
            return (((buf[off + 6] & 0xff) << 8) | buf[off + 5] & 0xff) & 0x3fff;
        }
    }
}
