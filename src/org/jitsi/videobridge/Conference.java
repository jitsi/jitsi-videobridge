/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge;

import java.io.*;
import java.lang.ref.*;
import java.util.*;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;

import org.jitsi.util.*;
import org.osgi.framework.*;

/**
 * Represents a conference in the terms of Jitsi Videobridge.
 *
 * @author Lyubomir Marinov
 */
public class Conference
{
    /**
     * The <tt>Logger</tt> used by the <tt>Conference</tt> class and its
     * instances to print debug information.
     */
    private static final Logger logger = Logger.getLogger(Conference.class);

    /**
     * Logs a specific <tt>String</tt> at debug level.
     *
     * @param s the <tt>String</tt> to log at debug level 
     */
    private static void logd(String s)
    {
        /*
         * FIXME Jitsi Videobridge uses the defaults of java.util.logging at the
         * time of this writing but wants to log at debug level at all times for
         * the time being in order to facilitate early development.
         */
        logger.info(s);
    }

    /**
     * The <tt>Content</tt>s of this <tt>Conference</tt>.
     */
    private final List<Content> contents = new LinkedList<Content>();

    /**
     * The <tt>Endpoint</tt>s participating in this <tt>Conference</tt>.
     */
    private final List<WeakReference<Endpoint>> endpoints
        = new LinkedList<WeakReference<Endpoint>>();

    /**
     * The indicator which determines whether {@link #expire()} has been called
     * on this <tt>Conference</tt>.
     */
    private boolean expired = false;

    /**
     * The JID of the conference focus who has initialized this instance and
     * from whom requests to manage this instance must come or they will be
     * ignored.
     */
    private final String focus;

    /**
     * The (unique) identifier/ID of this instance.
     */
    private final String id;

    /**
     * The time in milliseconds of the last activity related to this
     * <tt>Conference</tt>. In the time interval between the last activity and
     * now, this <tt>Conference</tt> is considered inactive.
     */
    private long lastActivityTime;

    /**
     * The <tt>Videobridge</tt> which has initialized this <tt>Conference</tt>.
     */
    private final Videobridge videobridge;

    /**
     * FIXME: for testing purpose only, to be removed
     */
    private final RandomSpeakerGenerator randomSpeakerGenerator;

    /**
     * Initializes a new <tt>Conference</tt> instance which is to represent a
     * conference in the terms of Jitsi Videobridge which has a specific
     * (unique) ID and is managed by a conference focus with a specific JID.
     *
     * @param videobridge the <tt>Videobridge</tt> on which the new
     * <tt>Conference</tt> instance is to be initialized
     * @param id the (unique) ID of the new instance to be initialized
     * @param focus the JID of the conference focus who has requested the
     * initialization of the new instance and from whom further/future requests
     * to manage the new instance must come or they will be ignored
     */
    public Conference(Videobridge videobridge, String id, String focus)
    {
        if (videobridge == null)
            throw new NullPointerException("videoBridge");
        if (id == null)
            throw new NullPointerException("id");

        this.videobridge = videobridge;
        this.id = id;
        this.focus = focus;

        this.randomSpeakerGenerator = new RandomSpeakerGenerator();
    }

    /**
     * Sets the values of the properties of a specific
     * <tt>ColibriConferenceIQ</tt> to the values of the respective
     * properties of this instance. Thus, the specified <tt>iq</tt> may be
     * thought of as a description of this instance.
     * <p>
     * <b>Note</b>: The copying of the values is deep i.e. the
     * <tt>Contents</tt>s of this instance are described in the specified
     * <tt>iq</tt>.
     * </p>
     *
     * @param iq the <tt>ColibriConferenceIQ</tt> to set the values of the
     * properties of this instance on
     */
    public void describeDeep(ColibriConferenceIQ iq)
    {
        describeShallow(iq);

        for (Content content : getContents())
        {
            ColibriConferenceIQ.Content contentIQ
                = iq.getOrCreateContent(content.getName());

            for (Channel channel : content.getChannels())
            {
                ColibriConferenceIQ.Channel channelIQ
                    = new ColibriConferenceIQ.Channel();

                channel.describe(channelIQ);
                contentIQ.addChannel(channelIQ);
            }
        }
    }

    /**
     * Sets the values of the properties of a specific
     * <tt>ColibriConferenceIQ</tt> to the values of the respective
     * properties of this instance. Thus, the specified <tt>iq</tt> may be
     * thought of as a description of this instance.
     * <p>
     * <b>Note</b>: The copying of the values is shallow i.e. the
     * <tt>Content</tt>s of this instance are not described in the specified
     * <tt>iq</tt>.
     * </p>
     *
     * @param iq the <tt>ColibriConferenceIQ</tt> to set the values of the
     * properties of this instance on
     */
    public void describeShallow(ColibriConferenceIQ iq)
    {
        iq.setID(getID());
    }

