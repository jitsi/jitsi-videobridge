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
import org.jitsi.rtp.rtcp.*;
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
     * The size of the extension header as defined by RFC 3550.
     */
    public static final int EXT_HEADER_SIZE = 4;

    /**
     * The size of the fixed part of the RTP header as defined by RFC 3550.
     */
    public static final int FIXED_HEADER_SIZE = 12;

    /**
     * The bitmask for the RTP sequence number field.
     */
    public static final int SEQUENCE_NUMBER_MASK = 0xffff;

    /**
     * The bitmask for the RTP timestamp field.
     */
    public static final long TIMESTAMP_MASK = 0xFFFF_FFFFL;

    /**
     * The bitmap/flag mask that specifies the set of boolean attributes enabled
     * for this <tt>NewRawPacket</tt>. The value is the logical sum of all of the
     * set flags. The possible flags are defined by the <tt>FLAG_XXX</tt>
     * constants of FMJ's {@link javax.media.Buffer} class.
     */
    private int flags;

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

    /**
     * Adds the given buffer as a header extension of this packet
     * according the rules specified in RFC 5285. Note that this method does
     * not replace extensions so if you add the same buffer twice it would be
     * added as a separate extension.
     *
     * This method MUST NOT be called while iterating over the extensions using
     * {@link #getHeaderExtensions()}, or while manipulating the state of this
     * {@link NewRawPacket}.
     *
     * @param id the ID with which to add the extension.
     * @param data the buffer containing the extension data.
     */
    public void addExtension(byte id, byte[] data)
    {
        addExtension(id, data, data.length);
    }

    /**
     * Adds the given buffer as a header extension of this packet
     * according the rules specified in RFC 5285. Note that this method does
     * not replace extensions so if you add the same buffer twice it would be
     * added as a separate extension.
     *
     * This method MUST NOT be called while iterating over the extensions using
     * {@link #getHeaderExtensions()}, or while manipulating the state of this
     * {@link NewRawPacket}.
     *
     * @param id the ID with which to add the extension.
     * @param data the buffer containing the extension data.
     * @param len the length of the extension.
     */
    public void addExtension(byte id, byte[] data, int len)
    {
        if (data == null || len < 1 || len > 16 || data.length < len)
        {
            throw new IllegalArgumentException(
                "id=" + id + " data.length="
                    + (data == null ? "null" : data.length)
                    + " len=" + len);
        }

        HeaderExtension he = addExtension(id, len);
        System.arraycopy(data, 0,
                         he.getBuffer(), he.getOffset() + 1,
                         len);
    }

    /**
     * Adds an RTP header extension with a given ID and a given length to this
     * packet. The contents of the extension are not set to anything, and the
     * caller of this method is responsible for filling them in.
     *
     * This method MUST NOT be called while iterating over the extensions using
     * {@link #getHeaderExtensions()}, or while manipulating the state of this
     * {@link NewRawPacket}.
     *
     * @param id the ID of the extension to add.
     * @param len the length in bytes of the extension to add.
     * @return the header extension which was added.
     */
    public HeaderExtension addExtension(byte id, int len)
    {
        if (id < 1 || id > 15 || len < 1 || len > 16)
        {
            throw new IllegalArgumentException("id=" + id + " len=" + len);
        }

        // The byte[] of a NewRawPacket has the following structure:
        // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
        // | A: unused | B: hdr + ext | C: payload | D: unused |
        // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
        // And the regions have the following sizes:
        // A: this.offset
        // B: this.getHeaderLength()
        // C: this.getPayloadLength()
        // D: this.buffer.length - this.length - this.offset
        // We will try to extend the packet so that it uses A and/or D if
        // possible, in order to avoid allocating new memory.

        // We get this early, before we modify the buffer.
        int payloadLength = getPayloadLength();
        boolean extensionBit = getExtensionBit();
        int extHeaderOffset = FIXED_HEADER_SIZE + 4 * getCsrcCount();


        // This is an upper bound on the required length for the packet after
        // the addition of the new extension. It is easier to calculate than
        // the exact number, and is relatively close (it may be off by a few
        // bytes due to padding)
        int maxRequiredLength
            = getLength()
            + (extensionBit ? 0 : EXT_HEADER_SIZE)
            + 1 /* the 1-byte header of the extension element */
            + len
            + 3 /* padding */;


        byte[] newBuffer;
        int newPayloadOffset;
        if (buffer.length >= maxRequiredLength)
        {
            // We don't need a new buffer.
            newBuffer = buffer;

            if ( (offset + getHeaderLength()) >=
                 (maxRequiredLength - getPayloadLength()))
            {
                // If region A (see above) is enough to accommodate the new
                // packet, then keep the payload where it is.
                newPayloadOffset = getPayloadOffset();
            }
            else
            {
                // Otherwise, we have to use region D. To do so, move the
                // payload to the right.
                newPayloadOffset = buffer.length - payloadLength;
                System.arraycopy(buffer, getPayloadOffset(),
                                 buffer, newPayloadOffset,
                                 payloadLength);
            }
        }
        else
        {
            // We need a new buffer. We will place the payload to the very right.
            newBuffer = BufferPool.Companion.getGetArray().invoke(maxRequiredLength);
            newPayloadOffset = newBuffer.length - payloadLength;
            System.arraycopy(buffer, getPayloadOffset(),
                             newBuffer, newPayloadOffset,
                             payloadLength);
        }

        // By now we have the payload in a position which leaves enough space
        // for the whole new header.
        // Next, we are going to construct a new header + extensions (including
        // the one we are adding) at offset 0, and once finished, we will move
        // them to the correct offset.


        int newHeaderLength = extHeaderOffset;
        // The bytes in the header extensions, excluding the (0xBEDE, length)
        // field and any padding.
        int extensionBytes = 0;
        if (extensionBit)
        {
            // (0xBEDE, length)
            newHeaderLength += 4;

            // We can't find the actual length without an iteration because
            // of padding. It is safe to iterate, because we have not yet
            // modified the header (we only might have moved the offset right)
            HeaderExtensions hes = getHeaderExtensions();
            while (hes.hasNext())
            {
                HeaderExtension he = hes.next();
                // 1 byte for id/len + data
                extensionBytes += 1 + he.getExtLength();
            }

            newHeaderLength += extensionBytes;
        }

        // Copy the header (and extensions, excluding padding, if there are any)
        System.arraycopy(buffer, offset,
                         newBuffer, 0,
                         newHeaderLength);

        if (!extensionBit)
        {
            // If the original packet didn't have any extensions, we need to
            // add the extension header (RFC 5285):
            //  0                   1                   2                   3
            //  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
            // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
            // |       0xBE    |    0xDE       |           length              |
            // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
            RTPUtils.writeShort(newBuffer, extHeaderOffset, (short) 0xBEDE);
            // We will set the length field later.
            newHeaderLength += 4;
        }

        // Finally we get to add our extension.
        newBuffer[newHeaderLength++]
            = (byte) ((id & 0x0f) << 4 | (len - 1) & 0x0f);
        extensionBytes++;

        // This is where the data of the extension that we add begins. We just
        // skip 'len' bytes, and let the caller fill them in. We have to go
        // back one byte, because newHeaderLength already moved.
        int extensionDataOffset = newHeaderLength - 1;
        newHeaderLength += len;
        extensionBytes += len;

        int paddingBytes = (4 - (extensionBytes % 4)) % 4;
        for (int i = 0; i < paddingBytes; i++)
        {
            // Set the padding to 0 (we have to do this because we may be
            // reusing a buffer).
            newBuffer[newHeaderLength++] = 0;
        }

        RTPUtils.writeShort(newBuffer, extHeaderOffset + 2,
                            (short) ((extensionBytes + paddingBytes) / 4));

        // Now we have the new header, with the added header extension and with
        // the correct padding, in newBuffer at offset 0. Lets move it to the
        // correct place (right before the payload).
        int newOffset = newPayloadOffset - newHeaderLength;
        if (newOffset != 0)
        {
            System.arraycopy(newBuffer, 0,
                             newBuffer, newOffset,
                             newHeaderLength);
        }

        // All that is left to do is update the NewRawPacket state.
        byte[] oldBuffer = buffer;
        setBuffer(newBuffer);
        // Reference comparison to see if we got a new buffer.  If so, return the old one to the pool
        if (oldBuffer != newBuffer)
        {
            BufferPool.Companion.getReturnArray().invoke(oldBuffer);
        }

        this.offset = newOffset;
        this.length = newHeaderLength + payloadLength;

        // ... and set the extension bit.
        setExtensionBit(true);

        // Setup the single HeaderExtension instance of this NewRawPacket and
        // return it.
        HeaderExtension he = getHeaderExtensions().headerExtension;
        he.setOffset( getOffset() + extensionDataOffset);
        he.setLength(len + 1);
        return he;
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
     * Append a byte array to the end of the packet. This may change the data
     * buffer of this packet.
     *
     * @param data byte array to append
     * @param len the number of bytes to append
     */
    public void append(byte[] data, int len) {
        if (data == null || len == 0)  {
            return;
        }

        // Ensure the internal buffer is long enough to accommodate data. (The
        // method grow will re-allocate the internal buffer if it's too short.)
        grow(len);
        // Append data.
        System.arraycopy(data, 0, buffer, length + offset, len);
        length += len;
    }

    /**
     * Returns a map binding CSRC IDs to audio levels as reported by the remote
     * party that sent this packet.
     *
     * @param csrcExtID the ID of the extension that's transporting csrc audio
     * levels in the session that this <tt>NewRawPacket</tt> belongs to.
     *
     * @return an array representing a map binding CSRC IDs to audio levels as
     * reported by the remote party that sent this packet. The entries of the
     * map are contained in consecutive elements of the returned array where
     * elements at even indices stand for CSRC IDs and elements at odd indices
     * stand for the associated audio levels
     */
    public long[] extractCsrcAudioLevels(byte csrcExtID)
    {
        if (!getExtensionBit() || (getExtensionLength() == 0))
            return null;

        int csrcCount = getCsrcCount();

        if (csrcCount == 0)
            return null;

        /*
         * XXX The guideline which is also supported by Google and recommended
         * for Android is that single-dimensional arrays should be preferred to
         * multi-dimensional arrays in Java because the former take less space
         * than the latter and are thus more efficient in terms of memory and
         * garbage collection.
         */
        long[] csrcLevels = new long[csrcCount * 2];

        //first extract the csrc IDs
        for (int i = 0, csrcStartIndex = offset + FIXED_HEADER_SIZE;
                i < csrcCount;
                i++, csrcStartIndex += 4)
        {
            int csrcLevelsIndex = 2 * i;

            csrcLevels[csrcLevelsIndex] = readUint32AsLong(csrcStartIndex);
            /*
             * The audio levels generated by Jitsi are not in accord with the
             * respective specification, they are backwards with respect to the
             * value domain. Which means that the audio level generated from a
             * muted audio source is 0/zero.
             */
            csrcLevels[csrcLevelsIndex + 1]
                = getCsrcAudioLevel(csrcExtID, i, (byte) 0);
        }

        return csrcLevels;
    }

    /**
     * Returns the list of CSRC IDs, currently encapsulated in this packet.
     *
     * @return an array containing the list of CSRC IDs, currently encapsulated
     * in this packet.
     */
    public long[] extractCsrcList()
    {
        int csrcCount = getCsrcCount();
        long[] csrcList = new long[csrcCount];

        for (int i = 0, csrcStartIndex = offset + FIXED_HEADER_SIZE;
                i < csrcCount;
                i++, csrcStartIndex += 4)
        {
            csrcList[i] = readInt(csrcStartIndex);
        }

        return csrcList;
    }

    /**
     * Extracts the source audio level reported by the remote party which sent
     * this packet and carried in this packet.
     *
     * @param ssrcExtID the ID of the extension that's transporting ssrc audio
     * levels in the session that this <tt>NewRawPacket</tt> belongs to
     * @return the source audio level reported by the remote party which sent
     * this packet and carried in this packet or a negative value if this packet
     * contains no extension such as the specified by <tt>ssrcExtID</tt>
     */
    public byte extractSsrcAudioLevel(byte ssrcExtID)
    {
        /*
         * The method getCsrcAudioLevel(byte, int) is implemented with the
         * awareness that there may be a flag bit V with a value other than 0.
         */
        /*
         * The audio levels sent by Google Chrome are in accord with the
         * specification i.e. the audio level generated from a muted audio
         * source is 127 and the values are non-negative. If there is no source
         * audio level in this packet, return a negative value.
         */
        return getCsrcAudioLevel(ssrcExtID, 0, Byte.MIN_VALUE);
    }

    /**
     * Returns the index of the element in this packet's buffer where the
     * content of the header with the specified <tt>extensionID</tt> starts.
     *
     * @param extensionID the ID of the extension whose content we are looking
     * for.
     *
     * @return the index of the first byte of the content of the extension
     * with the specified <tt>extensionID</tt> or -1 if no such extension was
     * found.
     */
    private int findExtension(int extensionID)
    {
        if( !getExtensionBit() || getExtensionLength() == 0)
            return 0;

        int extOffset = offset + FIXED_HEADER_SIZE
                + getCsrcCount()*4 + EXT_HEADER_SIZE;

        int extensionEnd = extOffset + getExtensionLength();
        int extHdrLen = getExtensionHeaderLength();

        if (extHdrLen != 1 && extHdrLen != 2)
        {
            return -1;
        }

        while (extOffset < extensionEnd)
        {
            int currType = -1;
            int currLen = -1;

            if(extHdrLen == 1)
            {
                //short header. type is in the lefter 4 bits and length is on
                //the right; like this:
                //      0
                //      0 1 2 3 4 5 6 7
                //      +-+-+-+-+-+-+-+-+
                //      |  ID   |  len  |
                //      +-+-+-+-+-+-+-+-+

                currType = buffer[extOffset] >> 4;
                currLen = (buffer[extOffset] & 0x0F) + 1; //add one as per 5285

                //now skip the header
                extOffset ++;
            }
            else
            {
                //long header. type is in the first byte and length is in the
                //second
                //       0                   1
                //       0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5
                //      +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
                //      |       ID      |     length    |
                //      +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+

                currType = buffer[extOffset];
                currLen = buffer[extOffset + 1];

                //now skip the header
                extOffset += 2;
            }

            if(currType == extensionID)
            {
                return extOffset;
            }

            extOffset += currLen;
        }

        return -1;
    }

    /**
     * Get buffer containing the content of this packet
     *
     * @return buffer containing the content of this packet
     */
    @Override
    public byte[] getBuffer()
    {
        return this.buffer;
    }

    /**
     * Returns the CSRC level at the specified index or <tt>defaultValue</tt>
     * if there was no level at that index.
     *
     * @param csrcExtID the ID of the extension that's transporting csrc audio
     * levels in the session that this <tt>NewRawPacket</tt> belongs to.
     * @param index the sequence number of the CSRC audio level extension to
     * return.
     *
     * @return the CSRC audio level at the specified index of the csrc audio
     * level option or <tt>0</tt> if there was no level at that index.
     */
    private byte getCsrcAudioLevel(byte csrcExtID, int index, byte defaultValue)
    {
        byte level = defaultValue;

        try
        {
            if (getExtensionBit() && getExtensionLength() != 0)
            {
                int levelsStart = findExtension(csrcExtID);

                if (levelsStart != -1)
                {
                    int levelsCount = getLengthForExtension(levelsStart);

                    if (levelsCount < index)
                    {
                        //apparently the remote side sent more CSRCs than levels.

                        // ... yeah remote sides do that now and then ...
                    }
                    else
                    {
                        level = (byte) (0x7F & buffer[levelsStart + index]);
                    }
                }
            }
        }
        catch (ArrayIndexOutOfBoundsException e)
        {
            // While ideally we should check the bounds everywhere and not
            // attempt to access the packet's buffer at invalid indexes, there
            // are too many places where it could inadvertently happen. It's
            // safer to return the default value than to risk killing a thread
            // which may not expect this.
            level = defaultValue;
        }

        return level;
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
        if (FIXED_HEADER_SIZE + cc * 4 > length)
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
     * Returns the length of the extension header being used in this packet or
     * <tt>-1</tt> in case there were no extension headers here or we didn't
     * understand the kind of extension being used.
     *
     * @return  the length of the extension header being used in this packet or
     * <tt>-1</tt> in case there were no extension headers here or we didn't
     * understand the kind of extension being used.
     */
    private int getExtensionHeaderLength()
    {
        if (!getExtensionBit())
            return -1;

        //the type of the extension header comes right after the RTP header and
        //the CSRC list.
        int extLenIndex =  offset + FIXED_HEADER_SIZE + getCsrcCount()*4;

        //0xBEDE means short extension header.
        if (buffer[extLenIndex] == (byte)0xBE
            && buffer[extLenIndex + 1] == (byte)0xDE)
                return 1;

        //0x100 means a two-byte extension header.
        if (buffer[extLenIndex]== (byte)0x10
            && (buffer[extLenIndex + 1] >> 4)== 0)
                return 2;

        return -1;
    }

    /**
     * Returns the length of the extensions currently added to this packet.
     *
     * @return the length of the extensions currently added to this packet.
     */
    public int getExtensionLength()
    {
        return getExtensionLength(buffer, offset, length);
    }

    /**
     * @return the iterator over this {@link NewRawPacket}'s RTP header extensions.
     */
    public HeaderExtensions getHeaderExtensions()
    {
        if (headerExtensions == null)
        {
            headerExtensions = new HeaderExtensions();
        }
        headerExtensions.reset();
        return headerExtensions;
    }

    /**
     * Returns the length of the extensions currently added to this packet.
     *
     * @param buffer
     * @param offset
     * @param length
     * @return the length of the extensions currently added to this packet.
     */
    public static int getExtensionLength(byte[] buffer, int offset, int length)
    {
        if (!getExtensionBit(buffer, offset, length))
            return 0;

        // TODO should we verify the "defined by profile" field here (0xBEDE)?

        // The extension length comes after the RTP header, the CSRC list, and
        // two bytes in the extension header called "defined by profile".
        int extLenIndex = offset + FIXED_HEADER_SIZE
            + getCsrcCount(buffer, offset, length) * 4 + 2;

        int len
            = ((buffer[extLenIndex] << 8) | (buffer[extLenIndex + 1] & 0xFF))
                * 4;

        if (len < 0 || len > (length - FIXED_HEADER_SIZE - EXT_HEADER_SIZE -
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
     * Gets the bitmap/flag mask that specifies the set of boolean attributes
     * enabled for this <tt>NewRawPacket</tt>.
     *
     * @return the bitmap/flag mask that specifies the set of boolean attributes
     * enabled for this <tt>NewRawPacket</tt>
     */
    public int getFlags()
    {
        return flags;
    }

    /**
     * Return the define by profile part of the extension header.
     * @return the starting two bytes of extension header.
     */
    public int getHeaderExtensionType()
    {
        if (!getExtensionBit())
            return 0;

        return
            readUint16AsInt(offset + FIXED_HEADER_SIZE + getCsrcCount() * 4);
    }

    /**
     * Get RTP header length from a RTP packet
     *
     * @return RTP header length from source RTP packet
     */
    public int getHeaderLength()
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
    public static int getHeaderLength(byte[] buffer, int offset, int length)
    {
        int headerLength
            = FIXED_HEADER_SIZE + 4 * getCsrcCount(buffer, offset, length);

        // Make sure that the header length doesn't exceed the packet length.
        if (headerLength > length)
        {
            headerLength = length;
        }

        if (getExtensionBit(buffer, offset, length))
        {
            // Make sure that the header length doesn't exceed the packet
            // length.
            if (headerLength + EXT_HEADER_SIZE <= length)
            {
                headerLength += EXT_HEADER_SIZE
                    + getExtensionLength(buffer, offset, length);
            }
        }

        return headerLength;
    }

    /**
     * Get the length of this packet's data
     *
     * @return length of this packet's data
     */
    @Override
    public int getLength()
    {
        return length;
    }

    /**
     * Returns the length of the header extension that is carrying the content
     * starting at <tt>contentStart</tt>. In other words this method checks the
     * size of extension headers in this packet and then either returns the
     * value of the byte right before <tt>contentStart</tt> or its lower 4 bits.
     * This is a very basic method so if you are using it - make sure u know
     * what you are doing.
     *
     * @param contentStart the index of the first element of the content of
     * the extension whose size we are trying to obtain.
     *
     * @return the length of the extension carrying the content starting at
     * <tt>contentStart</tt>.
     */
    private int getLengthForExtension(int contentStart)
    {
        int hdrLen = getExtensionHeaderLength();

        if( hdrLen == 1 )
            return (buffer[contentStart - 1] & 0x0F) + 1;
        else
            return buffer[contentStart - 1];
    }

    /**
     * Get the start offset of this packet's data inside storing buffer
     *
     * @return start offset of this packet's data inside storing buffer
     */
    @Override
    public int getOffset()
    {
        return this.offset;
    }

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
     * Get the RTP payload (bytes) of this RTP packet.
     *
     * @return an array of <tt>byte</tt>s which represents the RTP payload of
     * this RTP packet
     */
    public byte[] getPayload()
    {
        // FIXME The payload includes the padding at the end. Do we really want
        // it though? We are currently keeping the implementation as it is for
        // compatibility with existing code.
        return readRegion(getHeaderLength(), getPayloadLength());
    }

    /**
     * Get RTP payload length from a RTP packet
     *
     * @return RTP payload length from source RTP packet
     */
    public int getPayloadLength(boolean removePadding)
    {
        return getPayloadLength(buffer, offset, length, removePadding);
    }

    /**
     * Get RTP payload length from a RTP packet
     *
     * @return RTP payload length from source RTP packet
     */
    public int getPayloadLength()
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
    public static int getPayloadLength(byte[] buffer, int offset, int length)
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
    public static int getPayloadLength(
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
    public int getPayloadOffset()
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
    public static int getPayloadOffset(byte[] buffer, int offset, int length)
    {
        return offset + getHeaderLength(buffer, offset, length);
    }

    /**
     * Get RTCP SSRC from a RTCP packet
     *
     * @return RTP SSRC from source RTP packet
     *
     * @deprecated Use RtcpPacket
     */
    public long getRTCPSSRC()
    {
        if (!looksLikeRtcp())
        {
            return -1;
        }

        return RtcpHeader.Companion.getSenderSsrc(buffer, offset);
    }

    /**
     * Gets the packet type of this RTCP packet.
     *
     * @return the packet type of this RTCP packet.
     */
    public int getRTCPPacketType()
    {
        return 0xff & buffer[offset + 1];
    }

    /**
     * Set the RTP timestamp for an RTP buffer.
     *
     * @param buf the <tt>byte</tt> array that holds the RTP packet.
     * @param off the offset in <tt>buffer</tt> at which the actual RTP data
     * begins.
     * @param len the number of <tt>byte</tt>s in <tt>buffer</tt> which
     * constitute the actual RTP data.
     * @param ts the timestamp to set in the RTP buffer.
     */
    public static void setTimestamp(byte[] buf, int off, int len, long ts)
    {
        RTPUtils.writeInt(buf, off + 4, (int) ts);
    }

    /**
     * Sets the RTP timestamp of an RTP packet.
     *
     * param baaf the {@link ByteArrayBuffer} that contains the RTP packet.
     * @param ts the timestamp to set in the RTP packet.
     */
    public static void setTimestamp(ByteArrayBuffer baf, long ts)
    {
        if (baf == null)
        {
            return;
        }

        setTimestamp(baf.getBuffer(), baf.getOffset(), baf.getLength(), ts);
    }

    /**
     * Get SRTCP sequence number from a SRTCP packet
     *
     * @param authTagLen authentication tag length
     * @return SRTCP sequence num from source packet
     */
    public int getSRTCPIndex(int authTagLen)
    {
        int offset = getLength() - (4 + authTagLen);
        return readInt(offset);
    }

    /**
     * Grows the internal buffer of this {@code NewRawPacket}.
     *
     * This will change the data buffer of this packet but not the length of the
     * valid data. Use this to grow the internal buffer to avoid buffer
     * re-allocations when appending data.
     *
     * @param howMuch the number of bytes by which this {@code NewRawPacket} is to
     * grow
     */
    public void grow(int howMuch) {
        if (howMuch < 0)
            throw new IllegalArgumentException("howMuch");

        int newLength = length + howMuch;

        if (newLength > buffer.length - offset) {
            byte[] newBuffer = BufferPool.Companion.getGetArray().invoke(newLength);

            System.arraycopy(buffer, offset, newBuffer, 0, length);
            offset = 0;
            byte[] oldBuffer = buffer;
            setBuffer(newBuffer);
            BufferPool.Companion.getReturnArray().invoke(oldBuffer);
        }
    }

    /**
     * Read a byte from this packet at specified offset
     *
     * @param off start offset of the byte
     * @return byte at offset
     */
    public byte readByte(int off)
    {
        return buffer[offset + off];
    }

    /**
     * Read a integer from this packet at specified offset
     *
     * @param off start offset of the integer to be read
     * @return the integer to be read
     */
    public int readInt(int off)
    {
        return RTPUtils.readInt(buffer, offset + off);
    }

    /**
     * Read a 32-bit unsigned integer from this packet at the specified offset.
     *
     * @param off start offset of the integer to be read.
     * @return the integer to be read
     */
    public long readUint32AsLong(int off)
    {
        return RTPUtils.readUint32AsLong(buffer, offset + off);
    }


    /**
     * Read a byte region from specified offset with specified length
     *
     * @param off start offset of the region to be read
     * @param len length of the region to be read
     * @return byte array of [offset, offset + length)
     */
    public byte[] readRegion(int off, int len)
    {
        int startOffset = this.offset + off;
        if (off < 0 || len <= 0 || startOffset + len > this.buffer.length)
            return null;

        byte[] region = new byte[len];

        System.arraycopy(this.buffer, startOffset, region, 0, len);

        return region;
    }

    /**
     * Read a byte region from specified offset with specified length in given
     * buffer
     *
     * @param off start offset of the region to be read
     * @param len length of the region to be read
     * @param outBuff output buffer
     */
    public void readRegionToBuff(int off, int len, byte[] outBuff)
    {
        int startOffset = this.offset + off;
        if (off < 0 || len <= 0 || startOffset + len > this.buffer.length)
            return;

        if (outBuff.length < len)
            return;

        System.arraycopy(this.buffer, startOffset, outBuff, 0, len);
    }

    /**
     * Write a short to this packet at the specified offset.
     *
     * @param off
     * @param val
     */
    public void writeShort(int off, short val)
    {
        RTPUtils.writeShort(buffer, offset + off, val);
    }

    /**
     * Read an unsigned short at specified offset as a int
     *
     * @param off start offset of the unsigned short
     * @return the int value of the unsigned short at offset
     */
    public int readUint16AsInt(int off)
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

        int extHeaderLen = getExtensionLength() + EXT_HEADER_SIZE;

        System.arraycopy(buffer, payloadOffset,
            buffer, payloadOffset - extHeaderLen, getPayloadLength());

        this.length -= extHeaderLen;

        setExtensionBit(false);
    }

    /**
     * @param buffer the buffer to set
     */
    public void setBuffer(byte[] buffer)
    {
        this.buffer = buffer;
        headerExtensions = new HeaderExtensions();
    }

    /**
     * Replaces the existing CSRC list (even if empty) with <tt>newCsrcList</tt>
     * and updates the CC (CSRC count) field of this <tt>NewRawPacket</tt>
     * accordingly.
     *
     * @param newCsrcList the list of CSRC identifiers that we'd like to set for
     * this <tt>NewRawPacket</tt>.
     */
    public void setCsrcList(long[] newCsrcList)
    {
        int newCsrcCount = newCsrcList.length;
        byte[] csrcBuff = new byte[newCsrcCount * 4];
        int csrcOffset = 0;

        for(int i = 0; i < newCsrcList.length; i++)
        {
            long csrc = newCsrcList[i];

            RTPUtils.writeInt(csrcBuff, csrcOffset, (int) csrc);
            csrcOffset += 4;
        }

        int oldCsrcCount = getCsrcCount();

        byte[] oldBuffer = this.getBuffer();

        //the new buffer needs to be bigger than the new one in order to
        //accommodate the list of CSRC IDs (unless there were more of them
        //previously than after setting the new list).
        byte[] newBuffer
            = new byte[length + offset + csrcBuff.length - oldCsrcCount*4];

        //copy the part up to the CSRC list
        System.arraycopy(
                    oldBuffer, 0, newBuffer, 0, offset + FIXED_HEADER_SIZE);

        //copy the new CSRC list
        System.arraycopy( csrcBuff, 0, newBuffer,
                        offset + FIXED_HEADER_SIZE, csrcBuff.length);

        //now copy the payload from the old buff and make sure we don't copy
        //the CSRC list if there was one in the old packet
        int payloadOffsetForOldBuff
            = offset + FIXED_HEADER_SIZE + oldCsrcCount*4;

        int payloadOffsetForNewBuff
            = offset + FIXED_HEADER_SIZE + newCsrcCount*4;

        System.arraycopy( oldBuffer, payloadOffsetForOldBuff,
                          newBuffer, payloadOffsetForNewBuff,
                          length - payloadOffsetForOldBuff);

        //set the new CSRC count
        newBuffer[offset] = (byte)((newBuffer[offset] & 0xF0)
                                    | newCsrcCount);

        setBuffer(newBuffer);
        this.length = payloadOffsetForNewBuff + length
                - payloadOffsetForOldBuff - offset;
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
     * Sets the bitmap/flag mask that specifies the set of boolean attributes
     * enabled for this <tt>NewRawPacket</tt>.
     *
     * @param flags the bitmap/flag mask that specifies the set of boolean
     * attributes enabled for this <tt>NewRawPacket</tt>
     */
    public void setFlags(int flags)
    {
        this.flags = flags;
    }

    /**
     * @param length the length to set
     */
    @Override
    public void setLength(int length)
    {
        this.length = length;
    }

    /**
     * @param offset the offset to set
     */
    @Override
    public void setOffset(int offset)
    {
        this.offset = offset;
    }

    /**
     * Shrink the buffer of this packet by specified length
     *
     * @param len length to shrink
     */
    public void shrink(int len)
    {
        if (len <= 0)
            return;

        this.length -= len;
        if (this.length < 0)
            this.length = 0;
    }

    public String toHex()
    {
        return RTPUtils.toHexString(buffer, offset, length);
    }

    /**
     * Write a byte to this packet at specified offset
     *
     * @param off start offset of the byte
     * @param b byte to write
     */
    public void writeByte(int off, byte b)
    {
        buffer[offset + off] = b;
    }

    /**
     * Set an integer at specified offset in network order.
     *
     * @param off Offset into the buffer
     * @param data The integer to store in the packet
     */
    public void writeInt(int off, int data)
    {
        RTPUtils.writeInt(buffer, offset + off, data);
    }

    /**
     * Gets the OSN value of an RTX packet.
     *
     * @return the OSN value of an RTX packet.
     */
    public int getOriginalSequenceNumber()
    {
        return RTPUtils.readUint16AsInt(buffer, offset + getHeaderLength());
    }

    /**
     * Sets the OSN value of an RTX packet.
     *
     * @param sequenceNumber the new OSN value of this RTX packet.
     */
    public void setOriginalSequenceNumber(int sequenceNumber)
    {
        writeShort(getHeaderLength(), (short) sequenceNumber);
    }

    /**
     * Sets the padding length for this RTP packet.
     *
     * @param len the padding length.
     * @return the number of bytes that were written, or -1 in case of an error.
     */
    public boolean setPaddingSize(int len)
    {
        if (buffer == null || buffer.length < offset + FIXED_HEADER_SIZE + len
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

    /**
     * @return the header extension of this {@link NewRawPacket} with the given ID,
     * or null if the packet doesn't have one.
     * WARNING: This method should not be used while iterating over the
     * extensions with {@link #getHeaderExtensions()}, because it uses the same
     * iterator.
     * @param id
     */
    public HeaderExtension getHeaderExtension(byte id)
    {
        HeaderExtensions hes = getHeaderExtensions();
        while (hes.hasNext())
        {
            HeaderExtension he = hes.next();
            if (he.getExtId() == id)
            {
                return he;
            }
        }
        return null;
    }

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
                        + FIXED_HEADER_SIZE
                        + getCsrcCount(buffer, offset, length) * 4
                        + EXT_HEADER_SIZE;
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
     * Hex characters for converting bytes to readable hex strings
     */
    private final static char[] HEXES = new char[]
            {
                    '0', '1', '2', '3', '4', '5', '6', '7', '8',
                    '9', 'A', 'B', 'C', 'D', 'E', 'F'
            };
    /**
     * Returns the delta between two RTP sequence numbers, taking into account
     * rollover.  This will return the 'shortest' delta between the two
     * sequence numbers in the form of the number you'd add to b to get a. e.g.:
     * getSequenceNumberDelta(1, 10) -> -9 (10 + -9 = 1)
     * getSequenceNumberDelta(1, 65530) -> 7 (65530 + 7 = 1)
     * @return the delta between two RTP sequence numbers (modulo 2^16).
     */
    public static int getSequenceNumberDelta(int a, int b)
    {
        int diff = a - b;

        if (diff < -(1<<15))
        {
            diff += 1 << 16;
        }
        else if (diff > 1<<15)
        {
            diff -= 1 << 16;
        }

        return diff;
    }

    /**
     * Returns whether or not seqNumOne is 'older' than seqNumTwo, taking
     * rollover into account
     * @param seqNumOne
     * @param seqNumTwo
     * @return true if seqNumOne is 'older' than seqNumTwo
     */
    public static boolean isOlderSequenceNumberThan(int seqNumOne, int seqNumTwo)
    {
        return getSequenceNumberDelta(seqNumOne, seqNumTwo) < 0;
    }

    /**
     * Returns result of the subtraction of one RTP sequence number from another
     * (modulo 2^16).
     * @return result of the subtraction of one RTP sequence number from another
     * (modulo 2^16).
     */
    public static int subtractNumber(int a, int b)
    {
        return as16Bits(a - b);
    }


    /**
     * Apply a delta to a given sequence number and return the result (taking
     * rollover into account)
     * @param startingSequenceNumber the starting sequence number
     * @param delta the delta to be applied
     * @return the sequence number result from doing
     * startingSequenceNumber + delta
     */
    public static int applySequenceNumberDelta(
            int startingSequenceNumber, int delta)
    {
        return (startingSequenceNumber + delta) & 0xFFFF;
    }

    /**
     * Set an integer at specified offset in network order.
     *
     * @param off Offset into the buffer
     * @param data The integer to store in the packet
     */
    public static int writeInt(byte[] buf, int off, int data)
    {
        if (buf == null || buf.length < off + 4)
        {
            return -1;
        }

        buf[off++] = (byte)(data>>24);
        buf[off++] = (byte)(data>>16);
        buf[off++] = (byte)(data>>8);
        buf[off] = (byte)data;
        return 4;
    }

    /**
     * Writes the least significant 24 bits from the given integer into the
     * given byte array at the given offset.
     * @param buf the buffer into which to write.
     * @param off the offset at which to write.
     * @param data the integer to write.
     * @return 3
     */
    public static int writeUint24(byte[] buf, int off, int data)
    {
        if (buf == null || buf.length < off + 3)
        {
            return -1;
        }

        buf[off++] = (byte)(data>>16);
        buf[off++] = (byte)(data>>8);
        buf[off] = (byte)data;
        return 3;
    }

    /**
     * Set an integer at specified offset in network order.
     *
     * @param off Offset into the buffer
     * @param data The integer to store in the packet
     */
    public static int writeShort(byte[] buf, int off, short data)
    {
        buf[off++] = (byte)(data>>8);
        buf[off] = (byte)data;
        return 2;
    }

    /**
     * Read an integer from a buffer at a specified offset.
     *
     * @param buf the buffer.
     * @param off start offset of the integer to be read.
     */
    public static int readInt(byte[] buf, int off)
    {
        return
                ((buf[off++] & 0xFF) << 24)
                        | ((buf[off++] & 0xFF) << 16)
                        | ((buf[off++] & 0xFF) << 8)
                        | (buf[off] & 0xFF);
    }

    /**
     * Reads a 32-bit unsigned integer from the given buffer at the given
     * offset and returns its {@link long} representation.
     * @param buf the buffer.
     * @param off start offset of the integer to be read.
     */
    public static long readUint32AsLong(byte[] buf, int off)
    {
        return readInt(buf, off) & 0xFFFF_FFFFL;
    }

    /**
     * Read an unsigned short at a specified offset as an int.
     *
     * @param buf the buffer from which to read.
     * @param off start offset of the unsigned short
     * @return the int value of the unsigned short at offset
     */
    public static int readUint16AsInt(byte[] buf, int off)
    {
        int b1 = (0xFF & (buf[off + 0]));
        int b2 = (0xFF & (buf[off + 1]));
        int val = b1 << 8 | b2;
        return val;
    }

    /**
     * Read a signed short at a specified offset as an int.
     *
     * @param buf the buffer from which to read.
     * @param off start offset of the unsigned short
     * @return the int value of the unsigned short at offset
     */
    public static int readInt16AsInt(byte[] buf, int off)
    {
        int ret = ((0xFF & (buf[off])) << 8)
                | (0xFF & (buf[off + 1]));
        if ((ret & 0x8000) != 0)
        {
            ret = ret | 0xFFFF_0000;
        }

        return ret;
    }

    /**
     * Read an unsigned short at specified offset as a int
     *
     * @param buf
     * @param off start offset of the unsigned short
     * @return the int value of the unsigned short at offset
     */
    public static int readUint24AsInt(byte[] buf, int off)
    {
        int b1 = (0xFF & (buf[off + 0]));
        int b2 = (0xFF & (buf[off + 1]));
        int b3 = (0xFF & (buf[off + 2]));
        return b1 << 16 | b2 << 8 | b3;
    }

    /**
     * Returns the given integer masked to 16 bits
     * @param value the integer to mask
     * @return the value, masked to only keep the lower
     * 16 bits
     */
    public static int as16Bits(int value)
    {
        return value & 0xFFFF;
    }

    /**
     * Returns the given integer masked to 32 bits
     * @param value the integer to mask
     * @return the value, masked to only keep the lower
     * 32 bits
     */
    public static long as32Bits(long value)
    {
        return value & 0xFFFF_FFFFL;
    }

    /**
     * A {@link Comparator} implementation for unsigned 16-bit {@link Integer}s.
     * Compares {@code a} and {@code b} inside the [0, 2^16] ring;
     * {@code a} is considered smaller than {@code b} if it takes a smaller
     * number to reach from {@code a} to {@code b} than the other way round.
     *
     * IMPORTANT: This is a valid {@link Comparator} implementation only when
     * used for subsets of [0, 2^16) which don't span more than 2^15 elements.
     *
     * E.g. it works for: [0, 2^15-1] and ([50000, 2^16) u [0, 10000])
     * Doesn't work for: [0, 2^15] and ([0, 2^15-1] u {2^16-1}) and [0, 2^16)
     */
    public static final Comparator<? super Integer> sequenceNumberComparator
            = new Comparator<Integer>() {
        @Override
        public int compare(Integer a, Integer b)
        {
            if (a.equals(b))
            {
                return 0;
            }
            else if (a > b)
            {
                if (a - b < 0x10000)
                {
                    return 1;
                }
                else
                {
                    return -1;
                }
            }
            else //a < b
            {
                if (b - a < 0x10000)
                {
                    return -1;
                }
                else
                {
                    return 1;
                }
            }
        }
    };

    /**
     * Returns the difference between two RTP timestamps.
     * @return the difference between two RTP timestamps.
     */
    public static long rtpTimestampDiff(long a, long b)
    {
        long diff = a - b;
        if (diff < -(1L<<31))
        {
            diff += 1L << 32;
        }
        else if (diff > 1L<<31)
        {
            diff -= 1L << 32;
        }

        return diff;
    }

    /**
     * Returns whether or not the first given timestamp is newer than the second
     * @param a
     * @param b
     * @return true if a is newer than b, false otherwise
     */
    public static boolean isNewerTimestampThan(long a, long b)
    {
        return rtpTimestampDiff(a, b) > 0;
    }

    /**
     * Return a string containing the hex string version of the given byte
     * @param b
     * @return
     */
    private static String toHexString(byte b)
    {

        StringBuilder hexStringBuilder = new StringBuilder(2);

        hexStringBuilder.append(HEXES[(b & 0xF0) >> 4]);
        hexStringBuilder.append(HEXES[b & 0x0F]);

        return hexStringBuilder.toString();
    }

    /**
     * Return a string containing the hex string version of the given bytes
     * @param buf
     * @return
     */
    public static String toHexString(byte[] buf)
    {
        return toHexString(buf, 0, buf.length);
    }

    /**
     * Return a string containing the hex string version of the given byte
     * @param buf
     * @param off
     * @param len
     * @return
     */
    public static String toHexString(byte[] buf, int off, int len)
    {
        if (buf == null)
        {
            return null;
        }
        else
        {
            StringBuilder hexStringBuilder
                    = new StringBuilder(2 * buf.length);

            for (int i = 0; i < len; i++)
            {
                if (i % 16 == 0)
                {
                    hexStringBuilder.append("\n")
                            .append(toHexString((byte)i))
                            .append("  ");
                }
                else if (i % 8 == 0)
                {
                    hexStringBuilder.append(" ");
                }
                byte b = buf[off + i];

                hexStringBuilder.append(toHexString(b));
                hexStringBuilder.append(" ");
            }
            return hexStringBuilder.toString();
        }
    }
}
