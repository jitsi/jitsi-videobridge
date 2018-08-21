///*
// * Copyright @ 2018 Atlassian Pty Ltd
// *
// * Licensed under the Apache License, Version 2.0 (the "License");
// * you may not use this file except in compliance with the License.
// * You may obtain a copy of the License at
// *
// *     http://www.apache.org/licenses/LICENSE-2.0
// *
// * Unless required by applicable law or agreed to in writing, software
// * distributed under the License is distributed on an "AS IS" BASIS,
// * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// * See the License for the specific language governing permissions and
// * limitations under the License.
// */
//package org.jitsi.rtp.rtcp_og;
//
///*
// * Copyright @ 2015 Atlassian Pty Ltd
// *
// * Licensed under the Apache License, Version 2.0 (the "License");
// * you may not use this file except in compliance with the License.
// * You may obtain a copy of the License at
// *
// *     http://www.apache.org/licenses/LICENSE-2.0
// *
// * Unless required by applicable law or agreed to in writing, software
// * distributed under the License is distributed on an "AS IS" BASIS,
// * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// * See the License for the specific language governing permissions and
// * limitations under the License.
// */
//
//import java.io.*;
//
//
///**
// * Created by gp on 6/27/14.
// *
// * 0                   1                   2                   3
// * 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
// * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
// * |V=2|P|   FMT   |       PT      |          length               |
// * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
// * |                  SSRC of packet sender                        |
// * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
// * |                  SSRC of media source                         |
// * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
// * :            Feedback Control Information (FCI)                 :
// * :                                                               :
// */
//public class RTCPFBPacket
//        extends RTCPPacket
//{
//    public static final int RTPFB = 205;
//
//    public static final int PSFB = 206;
//
//    public byte[] fci;
//
//    /**
//     * Feedback message type (FMT).
//     */
//    public int fmt;
//
//    /**
//     * SSRC of packet sender.
//     */
//    public long senderSSRC;
//
//    /**
//     * SSRC of media source.
//     */
//    public long sourceSSRC;
//
//    public RTCPFBPacket(int fmt, int type, long senderSSRC, long sourceSSRC)
//    {
//        super.type = type;
//
//        this.fmt = fmt;
//        this.senderSSRC = senderSSRC;
//        this.sourceSSRC = sourceSSRC;
//    }
//
//    public RTCPFBPacket(RTCPCompoundPacket base)
//    {
//        super(base);
//    }
//
//    /**
//     * Gets a boolean that indicates whether or not the packet specified in the
//     * {@link ByteArrayBuffer} that is passed in the first argument is an RTCP
//     * RTPFB or PSFB packet.
//     *
//     * @param baf the {@link ByteArrayBuffer} that holds the RTCP packet.
//     * @return true if the packet specified in the {@link ByteArrayBuffer} that
//     * is passed in the first argument is an RTCP RTPFB or PSFB packet,
//     * otherwise false.
//     */
//    public static boolean isRTCPFBPacket(ByteArrayBuffer baf)
//    {
//        return isRTPFBPacket(baf) || isPSFBPacket(baf);
//    }
//
//    /**
//     * Gets a boolean that indicates whether or not the packet specified in the
//     * {@link ByteArrayBuffer} passed in as an argument is an RTP FB packet.
//     *
//     * @param baf the {@link ByteArrayBuffer} that holds the packet
//     * @return true if the packet specified in the {@link ByteArrayBuffer}
//     * passed in as an argument is an RTP FB packet, otherwise false.
//     */
//    public static boolean isRTPFBPacket(ByteArrayBuffer baf)
//    {
//        int pt = RTCPUtils.getPacketType(baf);
//        return pt == RTPFB;
//    }
//
//    /**
//     * Gets a boolean that indicates whether or not the packet specified in the
//     * {@link ByteArrayBuffer} passed in as an argument is an RTP FB packet.
//     *
//     * @param baf the {@link ByteArrayBuffer} that holds the packet
//     * @return true if the packet specified in the {@link ByteArrayBuffer}
//     * passed in as an argument is an RTP FB packet, otherwise false.
//     */
//    public static boolean isPSFBPacket(ByteArrayBuffer baf)
//    {
//        int pt = RTCPUtils.getPacketType(baf);
//        return pt == PSFB;
//    }
//
//    /**
//     * Gets the SSRC of the media source of the packet specified in the
//     * {@link ByteArrayBuffer} passed in as an argument.
//     *
//     * @param baf the {@link ByteArrayBuffer} that holds the packet
//     * @return the SSRC of the media source of the packet specified in the
//     * {@link ByteArrayBuffer} passed in as an argument, or -1 in case of an
//     * error.
//     */
//    public static long getSourceSSRC(ByteArrayBuffer baf)
//    {
//        if (baf == null || baf.isInvalid())
//        {
//            return -1;
//        }
//
//        return RTPUtils.readUint32AsLong(baf.getBuffer(), baf.getOffset() + 8);
//    }
//
//    /**
//     * Gets the Feedback Control Information (FCI) field of an RTCP FB message.
//     *
//     * @param baf the {@link ByteArrayBuffer} that contains the RTCP message.
//     * @return the Feedback Control Information (FCI) field of an RTCP FB
//     * message.
//     */
//    public static ByteArrayBuffer getFCI(ByteArrayBuffer baf)
//    {
//        if (!isRTCPFBPacket(baf))
//        {
//            return null;
//        }
//
//        int length = RTCPUtils.getLength(baf);
//        if (length < 0)
//        {
//            return null;
//        }
//
//        return new RawPacket(baf.getBuffer(),
//                baf.getOffset() + 12, length - 12);
//    }
//
//    /**
//     * {@inheritDoc}
//     */
//    @Override
//    public void assemble(DataOutputStream dataoutputstream) throws IOException
//    {
//        dataoutputstream.writeByte((byte) (0x80 /* version */ | fmt));
//        dataoutputstream.writeByte((byte) type); // packet type, 205 or 206
//
//        // Full length in bytes, including padding.
//        int len = this.calcLength();
//        dataoutputstream.writeShort(len / 4 - 1);
//        dataoutputstream.writeInt((int) senderSSRC);
//        dataoutputstream.writeInt((int) sourceSSRC);
//        dataoutputstream.write(fci);
//
//        // Pad with zeros. Since the above fields fill in exactly 3 words,
//        // the number of padding bytes will only depend on the length of the
//        // fci field.
//        for (int i = fci.length; i % 4 != 0; i++)
//        {
//            // pad to a word.
//            dataoutputstream.writeByte(0);
//        }
//    }
//
//    /**
//     * {@inheritDoc}
//     */
//    @Override
//    public int calcLength()
//    {
//        int len = 12; // header+ssrc+ssrc
//        if (fci != null && fci.length != 0)
//            len += fci.length;
//
//        // Pad to a word.
//        if (len % 4 != 0)
//        {
//            len += 4 - (len % 4);
//        }
//
//        return len;
//    }
//
//    @Override
//    public String toString()
//    {
//        return "\tRTCP FB packet from sync source " + senderSSRC;
//    }
//
//    /**
//     * @return a {@link RawPacket} representation of this {@link RTCPFBPacket}.
//     * @throws IOException
//     */
//    public RawPacket toRawPacket()
//            throws IOException
//    {
//        return RTCPPacketParserEx.toRawPacket(this);
//    }
//
//    /**
//     * @return  the {@code Sender SSRC} field of this {@code RTCP} feedback
//     * packet.
//     */
//    public long getSenderSSRC()
//    {
//        return senderSSRC;
//    }
//
//    /**
//     * @return  the {@code Source SSRC} field of this {@code RTCP} feedback
//     * packet.
//     */
//    public long getSourceSSRC()
//    {
//        return sourceSSRC;
//    }
//}
//
//