    /**
     * Expires this <tt>Conference</tt>, its <tt>Content</tt>s and their
     * respective <tt>Channel</tt>s. Releases the resources acquired by this
     * instance throughout its life time and prepares it to be garbage
     * collected.
     */
    public void expire()
    {
        synchronized (this)
        {
            if (expired)
                return;
            else
                expired = true;
        }

        randomSpeakerGenerator.expire();

        Videobridge videobridge = getVideobridge();

        try
        {
            videobridge.expireConference(this);
        }
        finally
        {
            // Expire the Contents of this Conference.
            for (Content content : getContents())
            {
                try
                {
                    content.expire();
                }
                catch (Throwable t)
                {
                    logger.warn(
                            "Failed to expire content " + content.getName()
                                + " of conference " + getID() + "!",
                            t);
                    if (t instanceof ThreadDeath)
                        throw (ThreadDeath) t;
                }
            }

            logd(
                    "Expired conference " + getID() + ". The total number of"
                        + " conferences is now "
                        + videobridge.getConferenceCount() + ", channels "
                        + videobridge.getChannelCount() + ".");
        }
    }

    /**
     * Expires a specific <tt>Content</tt> of this <tt>Conference</tt> (i.e. if
     * the specified <tt>content</tt> is not in the list of <tt>Content</tt>s of
     * this <tt>Conference</tt>, does nothing).
     *
     * @param content the <tt>Content</tt> to be expired by this
     * <tt>Conference</tt>
     */
    public void expireContent(Content content)
    {
        boolean expireContent;

        synchronized (contents)
        {
            if (contents.contains(content))
            {
                contents.remove(content);
                expireContent = true;
            }
            else
                expireContent = false;
        }
        if (expireContent)
            content.expire();
    }

    /**
     * Gets the <tt>Content</tt>s of this <tt>Conference</tt>.
     *
     * @return the <tt>Content</tt>s of this <tt>Conference</tt>
     */
    public Content[] getContents()
    {
        synchronized (contents)
        {
            return contents.toArray(new Content[contents.size()]);
        }
    }

    /**
     * Gets the JID of the conference focus who has initialized this instance
     * and from whom requests to manage this instance must come or they will be
     * ignored.
     *
     * @return the JID of the conference focus who has initialized this instance
     * and from whom requests to manage this instance must come or they will be
     * ignored
     */
    public final String getFocus()
    {
        return focus;
    }

    /**
     * Gets the (unique) identifier/ID of this instance.
     *
     * @return the (unique) identifier/ID of this instance
     */
    public final String getID()
    {
        return id;
    }

    /**
     * Gets the time in milliseconds of the last activity related to this
     * <tt>Conference</tt>.
     *
     * @return the time in milliseconds of the last activity related to this
     * <tt>Conference</tt>
     */
    public long getLastActivityTime()
    {
        synchronized (this)
        {
            return lastActivityTime;
        }
    }

    /**
     * Gets a <tt>Content</tt> of this <tt>Conference</tt> which has a specific
     * name. If a <tt>Content</tt> of this <tt>Conference</tt> with the
     * specified <tt>name</tt> does not exist at the time the method is invoked,
     * the method initializes a new <tt>Content</tt> instance with the specified
     * <tt>name</tt> and adds it to the list of <tt>Content</tt>s of this
     * <tt>Conference</tt>.
     *
     * @param name the name of the <tt>Content</tt> which is to be returned
     * @return a <tt>Content</tt> of this <tt>Conference</tt> which has the
     * specified <tt>name</tt>
     */
    public Content getOrCreateContent(String name)
    {
        Content content;

        synchronized (contents)
        {
            for (Content aContent : contents)
            {
                if (aContent.getName().equals(name))
                {
                    aContent.touch(); // It seems the content is still active.
                    return aContent;
                }
            }

            content = new Content(this, name);
            contents.add(content);
        }

        /*
         * The method Videobridge.getChannelCount() should better be executed
         * outside synchronized blocks in order to reduce the risks of causing
         * deadlocks.
         */
        Videobridge videobridge = getVideobridge();

        logd(
                "Created content " + name + " of conference " + getID()
                    + ". The total number of conferences is now "
                    + videobridge.getConferenceCount() + ", channels "
                    + videobridge.getChannelCount() + ".");

        return content;
    }

