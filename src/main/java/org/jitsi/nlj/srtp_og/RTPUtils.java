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
package org.jitsi.nlj.srtp_og;

/**
 * @author bbaldino
 */
/*
 * Copyright @ 2015 Atlassian Pty Ltd
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

import java.util.*;

/**
 * RTP-related static utility methods.
 * @author Boris Grozev
 */
public class RTPUtils
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
            if (a == b || a.intValue() == b.intValue())
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

