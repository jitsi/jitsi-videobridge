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

import org.jitsi.service.neomedia.*;
import org.jitsi.util.*;

import java.util.*;

/**
 * The JVB implementation of a {@link MediaStreamTrack}.
 *
 * @author George Politis
 */
public class MediaStreamTrackImpl
    implements MediaStreamTrack,
               Comparator<RTPEncodingImpl>
{
    /**
     * The {@link RTPEncoding}s that this {@link MediaStreamTrackImpl}
     * possesses.
     */
    private final RTPEncodingImpl[] rtpEncodings;

    /**
     * The independent {@link RTPEncoding}s that this {@link MediaStreamTrackImpl}
     * possesses. Not really used currently.
     */
    private final RTPEncodingImpl[] independentEncodings;

    /**
     * The identifier of this instance. Unused for the most part, for now.
     */
    private final String mediaStreamTrackId;

    /**
     * The index of this instance in the {@link MediaStreamTrackReceiver}.
     */
    private final int idx;

    /**
     *
     */
    private final boolean isMultiStream;

    /**
     * The {@link MediaStreamTrackReceiver} that receives this instance.
     */
    private MediaStreamTrackReceiver mediaStreamTrackReceiver;

    /**
     *
     */
    private long millisSinceLastKeyframe;

    /**
     * Ctor.
     *
     * @param mediaStreamTrackReceiver The {@link MediaStreamTrackReceiver} that
     * receives this instance.
     * @param rtpEncodings The {@link RTPEncoding}s that this instance possesses.
     */
    public MediaStreamTrackImpl(
        int idx,
        MediaStreamTrackReceiver mediaStreamTrackReceiver,
        RTPEncodingImpl[] rtpEncodings,
        RTPEncodingImpl ... independentEncodings)
    {
        this.mediaStreamTrackReceiver = mediaStreamTrackReceiver;
        this.rtpEncodings = rtpEncodings;
        this.idx = idx;
        this.mediaStreamTrackId = Integer.toString(idx);
        this.independentEncodings = independentEncodings;
        this.isMultiStream = independentEncodings.length > 1;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public RTPEncodingImpl[] getRTPEncodings()
    {
        return rtpEncodings;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isMultiStream()
    {
        return isMultiStream;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int compare(RTPEncodingImpl o1, RTPEncodingImpl o2)
    {
        if (o1 == o2)
        {
            return 0;
        }

        for (int i = 0; i < rtpEncodings.length; i++)
        {
            RTPEncodingImpl e = rtpEncodings[i];
            if (o1 == e)
            {
                return -1;
            }
            else if (o2 == e)
            {
                return 1;
            }
        }

        return 0;
    }

    /**
     * Gets the identifier of this {@code MediaStreamTrackImpl}.
     *
     * @return the identifier of this {@code MediaStreamTrackImpl}.
     */
    @Override
    public String getMediaStreamTrackId()
    {
        return mediaStreamTrackId;
    }

    /**
     * Returns the {@link MediaStreamTrackReceiver} that receives this instance.
     * @return The {@link MediaStreamTrackReceiver} that receives this instance.
     */
    public MediaStreamTrackReceiver getMediaStreamTrackReceiver()
    {
        return mediaStreamTrackReceiver;
    }

    /**
     *
     * @return
     */
    public boolean hasStarted()
    {
        return rtpEncodings != null && rtpEncodings.length != 0
            && rtpEncodings[0].getLastStableBitrateBps() > 0;
    }

    public long getMillisSinceLastKeyframe()
    {
        return millisSinceLastKeyframe;
    }

    public void setMillisSinceLastKeyframe(long millisSinceLastKeyframe)
    {
        this.millisSinceLastKeyframe = millisSinceLastKeyframe;
    }
}
