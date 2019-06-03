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

import org.jitsi.nlj.rtp.*;
import org.jitsi.nlj.rtp.codec.vp8.*;
import org.jitsi.utils.*;

import java.util.*;

/**
 * Represents a collection of {@link RTPEncodingDesc}s that encode the same
 * media source. This specific implementation provides webrtc simulcast stream
 * suspension detection.
 *
 * @author George Politis
 *
 * NOTE(brian): similar to the original but it doesn't reference a MediaStreamTrackReceiver
 */
public class MediaStreamTrackDesc
{
    /**
     * The {@link RTPEncodingDesc}s that this {@link MediaStreamTrackDesc}
     * possesses, ordered by their subjective quality from low to high.
     */
    private final RTPEncodingDesc[] rtpEncodings;

    /**
     * Allow the lookup of an encoding by the encoding id of a received packet.
     */
    private final Map<Long, RTPEncodingDesc> encodingsById = new HashMap<>();

    /**
     * A string which identifies the owner of this track (e.g. the endpoint
     * which is the sender of the track).
     */
    private final String owner;

    /**
     * Ctor.
     *
     * @param rtpEncodings The {@link RTPEncodingDesc}s that this instance
     * possesses.
     */
    public MediaStreamTrackDesc(
        RTPEncodingDesc[] rtpEncodings)
    {
        this(rtpEncodings, null);
    }

    /**
     * Ctor.
     *
     * @param rtpEncodings The {@link RTPEncodingDesc}s that this instance
     * possesses.
     */
    public MediaStreamTrackDesc(
        RTPEncodingDesc[] rtpEncodings,
        String owner)
    {
        this.rtpEncodings = rtpEncodings;
        this.owner = owner;
    }

    public void updateEncodingCache()
    {
        for (RTPEncodingDesc encoding : this.rtpEncodings)
        {
            encodingsById.put(encoding.getEncodingId(), encoding);
        }
    }

    /**
     * @return the identifier of the owner of this track.
     */
    public String getOwner()
    {
        return owner;
    }

    /**
     * Returns an array of all the {@link RTPEncodingDesc}s for this instance,
     * in subjective quality ascending order.
     *
     * @return an array of all the {@link RTPEncodingDesc}s for this instance,
     * in subjective quality ascending order.
     */
    public RTPEncodingDesc[] getRTPEncodings()
    {
        return rtpEncodings;
    }

    /**
     * Gets the last "stable" bitrate (in bps) of the encoding of the specified
     * index. The "stable" bitrate is measured on every new frame and with a
     * 5000ms window.
     *
     * @return the last "stable" bitrate (bps) of the encoding at the specified
     * index.
     */
    public long getBitrateBps(long nowMs, int idx)
    {
        if (ArrayUtils.isNullOrEmpty(rtpEncodings))
        {
            return 0;
        }

        if (idx > -1)
        {
            for (int i = idx; i > -1; i--)
            {
                long bps = rtpEncodings[i].getBitrateBps(nowMs);
                if (bps > 0)
                {
                    return bps;
                }
            }
        }

        return 0;
    }

    public RTPEncodingDesc findRtpEncodingDesc(VideoRtpPacket videoRtpPacket)
    {
        if (ArrayUtils.isNullOrEmpty(rtpEncodings))
        {
            return null;
        }

        long encodingId = RTPEncodingDesc.getEncodingId(videoRtpPacket);
        RTPEncodingDesc desc = encodingsById.get(encodingId);
        if (desc != null)
        {
            return desc;
        }

        return Arrays.stream(rtpEncodings)
                .filter(encoding -> encoding.matches(videoRtpPacket))
                .findFirst()
                .orElse(null);
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("MediaStreamTrackDesc ").append(hashCode()).append(" has encodings:\n");
        for (RTPEncodingDesc encodingDesc : rtpEncodings)
        {
            sb.append("  ").append(encodingDesc.toString());
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * FIXME: this should probably check whether the specified SSRC is part
     * of this track (i.e. check all encodings and include secondary SSRCs).
     *
     * @param ssrc the SSRC to match.
     * @return {@code true} if the specified {@code ssrc} is the primary SSRC
     * for this track.
     */
    public boolean matches(long ssrc)
    {
        return rtpEncodings.length > 0 && rtpEncodings[0].getPrimarySSRC() == ssrc;
    }
}
