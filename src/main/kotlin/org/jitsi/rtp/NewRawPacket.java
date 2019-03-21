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
    }

    public NewRawPacket(byte[] buffer)
    {
        super(buffer, 0, buffer.length);
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
     * @param buffer the buffer to set
     */
    @Override
    public void setBuffer(byte[] buffer)
    {
        super.setBuffer(buffer);
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
            .append(']');

        return sb.toString();
    }
}
