/*
 * Copyright @ 2015-2017 Atlassian Pty Ltd
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
package org.jitsi.videobridge.octo;

import org.jitsi.utils.*;

import static org.jitsi.utils.ByteArrayUtils.*;

/**
 * A utility class which handles the on-the-wire Octo format. Octo encapsulates
 * its payload (RTP, RTCP, or anything else) in an 12-byte header:
 * <pre>{@code
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                         Conference ID                         |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                         Endpoint ID                           |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * | M |                     Reserved                              |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * }</pre>
 * <p/>
 * Conference ID: An identifier of the conference.
 * <p/>
 * Endpoint ID: An identifier of the endpoint that is the original source of
 * the packet.
 * <p/>
 * M: media type (audio, video, or data).
 *
 * @author Boris Grozev
 */
public class OctoPacket
{
    /**
     * The fixed length of the Octo header.
     */
    public static final int OCTO_HEADER_LENGTH = 12;

    /**
     * The integer which identifies the "audio" media type in Octo.
     */
    private static final int OCTO_MEDIA_TYPE_AUDIO = 0;

    /**
     * The integer which identifies the "video" media type in Octo.
     */
    private static final int OCTO_MEDIA_TYPE_VIDEO = 1;

    /**
     * The integer which identifies the "data" media type in Octo.
     */
    private static final int OCTO_MEDIA_TYPE_DATA = 2;

    /**
     * @return the integer used to identify the particular {@link MediaType}
     * in Octo.
     */
    private static int getMediaTypeId(MediaType mediaType)
    {
        switch (mediaType)
        {
        case AUDIO:
            return OCTO_MEDIA_TYPE_AUDIO;
        case VIDEO:
            return OCTO_MEDIA_TYPE_VIDEO;
        case DATA:
            return OCTO_MEDIA_TYPE_DATA;
        default:
            return -1;
        }
    }

    /**
     * Writes an Octo header to the specified buffer at the specified offset.
     * @param buf the buffer to write to.
     * @param off the offset to write at.
     * @param mediaType the media type.
     * @param conferenceId the Octo ID of the conference.
     * @param endpointId the Octo ID of the endpoint.
     */
    public static void writeHeaders(
            byte[] buf, int off,
            MediaType mediaType,
            long conferenceId,
            String endpointId)
    {
        assertMinLen(buf, off, buf.length);
        off += writeConferenceId(conferenceId, buf, off);
        off += writeEndpointId(endpointId, buf, off);
        buf[off] = (byte) (getMediaTypeId(mediaType) << 6);
        buf[off+1] = 0;
        buf[off+2] = 0;
        buf[off+3] = 0;
    }

    /**
     * Reads the conference ID from an Octo header.
     * @param buf the buffer which contains the Octo header.
     * @param off the offset in {@code buf} at which the Octo header begins.
     * @param len the length of the buffer.
     * @return the Octo conference ID read from the buffer.
     */
    public static long readConferenceId(byte[] buf, int off, int len)
    {
        assertMinLen(buf, off, len);

        return readUint32(buf, off);
    }

    /**
     * Reads the {@link MediaType} from an Octo header.
     * @param buf the buffer which contains the Octo header.
     * @param off the offset in {@code buf} at which the Octo header begins.
     * @param len the length of the buffer.
     * @return the {@link MediaType} from the given Octo header.
     */
    public static MediaType readMediaType(byte[] buf, int off, int len)
    {
        assertMinLen(buf, off, len);

        int mediaType = (buf[off + 8] & 0xc0) >> 6;
        switch (mediaType)
        {
        case OCTO_MEDIA_TYPE_AUDIO:
            return MediaType.AUDIO;
        case OCTO_MEDIA_TYPE_VIDEO:
            return MediaType.VIDEO;
        case OCTO_MEDIA_TYPE_DATA:
            return MediaType.DATA;
        default:
            throw new IllegalArgumentException("Invalid media type value: " + mediaType);
        }
    }

    /**
     * Reads the endpoint ID from an Octo header.
     * @param buf the buffer which contains the Octo header.
     * @param off the offset in {@code buf} at which the Octo header begins.
     * @param len the length of the buffer.
     * @return the endpoint ID from the given Octo header.
     */
    public static String readEndpointId(byte[] buf, int off, int len)
    {
        assertMinLen(buf, off, len);

        long eid = readUint32(buf, off + 4);
        return String.format("%08x", eid);
    }

    /**
     * Writes the conference ID to an Octo header.
     * @param conferenceId the Octo conference ID represented as a hex string.
     * @param buf the buffer which contains the Octo header.
     * @param off the offset in {@code buf} at which the Octo header begins.
     * @param len the length of the buffer.
     */
    private static int writeConferenceId(
            long conferenceId, byte[] buf, int off)
    {
        writeInt(buf, off, (int) conferenceId);
        return 4;
    }

    /**
     * Writes the endpoint ID to an Octo header.
     * @param endpointId the Octo endpoint ID represented as a hex string.
     * @param buf the buffer which contains the Octo header.
     * @param off the offset in {@code buf} at which the Octo header begins.
     */
    private static int writeEndpointId(String endpointId, byte[] buf, int off)
    {
        long eid = Long.parseLong(endpointId, 16);
        writeInt(buf, off, (int) eid);

        return 4;
    }

    /**
     * Verifies that a given buffer has the minimum length of a valid Octo
     * packet.
     * @param buf the {@code byte[]} which contains the buffer.
     * @param off the offset at which the buffer starts.
     * @param len the length of the buffer.
     */
    private static void assertMinLen(byte[] buf, int off, int len)
    {
        if (!verifyMinLength(buf, off, len, OCTO_HEADER_LENGTH))
        {
            throw new IllegalArgumentException("Invalid Octo packet.");
        }
    }

    /**
     * Checks whether the buffer described by {@code buf}, {@code off} and
     * {@code len} has an effective length of at least {@code minLength}.
     * packet.
     * @param buf the {@code byte[]} which contains the buffer.
     * @param off the offset at which the buffer starts.
     * @param len the length of the buffer.
     *
     * TODO move to a util class.
     */
    private static boolean verifyMinLength(
            byte[] buf, int off, int len, int minLen)
    {
        return buf != null && off >= 0 && len >= minLen && minLen >= 0
            && off + len <= buf.length;
    }
}
