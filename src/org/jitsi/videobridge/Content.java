/*
 * Jitsi VideoBridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge;

import java.util.*;

import org.jitsi.impl.neomedia.*;
import org.jitsi.service.neomedia.*;

/**
 * Represents a content in the terms of Jitsi VideoBridge.
 *
 * @author Lyubomir Marinov
 */
public class Content
{
    /**
     * The <tt>Channel</tt>s of this <tt>Content</tt> mapped by their IDs.
     */
    private final Map<String, Channel> channels
        = new HashMap<String, Channel>();

    /**
     * The <tt>Conference</tt> which has initialized this <tt>Content</tt>.
     */
    private final Conference conference;

    /**
     * The indicator which determines whether {@link #expire()} has been called
     * on this <tt>Content</tt>.
     */
    private boolean expired = false;

    /**
     * The time in milliseconds of the last activity related to this
     * <tt>Content</tt>. In the time interval between the last activity and now,
     * this <tt>Content</tt> is considered inactive.
     */
    private long lastActivityTime;

    /**
     * The <tt>MediaType</tt> of this <tt>Content</tt>. The implementation
     * detects the <tt>MediaType</tt> by looking at the {@link #name} of this
     * instance.
     */
    private final MediaType mediaType;

    /**
     * The name of this <tt>Content</tt>.
     */
    private final String name;

    /**
     * The <tt>RTPTranslator</tt> which forwards the RTP and RTCP traffic
     * between {@link #channels}.
     */
    private final RTPTranslator rtpTranslator = new RTPTranslatorImpl();

    public Content(Conference conference, String name)
    {
        if (conference == null)
            throw new NullPointerException("conference");
        if (name == null)
            throw new NullPointerException("name");

        this.conference = conference;
        this.name = name;

        mediaType = MediaType.parseString(this.name);

        touch();
    }

    public Channel createChannel()
        throws Exception
    {
        Channel channel = null;

        while (channel == null)
        {
            String id = generateChannelID();

            synchronized (channels)
            {
                if (!channels.containsKey(id))
                {
                    channel = new Channel(this, id);
                    channels.put(id, channel);
                }
            }
        }

        return channel;
    }

    /**
     * Expires this <tt>Content</tt>.
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

        try
        {
            getConference().expireContent(this);
        }
        finally
        {
            // Expire the Channels of this Content.
            for (Channel channel : getChannels())
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

            getRTPTranslator().dispose();
        }
    }

    /**
     * Expires a specific <tt>Channel</tt> of this <tt>Content</tt> (i.e. if the
     * specified <tt>channel</tt> is not in the list of <tt>Channel</tt>s of
     * this <tt>Content</tt>, does nothing).
     *
     * @param channel the <tt>Channel</tt> to be expired by this
     * <tt>Content</tt>
     */
    public void expireChannel(Channel channel)
    {
        String id = channel.getID();
        boolean expireChannel;

        synchronized (channels)
        {
            if (channel.equals(channels.get(id)))
            {
                channels.remove(id);
                expireChannel = true;
            }
            else
                expireChannel = false;
        }
        if (expireChannel)
            channel.expire();
    }

    /**
     * Generates a new <tt>Channel</tt> ID which is not guaranteed to be unique.
     *
     * @return a new <tt>Channel</tt> ID which is not guaranteed to be unique
     */
    private String generateChannelID()
    {
        return
            Long.toHexString(
                    System.currentTimeMillis() + VideoBridge.RANDOM.nextLong());
    }

    public Channel getChannel(String id)
    {
        Channel channel;

        synchronized (channels)
        {
            channel = channels.get(id);
        }

        // It seems the channel is still active.
        if (channel != null)
            channel.touch();

        return channel;
    }

    /**
     * Gets the <tt>Channel</tt>s of this <tt>Content</tt>.
     *
     * @return the <tt>Channel</tt>s of this <tt>Content</tt>
     */
    public Channel[] getChannels()
    {
        synchronized (channels)
        {
            Collection<Channel> values = channels.values();

            return values.toArray(new Channel[values.size()]);
        }
    }

    /**
     * Gets the <tt>Conference</tt> which has initialized this <tt>Content</tt>.
     *
     * @return the <tt>Conference</tt> which has initialized this
     * <tt>Content</tt>
     */
    public final Conference getConference()
    {
        return conference;
    }

    /**
     * Gets the time in milliseconds of the last activity related to this
     * <tt>Content</tt>.
     *
     * @return the time in milliseconds of the last activity related to this
     * <tt>Content</tt>
     */
    public long getLastActivityTime()
    {
        synchronized (this)
        {
            return lastActivityTime;
        }
    }

    /**
     * Gets the <tt>MediaType</tt> of this <tt>Content</tt>. The implementation
     * detects the <tt>MediaType</tt> by looking at the <tt>name</tt> of this
     * instance.
     *
     * @return the <tt>MediaType</tt> of this <tt>Content</tt>
     */
    public MediaType getMediaType()
    {
        return mediaType;
    }

    public final String getName()
    {
        return name;
    }

    /**
     * Gets the <tt>RTPTranslator</tt> which forwards the RTP and RTCP traffic
     * between the <tt>Channel</tt>s of this <tt>Content</tt>.
     *
     * @return the <tt>RTPTranslator</tt> which forwards the RTP and RTCP
     * traffic between the <tt>Channel</tt>s of this <tt>Content</tt>
     */
    public RTPTranslator getRTPTranslator()
    {
        return rtpTranslator;
    }

    /**
     * Sets the time in milliseconds of the last activity related to this
     * <tt>Content</tt> to the current system time.
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
}
