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
package org.jitsi_modified.impl.neomedia.rtp;

import org.jetbrains.annotations.*;
import org.jitsi.nlj.rtp.*;
import org.jitsi.nlj.rtp.codec.vp8.*;
import org.jitsi.utils.*;
import org.jitsi.utils.stats.*;

import java.util.*;
import java.util.concurrent.atomic.*;

/**
 * Keeps track of how many channels receive it, its subjective quality index,
 * its last stable bitrate and other useful things for adaptivity/routing.
 *
 * @author George Politis
 */
public class RTPEncodingDesc
{
    /**
     * The quality that is used to represent that forwarding is suspended.
     */
    public static final int SUSPENDED_INDEX = -1;

    /**
     * A value used to designate the absence of height information.
     */
    private final static int NO_HEIGHT = -1;

    /**
     * A value used to designate the absence of frame rate information.
     */
    private final static double NO_FRAME_RATE = -1;

    /**
     * The default window size in ms for the bitrate estimation.
     *
     * TODO maybe make this configurable.
     */
    private static final int AVERAGE_BITRATE_WINDOW_MS = 5000;

    /**
     * The primary SSRC for this layering/encoding.
     */
    private final long primarySSRC;

    /**
     * The ssrcs associated with this encoding (for example, RTX or FLEXFEC)
     * Maps ssrc -> type {@link Constants} (rtx, etc.)
     */
    private final Map<Long, SsrcAssociationType> secondarySsrcs = new HashMap<>();

    /**
     * The index of this instance in the track encodings array.
     */
    private final int idx;

    /**
     * The temporal layer ID of this instance.
     */
    private final int tid;

    /**
     * The spatial layer ID of this instance.
     */
    private final int sid;

    /**
     * The max height of the bitstream that this instance represents. The actual
     * height may be less due to bad network or system load.
     */
    private final int height;

    /**
     * The max frame rate (in fps) of the bitstream that this instance
     * represents. The actual frame rate may be less due to bad network or
     * system load.
     */
    private final double frameRate;

    /**
     * The root {@link RTPEncodingDesc of the dependencies DAG. Useful for
     * simulcast handling.
     */
    private final RTPEncodingDesc base;

    /**
     * The {@link MediaStreamTrackDesc} that this {@link RTPEncodingDesc
     * belongs to.
     */
    private final MediaStreamTrackDesc track;

    /**
     * The {@link RateStatistics} instance used to calculate the receiving
     * bitrate of this RTP encoding.
     */
    private final RateStatistics rateStatistics
        = new RateStatistics(AVERAGE_BITRATE_WINDOW_MS);

    /**
     * The {@link RTPEncodingDesc on which this layer depends.
     */
    private final RTPEncodingDesc[] dependencyEncodings;

    /**
     * The number of receivers for this encoding.
     */
    private AtomicInteger numOfReceivers = new AtomicInteger();

    /**
     * Ctor.
     *
     * @param track the {@link MediaStreamTrackDesc} that this instance
     * belongs to.
     * @param primarySSRC The primary SSRC for this layering/encoding.
     */
    public RTPEncodingDesc(
            MediaStreamTrackDesc track, long primarySSRC)
    {
        this(track, 0, primarySSRC, -1 /* tid */, -1 /* sid */,
            NO_HEIGHT /* height */, NO_FRAME_RATE /* frame rate */,
            null /* dependencies */);
    }

    /**
     * Ctor.
     *
     * @param track the {@link MediaStreamTrackDesc} that this instance belongs
     * to.
     * @param idx the subjective quality index for this
     * layering/encoding.
     * @param primarySSRC The primary SSRC for this layering/encoding.
     * @param tid temporal layer ID for this layering/encoding.
     * @param sid spatial layer ID for this layering/encoding.
     * @param height the max height of this encoding
     * @param frameRate the max frame rate (in fps) of this encoding
     * @param dependencyEncodings  The {@link RTPEncodingDesc on which this
     * layer depends.
     */
    public RTPEncodingDesc(
            MediaStreamTrackDesc track, int idx,
            long primarySSRC,
            int tid, int sid,
            int height,
            double frameRate,
            RTPEncodingDesc[] dependencyEncodings)
    {
        // XXX we should be able to snif the actual height from the RTP
        // packets.
        this.height = height;
        this.frameRate = frameRate;
        this.primarySSRC = primarySSRC;
        this.track = track;
        this.idx = idx;
        this.tid = tid;
        this.sid = sid;
        this.dependencyEncodings = dependencyEncodings;
        if (ArrayUtils.isNullOrEmpty(dependencyEncodings))
        {
            this.base = this;
        }
        else
        {
            this.base = dependencyEncodings[0].getBaseLayer();
        }
    }

    /**
     * @return the "id" of this encoding. This is a server-side id and should
     * not be confused with any encoding id defined in the client (such us the
     * rid). This server-side id is used in the encodings lookup table that is
     * maintained in {@link MediaStreamTrackDesc}.
     */
    public long getEncodingId()
    {
        long encodingId = primarySSRC;
        if (tid > -1)
        {
            encodingId |= (long) tid << 32;
        }

        return encodingId;
    }

    /**
     * @param videoRtpPacket the video packet
     * @return gets the server-side encoding id (see
     * {@link #getEncodingId(VideoRtpPacket)}) of a video packet.
     */
    public static long getEncodingId(@NotNull VideoRtpPacket videoRtpPacket)
    {
        long encodingId = videoRtpPacket.getSsrc();
        if (videoRtpPacket instanceof Vp8Packet)
        {
            // note(george) we've observed that a client may announce but not
            // send simulcast (it is not clear atm who's to blame for this
            // "bug", chrome or our client code). In any case, when this happens
            // we "pretend" that the encoding of the packet is the base temporal
            // layer of the rtp stream (ssrc) of the packet.
            int tid = ((Vp8Packet) videoRtpPacket).getTemporalLayerIndex();
            if (tid < 0)
            {
                tid = 0;
            }
            encodingId |= (long) tid << 32;
        }

        return encodingId;
    }

