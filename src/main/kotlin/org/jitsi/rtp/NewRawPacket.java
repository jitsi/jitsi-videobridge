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
package org.jitsi.rtp;

import org.jetbrains.annotations.*;
import org.jitsi.rtp.rtp.*;
import org.jitsi.rtp.util.*;

import java.util.*;

/**
 * When using TransformConnector, a RTP/RTCP packet is represented using
 * NewRawPacket. NewRawPacket stores the buffer holding the RTP/RTCP packet, as well
 * as the inner offset and length of RTP/RTCP packet data.
 *
 * After transformation, data is also store in NewRawPacket objects, either the
 * original NewRawPacket (in place transformation), or a newly created NewRawPacket.
 *
 * Besides packet info storage, NewRawPacket also provides some other operations
 * such as readInt() to ease the development process.
 *
 * FIXME This class needs to be split/merged into RTPHeader, RTCPHeader,
 * ByteBufferUtils, etc.
 *
 * @author Werner Dittmann (Werner.Dittmann@t-online.de)
 * @author Bing SU (nova.su@gmail.com)
 * @author Emil Ivov
 * @author Damian Minkov
 * @author Boris Grozev
 * @author Lyubomir Marinov
 * @author George Politis
 */
public class NewRawPacket
    extends Packet
{
    /**
     * The size of the header for individual extensions.  Currently we only
     * support 1 byte header extensions
     */
    public static final int HEADER_EXT_HEADER_SIZE = 1;

    /**
     * A {@link HeaderExtensions} instance, used to iterate over the RTP header
     * extensions of this {@link NewRawPacket}.
     */
    private HeaderExtensions headerExtensions;

    /**
     * Initializes a new <tt>NewRawPacket</tt> instance with a specific
     * <tt>byte</tt> array buffer.
     *
     * @param buffer the <tt>byte</tt> array to be the buffer of the new
     * instance
     * @param offset the offset in <tt>buffer</tt> at which the actual data to
     * be represented by the new instance starts
     * @param length the number of <tt>byte</tt>s in <tt>buffer</tt> which
     * constitute the actual data to be represented by the new instance
     */
    public NewRawPacket(byte[] buffer, int offset, int length)
    {
        super(buffer, offset, length);
        headerExtensions = new HeaderExtensions();
    }

    public NewRawPacket(byte[] buffer)
    {
        super(buffer, 0, buffer.length);
        headerExtensions = new HeaderExtensions();
    }

    @NotNull
    @Override
    public NewRawPacket clone()
    {
        byte[] dataCopy = BufferPool.Companion.getGetArray().invoke(length);
        System.arraycopy(buffer, offset, dataCopy, 0, length);
        return new NewRawPacket(dataCopy, 0, length);
    }

    /**
     * Returns the number of CSRC identifiers currently included in this packet.
     *
     * @return the CSRC count for this <tt>NewRawPacket</tt>.
     */
    //TODO: will go away, but header extension code still using it
    private int getCsrcCount()
    {
        return getCsrcCount(buffer, offset, length);
    }

    /**
     * Returns the number of CSRC identifiers currently included in this packet.
     *
     * @param buffer
     * @param offset
     * @param length
     * @return the CSRC count for this <tt>NewRawPacket</tt>.
     */
    //TODO: will go away, but header extension code still using it
    private static int getCsrcCount(byte[] buffer, int offset, int length)
    {
        int cc = buffer[offset] & 0x0f;
        if (RtpHeader.FIXED_HEADER_SIZE_BYTES + cc * 4 > length)
            cc = 0;
        return cc;
    }

    /**
     * Returns <tt>true</tt> if the extension bit of this packet has been set
     * and <tt>false</tt> otherwise.
     *
     * @return  <tt>true</tt> if the extension bit of this packet has been set
     * and <tt>false</tt> otherwise.
     */
    //TODO: will be replaced by RtpPacket hasExtensions, but the addExtensions
    // logic still uses it
    private boolean getExtensionBit()
    {
        return getExtensionBit(buffer, offset, length);
    }

    /**
     * Returns <tt>true</tt> if the extension bit of this packet has been set
     * and <tt>false</tt> otherwise.
     *
     * @param buffer
     * @param offset
     * @param length
     * @return  <tt>true</tt> if the extension bit of this packet has been set
     * and <tt>false</tt> otherwise.
     */
    private static boolean getExtensionBit(byte[] buffer, int offset, int length)
    {
        return (buffer[offset] & 0x10) == 0x10;
    }

    /**
     * Returns the length of the extensions currently added to this packet.
     *
     * @return the length of the extensions currently added to this packet.
     */
    @Deprecated
    private int getExtensionLength()
    {
        return getExtensionLength(buffer, offset, length);
    }

    /**
     * Returns the length of the extensions currently added to this packet.
     *
     * @param buffer
     * @param offset
     * @param length
     * @return the length of the extensions currently added to this packet.
     */
    @Deprecated
    private static int getExtensionLength(byte[] buffer, int offset, int length)
    {
        if (!getExtensionBit(buffer, offset, length))
            return 0;

        // TODO should we verify the "defined by profile" field here (0xBEDE)?

        // The extension length comes after the RTP header, the CSRC list, and
        // two bytes in the extension header called "defined by profile".
        int extLenIndex = offset + RtpHeader.FIXED_HEADER_SIZE_BYTES
            + getCsrcCount(buffer, offset, length) * 4 + 2;

        int len
            = ((buffer[extLenIndex] << 8) | (buffer[extLenIndex + 1] & 0xFF))
                * 4;

        if (len < 0 || len > (length - RtpHeader.FIXED_HEADER_SIZE_BYTES - RtpHeader.EXT_HEADER_SIZE_BYTES -
            getCsrcCount(buffer, offset, length)*4))
        {
            // This is not a valid length. Together with the rest of the
            // header it exceeds the packet length. So be safe and assume
            // that there is no extension.
            len = 0;
        }

        return len;
    }

    /**
     * Get RTP header length from a RTP packet
     *
     * @return RTP header length from source RTP packet
     */
    @Deprecated
    private int getHeaderLength()
    {
        return getHeaderLength(buffer, offset, length);
    }

    /**
     * Get RTP header length from a RTP packet
     *
     * @param buffer
     * @param offset
     * @param length
     * @return RTP header length from source RTP packet
     */
    @Deprecated
    private static int getHeaderLength(byte[] buffer, int offset, int length)
    {
        int headerLength
            = RtpHeader.FIXED_HEADER_SIZE_BYTES + 4 * getCsrcCount(buffer, offset, length);

        // Make sure that the header length doesn't exceed the packet length.
        if (headerLength > length)
        {
            headerLength = length;
        }

        if (getExtensionBit(buffer, offset, length))
        {
            // Make sure that the header length doesn't exceed the packet
            // length.
            if (headerLength + RtpHeader.EXT_HEADER_SIZE_BYTES <= length)
            {
                headerLength += RtpHeader.EXT_HEADER_SIZE_BYTES
                    + getExtensionLength(buffer, offset, length);
            }
        }

        return headerLength;
    }

//    /**
//     * Returns the length of the header extension that is carrying the content
//     * starting at <tt>contentStart</tt>. In other words this method checks the
//     * size of extension headers in this packet and then either returns the
//     * value of the byte right before <tt>contentStart</tt> or its lower 4 bits.
//     * This is a very basic method so if you are using it - make sure u know
//     * what you are doing.
//     *
//     * @param contentStart the index of the first element of the content of
//     * the extension whose size we are trying to obtain.
//     *
//     * @return the length of the extension carrying the content starting at
//     * <tt>contentStart</tt>.
//     */
//    private int getLengthForExtension(int contentStart)
//    {
//        int hdrLen = getExtensionHeaderLength();
//
//        if( hdrLen == 1 )
//            return (buffer[contentStart - 1] & 0x0F) + 1;
//        else
//            return buffer[contentStart - 1];
//    }

    /**
     * Get RTP padding size from a RTP packet
     *
     * @return RTP padding size from source RTP packet
     */
    public int getPaddingSize()
    {
        return getPaddingSize(buffer, offset, length);
    }

    /**
     * Get RTP padding size from a RTP packet
     *
     * @return RTP padding size from source RTP packet
     */
    public static int getPaddingSize(byte[] buf, int off, int len)
    {
        if ((buf[off] & 0x20) == 0)
        {
            return 0;
        }
        else
        {
            // The last octet of the padding contains a count of how many
            // padding octets should be ignored, including itself.

            // XXX It's an 8-bit unsigned number.
            return 0xFF & buf[off + len - 1];
        }
    }

    /**
     * Get RTP payload length from a RTP packet
     *
     * @return RTP payload length from source RTP packet
     */
    @Deprecated
    private int getPayloadLength(boolean removePadding)
    {
        return getPayloadLength(buffer, offset, length, removePadding);
    }

    /**
     * Get RTP payload length from a RTP packet
     *
     * @return RTP payload length from source RTP packet
     */
    private int getPayloadLength()
    {
        return getPayloadLength(buffer, offset, length);
    }

    /**
     * Get RTP payload length from a RTP packet
     *
     * @param buffer
     * @param offset
     * @param length
     *
     * @return RTP payload length from source RTP packet
     */
    @Deprecated
    private static int getPayloadLength(byte[] buffer, int offset, int length)
    {
        return getPayloadLength(buffer, offset, length, false);
    }

    /**
     * Get RTP payload length from a RTP packet
     *
     * @param buffer
     * @param offset
     * @param length
     * @param removePadding
     *
     * @return RTP payload length from source RTP packet
     */
    @Deprecated
    private static int getPayloadLength(
        byte[] buffer, int offset, int length, boolean removePadding)
    {
        int lenHeader = getHeaderLength(buffer, offset, length);
        if (lenHeader < 0)
        {
            return -1;
        }

        int len = length - lenHeader;

        if (removePadding)
        {
            int szPadding = getPaddingSize(buffer, offset, length);
            if (szPadding < 0)
            {
                return -1;
            }

            len -= szPadding;
        }
        return len;
    }

    /**
     * Get the RTP payload offset of an RTP packet.
     *
     * @return the RTP payload offset of an RTP packet.
     */
    @Deprecated
    private int getPayloadOffset()
    {
        return getPayloadOffset(buffer, offset, length);
    }

    /**
     * Get the RTP payload offset of an RTP packet.
     *
     * @param buffer
     * @param offset
     * @param length
     *
     * @return the RTP payload offset of an RTP packet.
     */
    @Deprecated
    private static int getPayloadOffset(byte[] buffer, int offset, int length)
    {
        return offset + getHeaderLength(buffer, offset, length);
    }

    /**
     * Read an unsigned short at specified offset as a int
     *
     * @param off start offset of the unsigned short
     * @return the int value of the unsigned short at offset
     */
    @Deprecated
    private int readUint16AsInt(int off)
    {
        return RTPUtils.readUint16AsInt(buffer, offset + off);
    }

    /**
     * Removes the extension from the packet and its header.
     */
    public void removeExtension()
    {
        if(!getExtensionBit())
            return;

        int payloadOffset = offset + getHeaderLength();

        int extHeaderLen = getExtensionLength() + RtpHeader.EXT_HEADER_SIZE_BYTES;

        System.arraycopy(buffer, payloadOffset,
            buffer, payloadOffset - extHeaderLen, getPayloadLength());

        this.length -= extHeaderLen;

        setExtensionBit(false);
    }

    /**
     * @param buffer the buffer to set
     */
    @Override
    public void setBuffer(byte[] buffer)
    {
        super.setBuffer(buffer);
        // TODO: Is this necessary here?
        headerExtensions = new HeaderExtensions();
    }

    /**
     * Raises the extension bit of this packet is <tt>extBit</tt> is
     * <tt>true</tt> or set it to <tt>0</tt> if <tt>extBit</tt> is
     * <tt>false</tt>.
     *
     * @param extBit the flag that indicates whether we are to set or clear
     * the extension bit of this packet.
     */
    //TODO: will go away (use the one in RtpPacket) but extension stuff here is still
    // using it
    private void setExtensionBit(boolean extBit)
    {
        if(extBit)
            buffer[offset] |= 0x10;
        else
            buffer[offset] &= 0xEF;
    }

    /**
     * Sets the padding length for this RTP packet.
     *
     * @param len the padding length.
     * @return the number of bytes that were written, or -1 in case of an error.
     */
    public boolean setPaddingSize(int len)
    {
        if (buffer == null || buffer.length < offset + RtpHeader.FIXED_HEADER_SIZE_BYTES + len
            || len < 0 || len > 0xFF)
        {
            return false;
        }

        // Set the padding bit.
        buffer[offset] |= 0x20;
        buffer[offset + length - 1] = (byte) len;

        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString()
    {
        // Note: this will not print meaningful values unless the packet is an
        // RTP packet.
        StringBuilder sb
            = new StringBuilder("NewRawPacket[off=").append(offset)
            .append(", len=").append(length)
            .append(", hdrLen=").append(getHeaderLength())
            .append(", payloadLen=").append(getPayloadLength())
            .append(", paddingLen=").append(getPaddingSize())
            .append(", extLen=").append(getExtensionLength())
            .append(']');

        return sb.toString();
    }

//    /**
//     * @return the header extension of this {@link NewRawPacket} with the given ID,
//     * or null if the packet doesn't have one.
//     * WARNING: This method should not be used while iterating over the
//     * extensions with {@link #getHeaderExtensions()}, because it uses the same
//     * iterator.
//     * @param id
//     */
//    @Deprecated
//    private HeaderExtension getHeaderExtension(byte id)
//    {
//        HeaderExtensions hes = getHeaderExtensions();
//        while (hes.hasNext())
//        {
//            HeaderExtension he = hes.next();
//            if (he.getExtId() == id)
//            {
//                return he;
//            }
//        }
//        return null;
//    }

    /**
     * Represents an RTP header extension with the RFC5285 one-byte header:
     * <pre>{@code
     * 0
     * 0 1 2 3 4 5 6 7
     * +-+-+-+-+-+-+-+-+
     * |  ID   |  len  |
     * +-+-+-+-+-+-+-+-+
     * }</pre>
     */
    public class HeaderExtension
        extends ByteArrayBuffer
    {
        HeaderExtension()
        {
            super(NewRawPacket.this.buffer, 0, 0);
        }

        /**
         * @return the ID field of this extension.
         */
        public int getExtId()
        {
            if (super.getLength() <= 0)
                return -1;
            return (buffer[super.getOffset()] & 0xf0) >>> 4;
        }

        /**
         * @return the number of bytes of data in this header extension.
         */
        public int getExtLength()
        {
            // "The 4-bit length is the number minus one of data bytes of this
            // header extension element following the one-byte header.
            // Therefore, the value zero in this field indicates that one byte
            // of data follows, and a value of 15 (the maximum) indicates
            // element data of 16 bytes."
            return (buffer[super.getOffset()] & 0x0f) + 1;
        }
    }

    /**
     * Implements an iterator over the RTP header extensions of a
     * {@link NewRawPacket}.
     */
    public class HeaderExtensions
        implements Iterator<HeaderExtension>
    {
        /**
         * The offset of the next extension.
         */
        private int nextOff;

        /**
         * The remaining length of the extensions headers.
         */
        private int remainingLen;

        /**
         * The single {@link HeaderExtension} instance which will be updates
         * with each iteration.
         */
        private HeaderExtension headerExtension = new HeaderExtension();

        /**
         * Resets the iterator to the beginning of the header extensions of the
         * {@link NewRawPacket}.
         */
        private void reset()
        {
            int len = getExtensionLength();
            if (len <= 0)
            {
                // No extensions.
                nextOff = -1;
                remainingLen = -1;
                return;
            }

            nextOff
                = offset
                        + RtpHeader.FIXED_HEADER_SIZE_BYTES
                        + getCsrcCount(buffer, offset, length) * 4
                        + RtpHeader.EXT_HEADER_SIZE_BYTES;
            remainingLen = len;
        }

        /**
         * {@inheritDoc}
         * </p>
         * Returns true if this {@RawPacket} contains another header extension.
         */
        @Override
        public boolean hasNext()
        {
            if (remainingLen <= 0 || nextOff < 0)
            {
                return false;
            }

            int len = getExtLength(buffer, nextOff, remainingLen);
            if (len <= 0)
            {
                return false;
            }

            return true;
        }

        /**
         * @return the length in bytes of an RTP header extension with an
         * RFC5285 one-byte header. This is slightly different from
         * {@link HeaderExtension#getExtLength()} in that it includes the header
         * byte and checks the boundaries.
         */
        private int getExtLength(byte[] buf, int off, int len)
        {
            if (len <= 2)
            {
                return -1;
            }

            // len=0 indicates 1 byte of data; add 1 more byte for the id/len
            // field itself.
            int extLen = (buf[off] & 0x0f) + 2;

            if (extLen > len)
            {
                return -1;
            }
            return extLen;
        }

        /**
         * @return the next header extension of this {@link NewRawPacket}. Note
         * that it reuses the same object and only update its state.
         */
        @Override
        public HeaderExtension next()
        {
            // Prepare this.headerExtension
            int extLen = getExtLength(buffer, nextOff, remainingLen);
            if (extLen <= 0)
            {
                throw new IllegalStateException(
                    "Invalid extension length. Did hasNext() return true?");
            }
            headerExtension.setOffsetLength(nextOff, extLen);

            // Advance "next"
            nextOff += extLen;
            remainingLen -= extLen;

            return headerExtension;
        }
    }
}


class RTPUtils
{
    /**
     * Set an integer at specified offset in network order.
     *
     * @param off Offset into the buffer
     * @param data The integer to store in the packet
     */
    static int writeShort(byte[] buf, int off, short data)
    {
        buf[off++] = (byte)(data>>8);
        buf[off] = (byte)data;
        return 2;
    }

    /**
     * Read an unsigned short at a specified offset as an int.
     *
     * @param buf the buffer from which to read.
     * @param off start offset of the unsigned short
     * @return the int value of the unsigned short at offset
     */
    static int readUint16AsInt(byte[] buf, int off)
    {
        int b1 = (0xFF & (buf[off + 0]));
        int b2 = (0xFF & (buf[off + 1]));
        int val = b1 << 8 | b2;
        return val;
    }
}
