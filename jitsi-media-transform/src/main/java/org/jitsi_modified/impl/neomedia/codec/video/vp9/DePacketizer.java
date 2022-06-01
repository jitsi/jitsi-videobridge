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
package org.jitsi_modified.impl.neomedia.codec.video.vp9;

import org.jitsi.rtp.extensions.*;

/**
 * A depacketizer from VP9.
 * See {@link "https://tools.ietf.org/html/draft-ietf-payload-vp9-10"}
 *
 * @author George Politis
 */
public class DePacketizer
{
    /*
     * VP9 Payload header, flexible mode (F=1):
     *           0 1 2 3 4 5 6 7
     *          +-+-+-+-+-+-+-+-+
     *          |I|P|L|F|B|E|V|Z| (REQUIRED)
     *          +-+-+-+-+-+-+-+-+
     *     I:   |M| PICTURE ID  | (REQUIRED)
     *          +-+-+-+-+-+-+-+-+
     *     M:   | EXTENDED PID  | (RECOMMENDED)
     *          +-+-+-+-+-+-+-+-+
     *     L:   | TID |U| SID |D| (CONDITIONALLY RECOMMENDED)
     *          +-+-+-+-+-+-+-+-+                             -\
     *     P,F: | P_DIFF      |N| (CONDITIONALLY REQUIRED)    - up to 3 times
     *          +-+-+-+-+-+-+-+-+                             -/
     *     V:   | SS            |
     *          | ..            |
     *          +-+-+-+-+-+-+-+-+
     *
     * Non-flexible mode (F=0):
     *           0 1 2 3 4 5 6 7
     *          +-+-+-+-+-+-+-+-+
     *          |I|P|L|F|B|E|V|Z| (REQUIRED)
     *          +-+-+-+-+-+-+-+-+
     *     I:   |M| PICTURE ID  | (RECOMMENDED)
     *          +-+-+-+-+-+-+-+-+
     *     M:   | EXTENDED PID  | (RECOMMENDED)
     *          +-+-+-+-+-+-+-+-+
     *     L:   | TID |U| SID |D| (CONDITIONALLY RECOMMENDED)
     *          +-+-+-+-+-+-+-+-+
     *          |   TL0PICIDX   | (CONDITIONALLY REQUIRED)
     *          +-+-+-+-+-+-+-+-+
     *     V:   | SS            |
     *          | ..            |
     *          +-+-+-+-+-+-+-+-+
     */

    /**
     * I bit from the first byte of the Payload Descriptor:
     * Picture ID present.
     */
    private static final byte I_BIT = (byte) (1 << 7);

    /**
     * P bit from the first byte of the Payload Descriptor:
     * Inter-picture predicted frame.
     */
    private static final byte P_BIT = (byte) (1 << 6);

    /**
     * L bit from the first byte of the Payload Descriptor:
     * Layer indices present.
     */
    private static final byte L_BIT = (byte) (1 << 5);

    /**
     * F bit from the first byte of the Payload Descriptor:
     * flexible mode.
     */
    private static final byte F_BIT = (byte) (1 << 4);

    /**
     * B bit from the first byte of the Payload Descriptor:
     * Start of frame.
     */
    private static final byte B_BIT = (byte) (1 << 3);

    /**
     * E bit from the first byte of the Payload Descriptor:
     * End of frame.
     */
    private static final byte E_BIT = (byte) (1 << 2);

    /**
     * V bit from the first byte of the Payload Descriptor:
     * Scalability structure present.
     */
    private static final byte V_BIT = (byte) (1 << 1);

    /**
     * Z bit from first byte of the Payload Descriptor:
     * Not an upper-level reference frame
     */
    private static final byte Z_BIT = (byte)1;

    /**
     * M bit from the first picture ID byte of the Payload Descriptor:
     * extended picture ID.  Only present if I is true in the first byte.
     */
    private static final byte M_BIT = (byte) (1 << 7);

    /**
     * U bit from the layer indices byte of the Payload Descriptor:
     * switching up point.  Only present if L is true in the first byte.
     */
    private static final byte U_BIT = (byte) (1 << 4);

    /**
     * D bit from the layer indices byte of the Payload Descriptor:
     * inter-layer dependency.  Only present if L is true in the first byte.
     */
    private static final byte D_BIT = (byte) (1);