    public void addSecondarySsrc(long ssrc, SsrcAssociationType type)
    {
        secondarySsrcs.put(ssrc, type);
    }

    /**
     * Gets the primary SSRC for this layering/encoding.
     *
     * @return the primary SSRC for this layering/encoding.
     */
    public long getPrimarySSRC()
    {
        return primarySSRC;
    }

    /**
     * Get the secondary ssrc for this stream that corresponds to the given
     * type
     * @param type the type of the secondary ssrc (e.g. RTX)
     * @return the ssrc for the stream that corresponds to the given type,
     * if it exists; otherwise -1
     */
    public long getSecondarySsrc(SsrcAssociationType type)
    {
        for (Map.Entry<Long, SsrcAssociationType> e : secondarySsrcs.entrySet())
        {
            if (e.getValue().equals(type))
            {
                return e.getKey();
            }
        }
        return -1;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString()
    {
        return "subjective_quality=" + idx +
            ",primary_ssrc=" + getPrimarySSRC() +
            ",secondary_ssrcs=" + secondarySsrcs +
            ",temporal_id=" + tid +
            ",spatial_id=" + sid;
    }

    /**
     * Gets the subjective quality index of this instance.
     *
     * @return the subjective quality index of this instance.
     */
    public int getIndex()
    {
        return idx;
    }

    boolean matches(VideoRtpPacket packet)
    {
        if (!matches(packet.getSsrc()))
        {
            return false;
        }
        else if (tid == -1 && sid == -1)
        {
            return true;
        }
        else if (packet instanceof Vp8Packet)
        {
            Vp8Packet vp8Packet = (Vp8Packet)packet;
            // NOTE(brian): the spatial layer index of an encoding is only currently used for in-band spatial
            // scalability (a la vp9), so it isn't used for anything we're currently supporting (and is
            // codec-specific, so should probably be implemented in another way anyhow) so for now we don't
            // check that here (note, though, that the spatial layer index in a packet is currently set as of
            // the time of this writing and is from the perspective of a logical spatial index, i.e. the lowest sim
            // stream (180p) has spatial index 0, 360p has 1, 720p has 2.
            int vp8PacketTid = vp8Packet.getTemporalLayerIndex();
            return (tid == vp8PacketTid) || (vp8PacketTid == -1 && tid == 0);
        }
        else
        {
            return true;
        }
    }

    /**
     * Gets a boolean indicating whether or not the SSRC specified in the
     * arguments matches this encoding or not.
     *
     * @param ssrc the SSRC to match.
     */
    public boolean matches(long ssrc)
    {
        if (primarySSRC == ssrc)
        {
            return true;
        }
        return secondarySsrcs.containsKey(ssrc);
    }

    /**
     *
     * @param packetSizeBytes
     * @param nowMs
     */
    public void updateBitrate(int packetSizeBytes, long nowMs)
    {
        // Update rate stats (this should run after padding termination).
        rateStatistics.update(packetSizeBytes , nowMs);
    }

    /**
     * Gets the cumulative bitrate (in bps) of this {@link RTPEncodingDesc and
     * its dependencies.
     *
     * @param nowMs
     * @return the cumulative bitrate (in bps) of this {@link RTPEncodingDesc
     * and its dependencies.
     */
    public long getBitrateBps(long nowMs)
    {
        RTPEncodingDesc[] encodings = track.getRTPEncodings();
        if (ArrayUtils.isNullOrEmpty(encodings))
        {
            return 0;
        }

        long[] rates = new long[encodings.length];
        getBitrateBps(nowMs, rates);

        long bitrate = 0;
        for (int i = 0; i < rates.length; i++)
        {
            bitrate += rates[i];
        }

        return bitrate;
    }

    /**
     * Recursively adds the bitrate (in bps) of this {@link RTPEncodingDesc and
     * its dependencies in the array passed in as an argument.
     *
     * @param nowMs
     */
    private void getBitrateBps(long nowMs, long[] rates)
    {
        if (rates[idx] == 0)
        {
            rates[idx] = rateStatistics.getRate(nowMs);
        }

        if (!ArrayUtils.isNullOrEmpty(dependencyEncodings))
        {
            for (RTPEncodingDesc dependency : dependencyEncodings)
            {
                dependency.getBitrateBps(nowMs, rates);
            }
        }
    }

    /**
     * Gets the root {@link RTPEncodingDesc of the dependencies DAG. Useful for
     * simulcast handling.
     *
     * @return the root {@link RTPEncodingDesc of the dependencies DAG. Useful for
     * simulcast handling.
     */
    public RTPEncodingDesc getBaseLayer()
    {
        return base;
    }

    /**
     * Gets the max height of the bitstream that this instance represents.
     *
     * @return the max height of the bitstream that this instance represents.
     */
    public int getHeight()
    {
        return height;
    }

    /**
     * Gets the max frame rate (in fps) of the bitstream that this instance
     * represents.
     *
     * @return the max frame rate (in fps) of the bitstream that this instance
     * represents.
     */
    public double getFrameRate()
    {
        return frameRate;
    }

    /**
     * Gets the number of receivers for this encoding.
     *
     * @return the number of receivers for this encoding.
     */
    public boolean isReceived()
    {
        return numOfReceivers.get() > 0;
    }
}
