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
package org.jitsi.videobridge.rtcp;

import org.jitsi.service.neomedia.*;
import org.jitsi.videobridge.*;

import java.lang.ref.*;

/**
 * @author George Politis
 */
public abstract class VideoChannelRTCPTerminationStrategy
    implements RTCPTerminationStrategy
{
    /**
     * A <tt>WeakReference</tt> to the <tt>VideoChannel</tt> that owns this
     * this <tt>VideoChannelRTCPTerminationStrategy</tt>.
     */
    private WeakReference<VideoChannel> weakVideoChannel;

    /**
     * Gets the <tt>VideoChannel</tt> that owns this
     * <tt>VideoChannelRTCPTerminationStrategy</tt>.
     *
     * @return the <tt>VideoChannel</tt> that owns this
     * <tt>VideoChannelRTCPTerminationStrategy</tt>.
     */
    public VideoChannel getVideoChannel()
    {
        WeakReference<VideoChannel> wvc = this.weakVideoChannel;
        return wvc != null ? wvc.get() : null;
    }

    /**
     * Initializes this RTCP termination strategy with a <tt>VideoChannel</tt>.
     *
     * @param vc the <tt>VideoChannel</tt> that owns this
     * <tt>VideoChannelRTCPTerminationStrategy</tt>.
     */
    public void initialize(VideoChannel vc)
    {
        if (vc == null)
        {
            return;
        }

        this.weakVideoChannel = new WeakReference<VideoChannel>(vc);
    }
}
