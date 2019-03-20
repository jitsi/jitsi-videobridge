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

//TODO documentation
public abstract class ByteArrayBuffer
{
    private static final char[] HEX_CHARS = "0123456789ABCDEF".toCharArray();

    byte[] buffer;

    int offset;

    int length;

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

    public boolean isInvalid()
    {
        return false;
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