    /**
     * N bit from the picture difference byte of the Payload Descriptor:
     * more picture diffs present.  Only present if P and F are true in the first byte.
     */
    private static final byte N_BIT = (byte) (1);

    /**
     * A class that represents the VP9 Payload Descriptor structure defined
     * in {@link "https://tools.ietf.org/html/draft-ietf-payload-vp9-10"}
     */
    public static class VP9PayloadDescriptor
    {
        /**
         * Returns <tt>true</tt> if the arguments specify a valid non-empty
         * VP9 packet.
         *
         * @param buf the byte buffer that holds the VP9 payload.
         * @param off the offset in the byte buffer where the VP9 payload starts.
         * @param len the length of the VP9 payload.
         *
         * @return <tt>true</tt> if the arguments specify a valid non-empty
         * buffer.
         */
        private static boolean isValid(byte[] buf, int off, int len)
        {
            return
                (buf != null && buf.length >= off + len && off > -1 && len > 0);
        }

        /**
         * Returns <tt>true</tt> if the B bit from the first byte of the payload
         * descriptor has value 0.  (Note this is start of frame, not start of picture!)
         *
         * @param buf the byte buffer that holds the VP9 payload.
         * @param off the offset in the byte buffer where the VP9 payload starts.
         * @param len the length of the VP9 payload.
         *
         * @return  <tt>true</tt> if the B bit from the first byte of the
         * payload descriptor has value 0, false otherwise.
         *
         */
        public static boolean isStartOfFrame(byte[] buf, int off, int len)
        {
            // Check if this is the start of a VP9 layer frame in the payload
            // descriptor.

            return isValid(buf, off, len) && (buf[off] & B_BIT) != 0;
        }

        /**
         * Sets the B bit (start of frame) in the first byte of the payload
         * descriptor.
         *
         * @param buf the byte buffer that holds the VP9 payload.
         * @param off the offset in the byte buffer where the VP9 payload starts.
         * @param len the length of the VP9 payload.
         * @param start the new value of the start of frame bit.
         */
        public static void setStartOfFrame(byte[] buf, int off, int len, boolean start)
        {
            // Check if this is the start of a VP9 layer frame in the payload
            // descriptor.

            if (!isValid(buf, off, len))
            {
                throw new IllegalStateException("Can't set startOfFrame for invalid VP9 packet");
            }

            buf[off] = ByteKt.putBitWithMask(buf[off], B_BIT, start);
        }

        /**
         * Returns <tt>true</tt> if the E bit from the first byte of the payload
         * descriptor has value 0.  (Note this is end of frame, not end of picture!)
         *
         * @param buf the byte buffer that holds the VP9 payload.
         * @param off the offset in the byte buffer where the VP9 payload starts.
         * @param len the length of the VP9 payload.
         *
         * @return  <tt>true</tt> if the E bit from the first byte of the
         * payload descriptor has value 0, false otherwise.
         */
        public static boolean isEndOfFrame(byte[] buf, int off, int len)
        {
            // Check if this is the end of a VP9 layer frame in the payload
            // descriptor.

            return isValid(buf, off, len) && (buf[off] & E_BIT) != 0;
        }

        /**
         * Sets the E bit (end of frame) in the first byte of the payload
         * descriptor.
         *
         * @param buf the byte buffer that holds the VP9 payload.
         * @param off the offset in the byte buffer where the VP9 payload starts.
         * @param len the length of the VP9 payload.
         * @param end the new value of the end of frame bit.
         */
        public static void setEndOfFrame(byte[] buf, int off, int len, boolean end)
        {
            if (!isValid(buf, off, len))
            {
                throw new IllegalStateException("Can't set endOfFrame for invalid VP9 packet");
            }

            buf[off] = ByteKt.putBitWithMask(buf[off], E_BIT, end);
        }

        /**
         * Returns <tt>true</tt> if the packet is encoded in flexible mode
         *
         * @param buf the byte buffer that holds the VP9 payload.
         * @param off the offset in the byte buffer where the VP9 payload starts.
         * @param len the length of the VP9 payload.
         *
         * @return  <tt>true</tt> if the packet is encoded in flexible mode,
         * false otherwise
         */
        public static boolean isFlexibleMode(byte[] buf, int off, int len)
        {
            return isValid(buf, off, len) && (buf[off] & F_BIT) != 0;
        }

