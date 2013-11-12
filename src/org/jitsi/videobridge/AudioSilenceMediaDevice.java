/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
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
