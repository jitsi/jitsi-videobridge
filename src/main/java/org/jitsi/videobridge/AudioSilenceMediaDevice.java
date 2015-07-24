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
package org.jitsi.videobridge;

import javax.media.*;
import javax.media.protocol.*;

import org.jitsi.impl.neomedia.device.*;
import org.jitsi.service.neomedia.*;

/**
 * Implements a <tt>MediaDevice</tt> which provides silence in the form of audio
 * media and does not play back any (audio) media (because Jitsi Videobridge is
 * a server-side technology).
 *
 * @author Lyubomir Marinov
 */
public class AudioSilenceMediaDevice
    extends AudioMediaDeviceImpl
{
    /**
     * {@inheritDoc}
     *
     * Overrides the super implementation to initialize a <tt>CaptureDevice</tt>
     * without asking FMJ to initialize one for a <tt>CaptureDeviceInfo</tt>.
     */
    @Override
    protected CaptureDevice createCaptureDevice()
    {
        return new AudioSilenceCaptureDevice();
    }

    /**
     * {@inheritDoc}
     *
     * Overrides the super implementation to disable the very playback because
     * Jitsi Videobridge is a server-side technology.
     */
    @Override
    protected Processor createPlayer(DataSource dataSource)
    {
        return null;
    }

    /**
     * {@inheritDoc}
     *
     * Overrides the super implementation to initialize a
     * <tt>MediaDeviceSession</tt> which disables the very playback because
     * Jitsi Videobridge is a server-side technology.
     */
    @Override
    public MediaDeviceSession createSession()
    {
        return
            new AudioMediaDeviceSession(this)
                    {
                        /**
                         * {@inheritDoc}
                         *
                         * Overrides the super implementation to disable the
                         * very playback because Jitsi Videobridge is a
                         * server-side technology.
                         */
                        @Override
                        protected Player createPlayer(DataSource dataSource)
                        {
                            return null;
                        }
                    };
    }

    /**
     * {@inheritDoc}
     *
     * Overrides the super implementation to always return
     * {@link MediaDirection#SENDRECV} because this instance stands for a relay
     * and because the super bases the <tt>MediaDirection</tt> on the
     * <tt>CaptureDeviceInfo</tt> which this instance does not have.
     */
    @Override
    public MediaDirection getDirection()
    {
        return MediaDirection.SENDRECV;
    }
}
