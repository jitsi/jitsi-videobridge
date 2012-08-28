/*
 * Jitsi VideoBridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge;

import java.util.*;

import javax.media.*;
import javax.media.protocol.*;

import net.java.sip.communicator.impl.neomedia.*;
import net.java.sip.communicator.impl.neomedia.format.*;
import net.java.sip.communicator.service.neomedia.*;
import net.java.sip.communicator.service.neomedia.format.*;

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
     * The <tt>DataSource</tt> which is used by all <tt>Channel</tt>s created by
     * this instance to make a call to
     * {@link StreamRTPManager#createSendStream(DataSource, int)} in order to
     * have <tt>OutputDataStream</tt>s to send RTP and RTCP through.
     */
    private final PushBufferDataSource dataSource;

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

        /*
         * Jitsi VideoBridge is a relay so it does not capture media. But
         * RTPManager.createSendStream(DataSource,int) must be called to make
         * RTPManager create an OutputDataStream i.e. to send packets to through
         * the associated Channel. Create a minimal DataSource which will not
         * capture/push any media and will go through the method call. The
         * stream it provides must have a Format and the Format have a payload
         * type number assigned in RTPManager. The specifics of the Format do
         * not matter to RTPTranslatorImpl.
         */
        MediaFormat mediaFormat
            = MediaType.AUDIO.toString().equals(this.name)
                ? MediaUtils.getMediaFormat("PCMU", 8000)
                : MediaUtils.getMediaFormat("H264", 90000);
        @SuppressWarnings("unchecked")
        Format format
            = ((MediaFormatImpl<? extends Format>) mediaFormat).getFormat();

        this.dataSource = new PushBufferDataSourceImpl(format);

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
     * Gets the <tt>DataSource</tt> which is used by all <tt>Channel</tt>s
     * created by this instance to make a call to
     * {@link StreamRTPManager#createSendStream(DataSource, int)} in order to
     * have <tt>OutputDataStream</tt>s to send RTP and RTCP through.
     *
     * @return the <tt>DataSource</tt> which is used by all <tt>Channel</tt>s
     * created by this instance to make a call to
     * <tt>StreamRTPManager.createSendStream(DataSource, int)</tt> in order to
     * have <tt>OutputDataStream</tt>s to send RTP and RTCP through.
     */
    public PushBufferDataSource getDataSource()
    {
        return dataSource;
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