        /**
         * Returns <tt>true</tt> if the packet contains a scalability structure
         *
         * @param buf the byte buffer that holds the VP9 payload.
         * @param off the offset in the byte buffer where the VP9 payload starts.
         * @param len the length of the VP9 payload.
         *
         * @return  <tt>true</tt> if the packet contains a scalability structure,
         * false otherwise
         */
        public static boolean hasScalabilityStructure(byte[] buf, int off, int len)
        {
            return isValid(buf, off, len) && (buf[off] & V_BIT) != 0;
        }

        /**
         * Returns <tt>true</tt> if the packet might be an upper-level reference.
         *
         * (Note this is inverted from the sense of the Z bit in the payload header.)
         *
         * @param buf the byte buffer that holds the VP9 payload.
         * @param off the offset in the byte buffer where the VP9 payload starts.
         * @param len the length of the VP9 payload.
         *
         * @return  <tt>true</tt> if the packet contains a scalability structure,
         * false otherwise
         */
        public static boolean isUpperLevelReference(byte[] buf, int off, int len)
        {
            return isValid(buf, off, len) && (buf[off] & Z_BIT) == 0;
        }

        /**
         * Sets the Z bit (upper layer reference) in the first byte of the payload
         * descriptor.
         *
         * @param buf the byte buffer that holds the VP9 payload.
         * @param off the offset in the byte buffer where the VP9 payload starts.
         * @param len the length of the VP9 payload.
         * @param ref whether the frame might be an upper-layer reference.
         *        (Note this is inverted from the sense of the Z bit in the payload header.)
         */
        public static void setUpperLevelReference(byte[] buf, int off, int len, boolean ref)
        {
            if (!isValid(buf, off, len))
            {
                throw new IllegalStateException("Can't set upperLevelReference for invalid VP9 packet");
            }

            buf[off] = ByteKt.putBitWithMask(buf[off], Z_BIT, !ref);
        }

        /**
         * Returns <tt>true</tt> if the packet has a picture ID.
         *
         * @param buf the byte buffer that holds the VP9 payload.
         * @param off the offset in the byte buffer where the VP9 payload starts.
         * @param len the length of the VP9 payload.
         *
         * @return <tt>true</tt> if the packet has a picture ID, <tt>false</tt>
         * if not.
         */
        public static boolean hasPictureId(byte[] buf, int off, int len)
        {
            return isValid(buf, off, len) && (buf[off] & I_BIT) != 0 && len > 1;
        }

        /**
         * Returns <tt>true</tt> if the packet has an extended (15-bit) picture ID.
         *
         * @param buf the byte buffer that holds the VP9 payload.
         * @param off the offset in the byte buffer where the VP9 payload starts.
         * @param len the length of the VP9 payload.
         *
         * @return <tt>true</tt> if the packet has a picture ID, <tt>false</tt>
         * if not.
         */
        public static boolean hasExtendedPictureId(byte[] buf, int off, int len)
        {
            return hasPictureId(buf, off, len) &&
                (buf[off+1] & M_BIT) != 0 && len > 2;
        }

        /**
         * Gets the value of the PictureID field of a VP9 Payload Descriptor.
         * @param buf the byte buffer that holds the VP9 payload.
         * @param off the offset in the byte buffer where the VP9 payload starts.
         * @param len the length of the VP9 payload.
         *
         * @return <tt>true</tt> if the packet has a picture ID, <tt>false</tt>
         * if not.
         */
        public static int getPictureId(byte[] buf, int off, int len)
        {
            if (!hasPictureId(buf, off, len))
            {
                return -1;
            }

            if (hasExtendedPictureId(buf, off, len))
            {
                return (buf[off + 1] & 0x7f) << 8
                    | (buf[off + 2] & 0xff);
            }
            else
            {
                return buf[off + 1] & 0x7f;
            }
        }

