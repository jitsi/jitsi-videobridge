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

import java.lang.ref.*;

import org.jitsi.util.*;

/**
 * Implements a <tt>Thread</tt> which expires the {@link Channel}s of a specific
 * <tt>Videobridge</tt>.
 *
 * @author Lyubomir Marinov
 */
class VideobridgeExpireThread
    extends Thread
{

    /**
     * The <tt>Logger</tt> used by the <tt>VideobridgeExpireThread</tt> class
     * and its instances to print debug information.
     */
    private static final Logger logger
        = Logger.getLogger(VideobridgeExpireThread.class);

    /**
     * The <tt>Videobridge</tt> which has its {@link Channel}s expired by this
     * instance. <tt>WeakReference</tt>d to allow this instance to determine
     * when it is to stop executing.
     */
    private final WeakReference<Videobridge> videobridge;

    /**
     * Initializes a new <tt>VideobridgeExpireThread</tt> instance which is to
     * expire the {@link Channel}s of a specific <tt>Videobridge</tt>.
     *
     * @param videobridge the <tt>Videobridge</tt> which is to have its
     * <tt>Channel</tt>s expired by the new instance
     */
    public VideobridgeExpireThread(Videobridge videobridge)
    {
        this.videobridge = new WeakReference<Videobridge>(videobridge);

        setDaemon(true);
        setName(getClass().getName());
    }

    /**
     * Expires the {@link Channel}s of a specific <tt>Videobridge</tt> if they
     * have been inactive for more than their advertised <tt>expire</tt> number
     * of seconds.
     *
     * @param videobridge the <tt>Videobridge</tt> which is to have its
     * <tt>Channel</tt>s expired if they have been inactive for more than their
     * advertised <tt>expire</tt> number of seconds
     */
    private void expire(Videobridge videobridge)
    {
        for (Conference conference : videobridge.getConferences())
        {
            // The Conferences will live an iteration more than the Contents.
            Content[] contents = conference.getContents();

            if (contents.length == 0)
            {
                if ((conference.getLastActivityTime()
                            + 1000L * Channel.DEFAULT_EXPIRE)
                        < System.currentTimeMillis())
                {
                    try
                    {
                        conference.expire();
                    }
                    catch (Throwable t)
                    {
                        logger.warn(
                                "Failed to expire conference "
                                    + conference.getID() + "!",
                                t);
                        if (t instanceof ThreadDeath)
                            throw (ThreadDeath) t;
                    }
                }
            }
            else
            {
                for (Content content : conference.getContents())
                {
                    /*
                     * The Contents will live an iteration more than the
                     * Channels.
                     */
                    Channel[] channels = content.getChannels();

                    if (channels.length == 0)
                    {
                        if ((content.getLastActivityTime()
                                    + 1000L * Channel.DEFAULT_EXPIRE)
                                < System.currentTimeMillis())
                        {
                            try
                            {
                                content.expire();
                            }
                            catch (Throwable t)
                            {
                                logger.warn(
                                        "Failed to expire content "
                                            + content.getName()
                                            + " of conference "
                                            + conference.getID() + "!",
                                        t);
                                if (t instanceof ThreadDeath)
                                    throw (ThreadDeath) t;
                            }
                        }
                    }
                    else
                    {
                        for (Channel channel : channels)
                        {
                            if ((channel.getLastActivityTime()
                                        + 1000L * channel.getExpire())
                                    < System.currentTimeMillis())
                            {
                                try
                                {
                                    channel.expire();
                                }
                                catch (Throwable t)
                                {
                                    logger.warn(
                                            "Failed to expire channel "
                                                + channel.getID()
                                                + " of content "
                                                + content.getName()
                                                + " of conference "
                                                + conference.getID() + "!",
                                            t);
                                    if (t instanceof ThreadDeath)
                                        throw (ThreadDeath) t;
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Runs the loop in the background which expires the {@link Channel}s of
     * {@link #videobridge} if they have been inactive for more than their
     * advertised <tt>expire</tt> number of seconds.
     */
    @Override
    public void run()
    {
        long wakeup = -1;
        final long sleep = Channel.DEFAULT_EXPIRE * 1000;

        do
        {
            /*
             * If the Videobridge of this instance is not referenced anymore,
             * then it is time for this Thread to stop executing.
             */
            Videobridge videobridge = this.videobridge.get();

            if (videobridge == null)
                break;

            // Run the command of this Thread scheduled with a fixed delay.
            long now = System.currentTimeMillis();

            if (wakeup != -1)
            {
                long slept = now - wakeup;

                if (slept < sleep)
                {
                    boolean interrupted = false;

                    try
                    {
                        Thread.sleep(sleep - slept);
                    }
                    catch (InterruptedException ie)
                    {
                        interrupted = true;
                    }
                    if (interrupted)
                        Thread.currentThread().interrupt();

                    continue;
                }
            }

            wakeup = now;

            try
            {
                expire(videobridge);
            }
            catch (Throwable t)
            {
                logger.error(
                        "Failed to complete an iteration of automatic expiry of"
                            + " channels, contents, and conferences!",
                        t);
                if (t instanceof ThreadDeath)
                    throw (ThreadDeath) t;
            }
        }
        while (true);
    }
}