    /**
     * Gets an <tt>Endpoint</tt> participating in this <tt>Conference</tt> which
     * has a specific identifier/ID. If an <tt>Endpoint</tt> participating in
     * this <tt>Conference</tt> with the specified <tt>id</tt> does not exist at
     * the time the method is invoked, the method initializes a new
     * <tt>Endpoint</tt> instance with the specified <tt>id</tt> and adds it to
     * the list of <tt>Endpoint</tt>s participating in this <tt>Conference</tt>.
     *
     * @param id the identifier/ID of the <tt>Endpoint</tt> which is to be
     * returned
     * @return an <tt>Endpoint</tt> participating in this <tt>Conference</tt>
     * which has the specified <tt>id</tt>
     */
    public Endpoint getOrCreateEndpoint(String id)
    {
        synchronized (endpoints)
        {
            for (Iterator<WeakReference<Endpoint>> i = endpoints.iterator();
                    i.hasNext();)
            {
                Endpoint endpoint = i.next().get();

                if (endpoint == null)
                    i.remove();
                else if (endpoint.getID().equals(id))
                    return endpoint;
            }

            Endpoint endpoint = new Endpoint(id);

            endpoints.add(new WeakReference<Endpoint>(endpoint));
            return endpoint;
        }
    }

    /**
     * Gets the <tt>Videobridge</tt> which has initialized this
     * <tt>Conference</tt>.
     *
     * @return the <tt>Videobridge</tt> which has initialized this
     * <tt>Conference</tt>
     */
    public final Videobridge getVideobridge()
    {
        return videobridge;
    }

    /**
     * Returns the OSGi <tt>BundleContext</tt> in which this Conference is
     * executing.
     *
     * @return the OSGi <tt>BundleContext</tt> in which the Conference is
     * executing.
     */
    public BundleContext getBundleContext()
    {
        return getVideobridge().getBundleContext();
    }
    /**
     * Sets the time in milliseconds of the last activity related to this
     * <tt>Conference</tt> to the current system time.
     */
    public void touch()
    {
        long now = System.currentTimeMillis();

        synchronized (this)
        {
            if (getLastActivityTime() < now)
                lastActivityTime = now;
        }
    }

    /**
     * Class switches active speaker in random intervals from 0 to 10 seconds.
     *
     * @author Pawel Domas
     */
    class RandomSpeakerGenerator
        implements Runnable
    {
        private final Thread thread;
        private boolean run = true;

        private final Random r = new Random();

        public RandomSpeakerGenerator()
        {
            this.thread = new Thread(this, "RandomActiveSpeakerThread");
            this.thread.start();
        }

        @Override
        public void run()
        {
            while (run)
            {
                List<WeakReference<Endpoint>> endpointsCopy;

                synchronized (this)
                {
                    try
                    {
                        // Random interval from 0 to 10 seconds
                        this.wait(r.nextInt(10000));
                        if(!run)
                            break;
                    }
                    catch (InterruptedException e)
                    {
                        Thread.currentThread().interrupt();
                    }

                    synchronized (endpoints)
                    {
                        endpointsCopy
                            = new ArrayList<WeakReference<Endpoint>>(endpoints);
                    }
                }

                int endpointsCount = endpointsCopy.size();

                if(endpointsCount == 0)
                    continue;

                int idx = r.nextInt(endpointsCount);
                WeakReference<Endpoint> activeSpeakerCandidate
                    = endpointsCopy.get(idx);

                Endpoint newActiveSpeaker = activeSpeakerCandidate.get();
                if(newActiveSpeaker == null)
                {
                    // Maybe we'll have luck next time
                    logger.error("Selected disposed endpoint, continue");
                    continue;
                }

                String activeSpeakerId = newActiveSpeaker.getID();

                logger.info("New active speaker: "+activeSpeakerId);

                for(WeakReference<Endpoint> endpoint : endpointsCopy)
                {
                    Endpoint toNotify = endpoint.get();
                    if(toNotify == null)
                        continue;

                    SctpConnection sctpConnection
                        = toNotify.getSctpConnection();
                    if(sctpConnection == null)
                    {
                        logger.warn(
                            "No SCTP connection with " + toNotify.getID());
                        continue;
                    }

                    if(!sctpConnection.isReady())
                    {
                        logger.warn(
                            "SCTP connection with " + toNotify.getID()
                                + " not ready yet");
                        continue;
                    }

                    try
                    {
                        WebRtcDataStream dataStream
                            = sctpConnection.getDefaultDataStream();

                        if(dataStream == null)
                        {
                            logger.warn("WebRtc data channel not opened yet");
                            continue;
                        }

                        dataStream.sendString(
                            "activeSpeaker:"+activeSpeakerId
                        );
                    }
                    catch (IOException e)
                    {
                        logger.error("SCTP error", e);
                    }
                }
            }
        }

        public void expire()
        {
            synchronized (this)
            {
                run = false;
                this.notifyAll();
            }
            try
            {
                this.thread.join();
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
        }
    }
}
