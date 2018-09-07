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
package org.jitsi.impl.neomedia.transform;

import org.jitsi.impl.neomedia.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.util.*;

/**
 * Implements a <tt>TransformEngine</tt> which replaces the timestamps in
 * abs-send-time RTP extensions with timestamps generated locally.
 *
 * See http://www.webrtc.org/experiments/rtp-hdrext/abs-send-time
 *
 * @author Boris Grozev
 */
public class AbsSendTimeEngine
    extends SinglePacketTransformerAdapter
    implements TransformEngine
{
    /**
     * One billion.
     */
    private static final int b = 1_000_000_000;

    /**
     * The length of the data in the abs-send-time extension (see the draft).
     */
    private static final int EXT_LENGTH = 3;

    /**
     * The <tt>Logger</tt> used by the {@link AbsSendTimeEngine} class and its
     * instances.
     */
//    private static final Logger logger
//        = Logger.getLogger(AbsSendTimeEngine.class);

    /**
     * The ID of the abs-send-time RTP header extension.
     */
    private int extensionID = -1;

    /**
     * Initializes a new {@link AbsSendTimeEngine} instance.
     */
    public AbsSendTimeEngine()
    {
        super(RTPPacketPredicate.INSTANCE);
    }

    /**
     * Implements {@link SinglePacketTransformer#transform(RawPacket)}.
     */
    @Override
    public RawPacket transform(RawPacket pkt)
    {
        if (extensionID != -1)
        {
            RawPacket.HeaderExtension ext
                = pkt.getHeaderExtension((byte) extensionID);
            if (ext == null)
            {
                ext = pkt.addExtension((byte) extensionID, EXT_LENGTH);
            }

            setTimestamp(ext.getBuffer(), ext.getOffset() + 1);
        }
        return pkt;
    }

    /**
     * Implements {@link TransformEngine#getRTPTransformer()}.
     */
    @Override
    public PacketTransformer getRTPTransformer()
    {
        return this;
    }

    /**
     * Implements {@link TransformEngine#getRTCPTransformer()}.
     *
     * This <tt>TransformEngine</tt> does not transform RTCP packets.
     *
     */
    @Override
    public PacketTransformer getRTCPTransformer()
    {
        return null;
    }

    /**
     * Sets the 3 bytes at offset <tt>off</tt> in <tt>buf</tt> to the value of
     * {@link System#nanoTime()} converted to the fixed point (6.18) format
     * specified in
     * {@link "http://www.webrtc.org/experiments/rtp-hdrext/abs-send-time"}.
     *
     * @param buf the buffer where to write the timestamp.
     * @param off the offset at which to write the timestamp.
     */
    private void setTimestamp(byte[] buf, int off)
    {
        long ns = System.nanoTime();
        int fraction = (int) ( (ns % b) * (1 << 18) / b );
        int seconds = (int) ((ns / b) % 64); //6 bits only

        int timestamp = (seconds << 18 | fraction) & 0x00FFFFFF;

        buf[off] = (byte) (timestamp >> 16);
        buf[off+1] = (byte) (timestamp >> 8);
        buf[off+2] = (byte) timestamp;
    }

    /**
     * Sets the ID of the abs-send-time RTP extension. Set to -1 to effectively
     * disable this transformer.
     * @param id the ID to set.
     */
    public void setExtensionID(int id)
    {
        extensionID = id;
    }

    /**
     *   1 2 3 4 5 6 7 8 9 10 11 12 .... 28 29 30 31 32
     *  +-+-+-+-+-+-+-+-+-+-+--+--+-....+--+--+--+--+--+
     *  |  ID   |  LEN  |   AbsSendTime Value          |
     *  +-+-+-+-+-+-+-+-+-+-+--+--+-....+--+--+--+--+--+
     * getAbsSendTime returns the AbsSendTime as a 24bit value
     * @param pkt is a RawPacket
     * @return
     */
    public static long getAbsSendTime(RawPacket pkt, byte extensionID)
    {
        long absSendTime = -1L;

        RawPacket.HeaderExtension header = pkt.getHeaderExtension(extensionID);

        if (header != null)
        {
            //offSet is the byte index to read from
            int offSet = header.getOffset() + 1;
            if (header.getExtLength() == EXT_LENGTH)
            {
                absSendTime
                    = RTPUtils.readUint24AsInt(header.getBuffer(), offSet);
            }
        }

        return absSendTime;
    }
}
