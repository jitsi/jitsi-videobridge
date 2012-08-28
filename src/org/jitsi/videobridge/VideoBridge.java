/*
 * Jitsi VideoBridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge;

import java.lang.ref.*;
import java.util.*;

/**
 * Represents the Jitsi VideoBridge which creates, lists and destroys
 * {@link Conference} instances.
 *
 * @author Lyubomir Marinov
 */
public class VideoBridge
{
    /**
     * The pseudo-random generator which is to be used when generating
     * {@link Conference} and {@link Channel} IDs in order to minimize busy
     * waiting for the value of {@link System#currentTimeMillis()} to change.
     */
    static final Random RANDOM = new Random();

    /**
     * The <tt>ComponentImpl</tt> which has initialized this
     * <tt>VideoBridge</tt>.
     */
    private final ComponentImpl component;

    /**
     * The <tt>Conference</tt>s of this <tt>VideoBridge</tt> mapped by their
     * IDs.
     */
    private final Map<String, Conference> conferences
        = new HashMap<String, Conference>();

    /**
     * Initializes a new <tt>VideoBridge</tt> instance which is initialized by a
     * specific <tt>ComponentImpl</tt>.
     *
     * @param component the <tt>ComponentImpl</tt> which initialized the new
     * instance
     */
    public VideoBridge(ComponentImpl component)
    {
        if (component == null)
            throw new NullPointerException("component");

        this.component = component;

        new ExpireThread(this).start();
    }

    /**
     * Initializes a new {@link Conference} instance with an ID unique to the
     * <tt>Conference</tt> instances listed by this <tt>VideoBridge</tt> and
     * adds the new instance to the list of existing <tt>Conference</tt>
     * instances.
     *
     * @return a new <tt>Conference</tt> instance with an ID unique to the
     * <tt>Conference</tt> instances listed by this <tt>VideoBridge</tt>
     */
    public Conference createConference()
    {
        Conference conference = null;

        while (conference == null)
        {
            String id = generateConferenceID();

            synchronized (conferences)
            {
                if (!conferences.containsKey(id))
                {
                    conference = new Conference(this, id);
                    conferences.put(id, conference);
                }
            }
        }

        return conference;
    }

    /**
     * Expires a specific <tt>Conference</tt> of this <tt>VideoBridge</tt> (i.e.
     * if the specified <tt>Conference</tt> is not in the list of
     * <tt>Conference</tt>s of this <tt>VideoBridge</tt>, does nothing).
     *
     * @param conference the <tt>Conference</tt> to be expired by this
     * <tt>VideoBridge</tt>
     */
    public void expireConference(Conference conference)
    {
        String id = conference.getID();
        boolean expireConference;

        synchronized (conferences)
        {
            if (conference.equals(conferences.get(id)))
            {
                conferences.remove(id);
                expireConference = true;
            }
            else
                expireConference = false;
        }
        if (expireConference)
            conference.expire();
    }

    /**
     * Generates a new <tt>Conference</tt> ID which is not guaranteed to be
     * unique.
     *
     * @return a new <tt>Conference</tt> ID which is not guaranteed to be unique
     */
    private String generateConferenceID()
    {
        return Long.toHexString(System.currentTimeMillis() + RANDOM.nextLong());
    }

    /**
     * Gets the <tt>ComponentImpl</tt> which has initialized this
     * <tt>VideoBridge</tt>.
     *
     * @return the <tt>ComponentImpl</tt> which has initialized this
     * <tt>VideoBridge</tt>
     */
    public ComponentImpl getComponent()
    {
        return component;
    }

    /**
     * Gets an existing {@link Conference} with a specific ID.
     *
     * @param id the ID of the existing <tt>Conference</tt> to get
     * @return an existing <tt>Conference</tt> with the specified ID or
     * <tt>null</tt> if no <tt>Conference</tt> with the specified ID is known to
     * this <tt>VideoBridge</tt>
     */
    public Conference getConference(String id)
    {
        Conference conference;

        synchronized (conferences)
        {
            conference = conferences.get(id);
        }

        // It seems the conference is still active.
        if (conference != null)
            conference.touch();

        return conference;
    }

    /**
     * Gets the <tt>Conference</tt>s of this <tt>VideoBridge</tt>.
     *
     * @return the <tt>Conference</tt>s of this <tt>VideoBridge</tt>
     */
    public Conference[] getConferences()
    {
        synchronized (conferences)
        {
            Collection<Conference> values = conferences.values();

            return values.toArray(new Conference[values.size()]);
        }
    }

    /**
     * Implements a <tt>Thread</tt> which expires the {@link Channel}s of a
     * specific <tt>VideoBridge</tt>.
     */
    private static class ExpireThread
        extends Thread
    {
        /**
         * The <tt>VideoBridge</tt> which has its {@link Channel}s expired by
         * this instance. <tt>WeakReference</tt>d to allow this instance to
         * determine when it is to stop executing.
         */
        private final WeakReference<VideoBridge> videoBridge;

        /**
         * Initializes a new <tt>ExpireThread</tt> instance which is to expire
         * the {@link Channel}s of a specific <tt>VideoBridge</tt>.
         *
         * @param videoBridge the <tt>VideoBridge</tt> which is to have its
         * <tt>Channel</tt>s expired by the new instance
         */
        public ExpireThread(VideoBridge videoBridge)
        {
            this.videoBridge = new WeakReference<VideoBridge>(videoBridge);

            setDaemon(true);
            setName(getClass().getName());
        }

        /**
         * Expires the {@link Channel}s of a specific <tt>VideoBridge</tt> if
         * they have been inactive for more than their advertised
         * <tt>expire</tt> number of seconds.
         *
         * @param videoBridge the <tt>VideoBridge</tt> which is to have its
         * <tt>Channel</tt>s expired if they have been inactive for more than
         * their advertised <tt>expire</tt> number of seconds
         */
        private void expire(VideoBridge videoBridge)
        {
            for (Conference conference : videoBridge.getConferences())
            {
                /*
                 * The Conferences will live an iteration more than the
                 * Contents.
                 */
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
                            t.printStackTrace(System.err);
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
                                    t.printStackTrace(System.err);
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
                                        t.printStackTrace(System.err);
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

        @Override
        public void run()
        {
            long wakeup = -1;
            final long sleep = Channel.DEFAULT_EXPIRE * 1000;

            while (true)
            {
                /*
                 * If the VideoBridge of this instance is not referenced
                 * anymore, then it is time for this Thread to stop executing.
                 */
                VideoBridge videoBridge = this.videoBridge.get();

                if (videoBridge == null)
                    break;

                /*
                 * Run the command of this Thread scheduled with a fixed delay.
                 */
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
                    expire(videoBridge);
                }
                catch (Throwable t)
                {
                    t.printStackTrace(System.err);
                    if (t instanceof ThreadDeath)
                        throw (ThreadDeath) t;
                }
            }
        }
    }
}