        /**
         * Sets the extended picture ID for the VP9 payload specified in the
         * buffer that is passed as an argument.
         *
         * @param buf the byte buffer that contains the VP9 payload.
         * @param off the offset in the byte buffer where the VP9 payload
         *            starts.
         * @param len the length of the VP9 payload in the byte buffer.
         * @return true if the operation succeeded, false otherwise.
         */
        public static boolean setExtendedPictureId(
            byte[] buf, int off, int len, int val)
        {
            if (!hasExtendedPictureId(buf, off, len))
            {
                return false;
            }

            buf[off + 1] = (byte) (0x80 | (val >> 8) & 0x7F);
            buf[off + 2] = (byte) (val & 0xFF);

            return true;
        }

        /**
         * Returns <tt>true</tt> if the packet contains an inter-picture
         * predicted frame.
         *
         * @param buf the byte buffer that holds the VP9 payload.
         * @param off the offset in the byte buffer where the VP9 payload starts.
         * @param len the length of the VP9 payload.
         *
         * @return <tt>true</tt> if the packet contains an inter-picture
         * predicted frame, <tt>false</tt> if not.
         */
        public static boolean isInterPicturePredicted(byte[] buf, int off, int len)
        {
            return isValid(buf, off, len) && (buf[off] & P_BIT) != 0;
        }

        /**
         * Sets the P bit (inter-picture predicted) in the first byte of the payload
         * descriptor.
         *
         * @param buf the byte buffer that holds the VP9 payload.
         * @param off the offset in the byte buffer where the VP9 payload starts.
         * @param len the length of the VP9 payload.
         * @param pred the new value of the inter-picture predicted bit.
         */
        public static void setInterPicturePredicted(byte[] buf, int off, int len, boolean pred)
        {
            if (!isValid(buf, off, len))
            {
                throw new IllegalStateException("Can't set interPicturePredicted for invalid VP9 packet");
            }

            buf[off] = ByteKt.putBitWithMask(buf[off], P_BIT, pred);
        }


        /**
         * Returns <tt>true</tt> if the packet is part of a keyframe.
         *
         * @param buf the byte buffer that holds the VP9 payload.
         * @param off the offset in the byte buffer where the VP9 payload starts.
         * @param len the length of the VP9 payload.
         *
         * @return <tt>true</tt> if the packet is part of a keyframe, false if not.
         */
        public static boolean isKeyFrame(byte[] buf, int off, int len)
        {
            if (!isValid(buf, off, len))
            {
                return false;
            }

            if ((buf[off] & P_BIT) != 0)
            {
                return false;
            }

            if (!hasLayerIndices(buf, off, len))
            {
                /* P bit without layer indices is a key frame. */
                return true;
            }

            return (getSpatialLayerIndex(buf, off, len) == 0 ||
                !usesInterLayerDependency(buf, off, len));
        }

        /**
         * Query whether the packet has layer indices values.
         *
         * @param buf the byte buffer that holds the VP9 payload.
         * @param off the offset in the byte buffer where the VP9 payload starts.
         * @param len the length of the VP9 payload.
         *
         * @return the temporal layer index (TID), if that's set, -1 otherwise.
         */
        public static boolean hasLayerIndices(byte[] buf, int off, int len)
        {
            return (isValid(buf, off, len) && (buf[off] & L_BIT) != 0);
        }

        private static int getLayerIndexOffset(byte[] buf, int off, int len)
        {
            if (!hasLayerIndices(buf, off, len))
            {
                return -1;
            }

            int loff = off + 1;
            if ((buf[off] & I_BIT) != 0)
            {
                loff += 1;
                if ((buf[off + 1] & M_BIT) != 0)
                {
                    // extended pid.
                    loff += 1;
                }
            }

            if (loff >= off+len)
            {
                return -1;
            }

            return loff;
        }

        /**
         * Determines whether the VP9 payload specified in the buffer that is
         * passed as an argument has a TL0PICIDX or not.
         *
         * @param buf the byte buffer that contains the VP9 payload.
         * @param off the offset in the byte buffer where the VP9 payload
         *            starts.
         * @param len the length of the VP9 payload in the byte buffer.
         * @return true if the VP9 payload contains a TL0PICIDX,
         * false otherwise.
         */
        public static boolean hasTL0PICIDX(byte[] buf, int off, int len)
        {
            int loff = getLayerIndexOffset(buf, off, len);
            if (loff < 0 || loff + 1 >= off+len)
            {
                return false;
            }
            return !isFlexibleMode(buf, off, len);
        }

