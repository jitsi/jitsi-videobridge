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
package org.jitsi.nlj.rtcp_og;

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

import org.jitsi.service.neomedia.*;
import org.jitsi.util.*;

/**
 * Utility class that contains static methods for RTCP header manipulation.
 *
 * @author George Politis
 */
public class RTCPUtils
{
    /**
     * The values of the Version field for RTCP packets.
     */
    public static int VERSION = 2;

    /**
     * The size in bytes of the smallest possible RTCP packet (e.g. an empty
     * Receiver Report).
     */
    public static int MIN_SIZE = 8;

    /**
     * Gets the RTCP packet type.
     *
     * @param buf the byte buffer that contains the RTCP header.
     * @param off the offset in the byte buffer where the RTCP header starts.
     * @param len the number of bytes in buffer which constitute the actual
     * data.
     * @return the unsigned RTCP packet type, or -1 in case of an error.
     */
    public static int getPacketType(byte[] buf, int off, int len)
    {
        if (!isHeaderValid(buf, off, len))
        {
            return -1;
        }

        return buf[off + 1] & 0xff;
    }

    /**
     * Gets the RTCP packet type.
     *
     * @param baf the {@link ByteArrayBuffer} that contains the RTCP header.
     * @return the unsigned RTCP packet type, or -1 in case of an error.
     */
    public static int getPacketType(ByteArrayBuffer baf)
    {
        if (baf == null)
        {
            return -1;
        }

        return getPacketType(baf.getBuffer(), baf.getOffset(), baf.getLength());
    }


    /**
     * Sets the RTCP sender SSRC.
     *
     * @param buf the byte buffer that contains the RTCP header.
     * @param off the offset in the byte buffer where the RTCP header starts.
     * @param len the number of bytes in buffer which constitute the actual
     * data.
     * @param senderSSRC the sender SSRC to set.
     * @return the number of bytes that were written to the byte buffer, or -1
     * in case of an error.
     */
    private static int setSenderSSRC(
            byte[] buf, int off, int len, int senderSSRC)
    {
        if (!isHeaderValid(buf, off, len))
        {
            return -1;
        }

        return RTPUtils.writeInt(buf, off + 4, senderSSRC);
    }

    /**
     * Gets the RTCP packet length in bytes as specified by the length field
     * of the RTCP packet (does not verify that the buffer is actually large
     * enough).
     *
     * @param buf the byte buffer that contains the RTCP header.
     * @param off the offset in the byte buffer where the RTCP header starts.
     * @param len the number of bytes in buffer which constitute the actual
     * data.
     * @return  the RTCP packet length in bytes, or -1 in case of an error.
     */
    public static int getLength(byte[] buf, int off, int len)
    {
        // XXX Do not check with isHeaderValid.
        if (buf == null || buf.length < off + len || len < 4)
        {
            return -1;
        }

        int lengthInWords
                = ((buf[off + 2] & 0xff) << 8) | (buf[off + 3] & 0xff);

        return (lengthInWords + 1) * 4;
    }

    /**
     * Gets the RTCP packet version.
     *
     * @param buf the byte buffer that contains the RTCP header.
     * @param off the offset in the byte buffer where the RTCP header starts.
     * @param len the number of bytes in buffer which constitute the actual
     * data.
     * @return the RTCP packet version, or -1 in case of an error.
     */
    public static int getVersion(byte[] buf, int off, int len)
    {
        // XXX Do not check with isHeaderValid.
        if (buf == null || buf.length < off + len || len < 1)
        {
            return -1;
        }

        return (buf[off] & 0xc0) >>> 6;
    }

    /**
     * Checks whether the RTCP header is valid or not (note that a valid header
     * does not necessarily imply a valid packet). It does so by checking
     * the RTCP header version and makes sure the buffer is at least 8 bytes
     * long.
     *
     * @param buf the byte buffer that contains the RTCP header.
     * @param off the offset in the byte buffer where the RTCP header starts.
     * @param len the number of bytes in buffer which constitute the actual
     * data.
     * @return true if the RTCP packet is valid, false otherwise.
     */
    public static boolean isHeaderValid(byte[] buf, int off, int len)
    {
        int version = RTCPUtils.getVersion(buf, off, len);
        if (version != VERSION)
        {
            return false;
        }

        int pktLen = RTCPUtils.getLength(buf, off, len);
        if (pktLen < MIN_SIZE)
        {
            return false;
        }

        return true;
    }

    /**
     * Sets the RTCP sender SSRC.
     *
     * @param baf the {@link ByteArrayBuffer} that contains the RTCP header.
     * @param senderSSRC the sender SSRC to set.
     * @return the number of bytes that were written to the byte buffer, or -1
     * in case of an error.
     */
    public static int setSenderSSRC(ByteArrayBuffer baf, int senderSSRC)
    {
        if (baf == null)
        {
            return -1;
        }

        return setSenderSSRC(
                baf.getBuffer(), baf.getOffset(), baf.getLength(), senderSSRC);
    }

    /**
     * Gets the report count field of the RTCP packet specified in the
     * {@link ByteArrayBuffer} that is passed in as a parameter.
     *
     * @param baf the {@link ByteArrayBuffer} that contains the RTCP header.
     * @return the report count field of the RTCP packet specified in the
     * {@link ByteArrayBuffer} that is passed in as a parameter, or -1 in case
     * of an error.
     */
    public static int getReportCount(ByteArrayBuffer baf)
    {
        if (baf == null)
        {
            return -1;
        }

        return getReportCount(
                baf.getBuffer(), baf.getOffset(), baf.getLength());
    }

    /**
     * Gets the report count field of the RTCP packet specified in the
     * {@link ByteArrayBuffer} that is passed in as a parameter.
     *
     * @param buf the byte buffer that contains the RTCP header.
     * @param off the offset in the byte buffer where the RTCP header starts.
     * @param len the number of bytes in buffer which constitute the actual
     * data.
     * @return the report count field of the RTCP packet specified in the
     * byte buffer that is passed in as a parameter, or -1 in case
     * of an error.
     */
    private static int getReportCount(byte[] buf, int off, int len)
    {
        if (buf == null || buf.length < off + len || len < 1)
        {
            return -1;
        }

        return buf[off] & 0x1F;
    }

    /**
     * Gets the RTCP packet length in bytes.
     *
     * @param baf the {@link ByteArrayBuffer} that contains the RTCP header.
     * @return  the RTCP packet length in bytes, or -1 in case of an error.
     */
    public static int getLength(ByteArrayBuffer baf)
    {
        if (baf == null)
        {
            return -1;
        }

        return getLength(baf.getBuffer(), baf.getOffset(), baf.getLength());
    }

    /**
     * Checks whether the buffer described by the parameters looks like an
     * RTCP packet. It only checks the Version and Packet Type fields, as
     * well as a minimum length.
     * This method returning {@code true} does not necessarily mean that the
     * given packet is a valid RTCP packet, but it should be parsed as RTCP
     * (as opposed to as e.g. RTP or STUN).
     *
     * @param buf
     * @param off
     * @param len
     * @return {@code true} if the described packet looks like RTCP.
     */
    public static boolean isRtcp(byte[] buf, int off, int len)
    {
        if (!isHeaderValid(buf, off, len))
        {
            return false;
        }

        int pt = getPacketType(buf, off, len);

        // Other packet types are used for RTP.
        return 200 <= pt && pt <= 211;
    }

}

