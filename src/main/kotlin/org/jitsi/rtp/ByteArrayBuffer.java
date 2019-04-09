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

import org.jitsi.rtp.util.*;

//TODO documentation
public abstract class ByteArrayBuffer
{
    private static final char[] HEX_CHARS = "0123456789ABCDEF".toCharArray();

    public byte[] buffer;

    public int offset;

    public int length;

    public ByteArrayBuffer(byte[] buffer, int offset, int length)
    {
        this.buffer = buffer;
        this.offset = offset;
        this.length = length;
    }

    public ByteArrayBuffer()
    {
        this.buffer = new byte[0];
        this.offset = 0;
        this.length = 0;
    }

    public byte[] getBuffer()
    {
        return buffer;
    }

    public int getOffset()
    {
        return offset;
    }

    public void setOffset(int offset)
    {
        //TODO check bounds?
        this.offset = offset;
    }

    public int getLength()
    {
        return length;
    }

    public void setLength(int length)
    {
        //TODO check bounds?
        this.length = length;
    }

    public void setOffsetLength(int offset, int length)
    {
        //TODO check bounds?
        this.offset = offset;
        this.length = length;
    }

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
     * Grows the internal buffer of this {@code ByteArrayBuffer}.
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
            throw new IllegalArgumentException("howMuch: " + howMuch);

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

    /**
     * Creates a clone of this buffer. The underlying byte[] has the same size
     * as our byte[], but we only copy the data that this {@link ByteArrayBuffer}
     * represents.
     */
    protected byte[] cloneBuffer()
    {
        byte[] clone = BufferPool.Companion.getGetArray().invoke(buffer.length);
        System.arraycopy(buffer, offset, clone, offset, length);
        return clone;
    }


    /**
     * @param buffer the buffer to set
     */
    public void setBuffer(byte[] buffer)
    {
        this.buffer = buffer;
    }

    public String toHex()
    {
        StringBuilder sb = new StringBuilder();
        int position = 0;

        for (int i = offset; i < (offset + length) && i < buffer.length; ++i)
        {
            int octet = buffer[i];
            int firstIndex = (octet & 0xF0) >> 4;
            int secondIndex = octet & 0x0F;
            sb.append(HEX_CHARS[firstIndex]);
            sb.append(HEX_CHARS[secondIndex]);
            if ((position + 1) % 16 == 0) {
                sb.append("\n");
            } else if ((position + 1) % 4 == 0) {
                sb.append(" ");
            }
            position++;
        }

        return sb.toString();
    }
}