        /**
         * Gets the TL0PICIDX from the payload descriptor.
         *
         * @param buf the byte buffer that holds the VP9 payload descriptor.
         * @param off the offset in the byte buffer where the payload descriptor
         *            starts.
         * @param len the length of the payload descriptor in the byte buffer.
         * @return the TL0PICIDX from the payload descriptor, or -1 if the packet
         *  does not have one.
         */
        public static int getTL0PICIDX(byte[] buf, int off, int len)
        {
            int loff = getLayerIndexOffset(buf, off, len);
            if (!hasTL0PICIDX(buf, off, len))
                return -1;
            return buf[loff+1] & 0xff;
        }

        /**
         * Sets the TL0PICIDX field for the VP9 payload specified in the
         * buffer that is passed as an argument.
         *
         * @param buf the byte buffer that contains the VP9 payload.
         * @param off the offset in the byte buffer where the VP9 payload
         *            starts.
         * @param len the length of the VP9 payload in the byte buffer.
         * @return true if the operation succeeded, false otherwise.
         */
        public static boolean setTL0PICIDX(byte[] buf, int off, int len,
            int val)
        {
            if (!hasTL0PICIDX(buf, off, len))
            {
                return false;
            }

            int loff = getLayerIndexOffset(buf, off, len);
            buf[loff+1] = (byte) val;
            return true;
        }

        /**
         * Gets the temporal layer index (TID), if that's set.
         *
         * @param buf the byte buffer that holds the VP9 payload.
         * @param off the offset in the byte buffer where the VP9 payload starts.
         * @param len the length of the VP9 payload.
         *
         * @return the temporal layer index (TID), if that's set, -1 otherwise.
         */
        public static int getTemporalLayerIndex(byte[] buf, int off, int len)
        {
            int loff = getLayerIndexOffset(buf, off, len);
            if (loff < 0)
            {
                return -1;
            }

            return (buf[loff] & 0xE0) >> 5;
        }

        /**
         * Gets the spatial layer index (SID), if that's set.
         *
         * @param buf the byte buffer that holds the VP9 payload.
         * @param off the offset in the byte buffer where the VP9 payload starts.
         * @param len the length of the VP9 payload.
         *
         * @return the spatial layer index (SID), if that's set, -1 otherwise.
         */
        public static int getSpatialLayerIndex(byte[] buf, int off, int len)
        {
            int loff = getLayerIndexOffset(buf, off, len);
            if (loff < 0)
            {
                return -1;
            }

            return (buf[loff] & 0xE) >> 1;
        }

        /**
         * Gets whether this frame is a temporal switching-up point, where it is
         * valid to switch the stream to a higher temporal layer.
         *
         * @param buf the byte buffer that holds the VP9 payload.
         * @param off the offset in the byte buffer where the VP9 payload starts.
         * @param len the length of the VP9 payload.
         *
         * @return <tt>true</tt> if the packet is a switching-up point, <tt>false</tt>
         * if not.
         */
        public static boolean isSwitchingUpPoint(byte[] buf, int off, int len)
        {
            int loff = getLayerIndexOffset(buf, off, len);
            if (loff < 0)
            {
                return false;
            }

            return (buf[loff] & U_BIT) != 0;
        }

        /**
         * Gets whether this frame uses inter-layer dependency, where the frame
         * depends on lower spatial-layer frames of the same picture.
         *
         * @param buf the byte buffer that holds the VP9 payload.
         * @param off the offset in the byte buffer where the VP9 payload starts.
         * @param len the length of the VP9 payload.
         *
         * @return <tt>true</tt> if the packet uses inter-layer dependency, <tt>false</tt>
         * if not.
         */
        public static boolean usesInterLayerDependency(byte[] buf, int off, int len)
        {
            int loff = getLayerIndexOffset(buf, off, len);
            if (loff < 0)
            {
                return false;
            }

            return (buf[loff] & D_BIT) != 0;
        }

