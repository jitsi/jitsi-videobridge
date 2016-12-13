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
package org.jitsi.videobridge.ratecontrol;

import org.ice4j.util.*;
import org.jitsi.impl.neomedia.*;
import org.jitsi.impl.neomedia.rtcp.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.util.*;
import org.jitsi.videobridge.*;

import java.util.*;
import java.util.concurrent.atomic.*;

/**
 * The JVB implementation of an {@code RTPEncoding}.
 *
 * @author George Politis
 */
public class RTPEncodingImpl
    implements RTPEncoding
{
    /**
     * TODO make this configurable.
     */
    private static final int AVERAGE_BITRATE_WINDOW_MS = 5000;

    /**
     * The {@code RateStatistics} instance used to calculate the receiving
     * bitrate of this RTP encoding.
     */
    private final RateStatistics rateStatistics
        = new RateStatistics(AVERAGE_BITRATE_WINDOW_MS);

    /**
     * The number of receivers that are receiving the RTP encoding that this
     * instance represents.
     */
    private AtomicInteger cntReceivers = new AtomicInteger(0);

    /**
     * The {@code MediaStreamTrackImpl} that this {@code RTPEncodingImpl}
     * belongs to.
     */
    private final MediaStreamTrackImpl track;

    /**
     * The identifier for this layering/encoding, set by the sender.
     */
    private final String encodingId;

    /**
     * The primary SSRC for this layering/encoding.
     */
    private final long primarySSRC;

    /**
     * The RTX SSRC for this layering/encoding.
     */
    private final long rtxSSRC;

    /**
     * The index of this instance in the track encodings array.
     */
    private final int idx;

    /**
     * Inverse of the input framerate fraction to be encoded.
     *
     * Example: 1.0 = full framerate, 2.0 = one half of the full framerate.
     *
     * For scalable video coding, framerateScale refers to the inverse of the
     * aggregate fraction of input framerate achieved by this layer when
     * combined with all dependent layers.
     */
    private final double frameRateScale;

    /**
     * If the sender's kind is "video", the video's resolution will be scaled
     * down in each dimension by the given value before sending.
     *
     * For example, if the value is 2.0, the video will be scaled down by a
     * factor of 2 in each dimension, resulting in sending a video of one
     * quarter size. If the value is 1.0 (the default), the video will not be
     * affected.
     *
     * For scalable video coding, resolutionScale refers to the aggregate scale
     * down of this layer when combined with all dependent layers.
     */
    private final double resolutionScale;

    /**
     * The {@code RTPEncodingImpl} on which this layer depends.
     */
    private final RTPEncodingImpl[] dependencyEncodings;

    /**
     *
     */
    private final Map<Integer, Boolean> dependencyCache = new TreeMap<>();

    /**
     *
     */
    private boolean active = false;

    /**
     *
     */
    private long lastStableBitrateBps;

    /**
     *
     */
    private long maxTimestamp = -1;

    /**
     *
     */
    private boolean canRewrite = false;

    /**
     * Ctor.
     *
     * @param track the {@code MediaStreamTrack} that this instance belongs to.
     * @param idx the identifier for this layering/encoding, set by the
     * sender.
     * @param primarySSRC The primary SSRC for this layering/encoding.
     */
    public RTPEncodingImpl(
        MediaStreamTrackImpl track, int idx, long primarySSRC)
    {
        this(track, idx, primarySSRC, -1, -1.0, -1.0, null);
    }

    /**
     * Ctor.
     *
     * @param track the {@code MediaStreamTrack} that this instance belongs to.
     * @param idx the identifier for this layering/encoding, set by the
     * sender.
     * @param primarySSRC The primary SSRC for this layering/encoding.
     * @param rtxSSRC The RTX SSRC for this layering/encoding.
     */
    public RTPEncodingImpl(
        MediaStreamTrackImpl track,
        int idx, long primarySSRC, long rtxSSRC)
    {
        this(track, idx, primarySSRC, rtxSSRC, -1.0, -1.0, null);
    }

    /**
     * Ctor.
     *
     * @param track the {@code MediaStreamTrack} that this instance belongs to.
     * @param idx the index for this layering/encoding.
     * @param primarySSRC The primary SSRC for this layering/encoding.
     * @param rtxSSRC The RTX SSRC for this layering/encoding.
     * @param frameRateScale The inverse of the input framerate fraction to be
     * encoded.
     * @param resolutionScale If the sender's kind is "video", the video's
     * resolution will be scaled
     * down in each dimension by the given value before sending.
     * @param dependencyEncodings  The {@code RTPEncodingImpl} on which this
     * layer depends.
     */
    public RTPEncodingImpl(
        MediaStreamTrackImpl track, int idx,
        long primarySSRC, long rtxSSRC,
        double frameRateScale, double resolutionScale,
        RTPEncodingImpl[] dependencyEncodings)
    {
        this.track = track;
        this.idx = idx;
        this.encodingId = Integer.toString(idx);
        this.primarySSRC = primarySSRC;
        this.rtxSSRC = rtxSSRC;
        this.frameRateScale = frameRateScale;
        this.resolutionScale = resolutionScale;
        this.dependencyEncodings = dependencyEncodings;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getPrimarySSRC()
    {
        return primarySSRC;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getRTXSSRC()
    {
        return rtxSSRC;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public double getFrameRateScale()
    {
        return frameRateScale;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public double getResolutionScale()
    {
        return resolutionScale;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public RTPEncodingImpl[] getDependencyEncodings()
    {
        return dependencyEncodings;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isActive()
    {
        return active;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString()
    {
        return "encoding_id=" + encodingId +
            ",primary_ssrc=" + primarySSRC +
            ",rtx_ssrc=" + rtxSSRC +
            ",frame_rate_scale=" + frameRateScale +
            ",resolution_scale=" + resolutionScale +
            ",active=" + active +
            ",last_stable_bitrate_bps=" + lastStableBitrateBps +
            ",receivers_count=" + cntReceivers.get();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getEncodingId()
    {
        return encodingId;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MediaStreamTrackImpl getMediaStreamTrack()
    {
        return track;
    }

    /**
     *
     * @param rtpEncoding
     * @return
     */
    public boolean requires(RTPEncodingImpl rtpEncoding)
    {
        if (rtpEncoding == null)
        {
            return false;
        }

        if (rtpEncoding == this)
        {
            return true;
        }

        Boolean requires = dependencyCache.get(rtpEncoding.idx);

        if (requires == null)
        {
            requires = false;

            if (dependencyEncodings != null && dependencyEncodings.length != 0)
            {
                for (RTPEncodingImpl enc : dependencyEncodings)
                {
                    if (enc.requires(rtpEncoding))
                    {
                        requires = true;
                        break;
                    }
                }
            }

            dependencyCache.put(rtpEncoding.idx, requires);
        }

        return requires;
    }

    /**
     * Gets a boolean value indicating whether this instance if forwarded or not.
     *
     * @return true if this instance if forwarded, otherwise false.
     */
    public boolean isForwarded()
    {
        return cntReceivers.get() > 0;
    }

    /**
     *
     */
    public void increment()
    {
        cntReceivers.incrementAndGet();

        if (dependencyEncodings != null && dependencyEncodings.length != 0)
        {
            for (int i = 0; i < dependencyEncodings.length; i++)
            {
                dependencyEncodings[i].cntReceivers.incrementAndGet();
            }
        }
    }

    /**
     *
     */
    public void decrement()
    {
        cntReceivers.decrementAndGet();

        if (dependencyEncodings != null && dependencyEncodings.length != 0)
        {
            for (int i = 0; i < dependencyEncodings.length; i++)
            {
                dependencyEncodings[i].cntReceivers.decrementAndGet();
            }
        }
    }

    /**
     *
     * @return
     */
    public long getLastStableBitrateBps()
    {
        return lastStableBitrateBps;
    }

    /**
     *
     * @param buf
     * @param off
     * @param len
     * @return
     */
    public boolean matches(byte[] buf, int off, int len)
    {
        long ssrc = RawPacket.getSSRCAsLong(buf, off, len);

        if (primarySSRC != ssrc && rtxSSRC != ssrc)
        {
            return false;
        }

        // TODO RID based matching.
        long tid = track.getMediaStreamTrackReceiver()
            .getChannel().getStream().getTemporalLayer(buf, off, len);

        return tid == -1 || (1 << (2 - tid)) == frameRateScale;
    }

    /**
     *
     * @param nowMs
     * @return
     */
    public long getBitrateBps(long nowMs)
    {
        long bitrateBps = rateStatistics.getRate(nowMs);

        if (dependencyEncodings != null && dependencyEncodings.length != 0)
        {
            for (RTPEncodingImpl dependency : dependencyEncodings)
            {
                bitrateBps += dependency.getBitrateBps(nowMs);
            }
        }

        return bitrateBps;
    }

    /**
     *
     * @return
     */
    public RateStatistics getRateStatistics()
    {
        return rateStatistics;
    }

    /**
     *
     * @return
     */
    public long getMaxTimestamp()
    {
        return maxTimestamp;
    }

    /**
     *
     * @param maxTimestamp
     */
    public void setMaxTimestamp(long maxTimestamp)
    {
        this.maxTimestamp = maxTimestamp;
    }

    /**
     *
     * @param lastStableBitrateBps
     */
    public void setLastStableBitrateBps(long lastStableBitrateBps)
    {
        this.lastStableBitrateBps = lastStableBitrateBps;
    }

    /**
     *
     * @param active
     */
    public void setActive(boolean active)
    {
        this.active = active;
    }
}