        /**
         * Sets the packet's layer indices, if there's room for it in the packet.
         *
         * @param buf the byte buffer that holds the VP9 payload.
         * @param off the offset in the byte buffer where the VP9 payload starts.
         * @param len the length of the VP9 payload.
         * @param sid the spatial layer of the packet
         * @param tid the temporal layer of the packet
         * @param isSwitchingUpPoint whether the packet is a switching-up point
         * @param usesInterLayerDependency whether the packet uses inter-layer dependency
         *
         * @return Whether the layer indices were successfully set
         */
        public static boolean setLayerIndices(byte[] buf, int off, int len,
            int sid, int tid, boolean isSwitchingUpPoint, boolean usesInterLayerDependency)
        {
            int loff = getLayerIndexOffset(buf, off, len);
            if (loff < 0)
            {
                return false;
            }

            if (sid < 0 || sid > 8)
            {
                throw new IllegalArgumentException("Invalid spatial ID " + sid);
            }

            if (tid < 0 || tid > 8)
            {
                throw new IllegalArgumentException("Invalid spatial ID " + sid);
            }

            buf[loff] = (byte)((tid << 5) |
                (isSwitchingUpPoint ? U_BIT : 0) |
                (sid << 1) |
                (usesInterLayerDependency ? D_BIT : 0));

            return true;
        }

        /**
         * The size in bytes of the Payload Descriptor at off
         * <tt>off</tt> in <tt>buf</tt>. The size is at least 1.
         *
         * @param buf buf
         * @param off off
         * @param len len
         * @return The size in bytes of the Payload Descriptor at off
         * <tt>off</tt> in <tt>buf</tt>, or -1 if the buffer is not a valid
         * VP9 Payload Descriptor. The size is at least 1.
         */
        public static int getSize(byte[] buf, int off, int len)
        {
            if (!isValid(buf, off, len))
                return -1;

            int pos = off + 1;

            if ((buf[off] & I_BIT) != 0)
            {
                boolean extended = (buf[pos] & M_BIT) != 0;
                pos++;
                if (extended)
                {
                    pos++;
                }
            }

            if ((buf[off] & L_BIT) != 0)
            {
                pos++;
                if ((buf[off] & F_BIT) == 0)
                {
                    pos++;
                }
            }

            if ((buf[off] & F_BIT) != 0 && (buf[off] & P_BIT) != 0)
            {
                for (int i = 0; i < 3; i++) {
                    int hasNext = buf[pos] & N_BIT;
                    pos++;

                    if (hasNext == 0)
                    {
                        break;
                    }
                }
            }

            if ((buf[off] & V_BIT) != 0)
            {
                /* SS present */
                int n_s = (buf[pos] & 0xE0) >> 5;
                boolean resPresent = ((buf[pos] & (1 << 4)) != 0);
                boolean pgPresent = ((buf[pos] & (1 << 3)) != 0);
                pos++;

                if (resPresent)
                {
                    pos += 4 * (n_s + 1);
                }

                if (pgPresent)
                {
                    int n_g = (buf[pos] & 0xff);
                    int i;
                    pos++;

                    for (i = 0; i < n_g; i++)
                    {
                        int r = (buf[pos] & 0x0C) >> 2;
                        pos += r + 1;
                    }
                }
            }

            return pos - off;
        }

        /**
         * The offset of the scalability structure within the buffer, if
         * the buffer contains a VP9 packet with a scalability structure; otherwise -1.
         *
         * @param buf  buf
         * @param off off
         * @param len len
         * @return The size in bytes of the Payload Descriptor at off
         * <tt>off</tt> in <tt>buf</tt>, or -1 if the buffer is not a valid
         * VP9 Payload Descriptor. The size is at least 1.
         */
        public static int getScalabilityStructureOffset(byte[] buf, int off, int len)
        {
            if (!hasScalabilityStructure(buf, off, len))
            {
                return -1;
            }

            int pos = off + 1;

            if ((buf[off] & I_BIT) != 0)
            {
                boolean extended = (buf[pos] & M_BIT) != 0;
                pos++;
                if (extended)
                {
                    pos++;
                }
            }

            if ((buf[off] & L_BIT) != 0)
            {
                pos++;
                if ((buf[off] & F_BIT) == 0)
                {
                    pos++;
                }
            }

            if ((buf[off] & F_BIT) != 0 && (buf[off] & P_BIT) != 0)
            {
                do {
                    pos++;
                } while ((buf[pos] & N_BIT) != 0);
            }

            return pos;
        }
    }
}
