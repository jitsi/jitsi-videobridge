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

import org.jitsi.service.neomedia.*;
import org.jitsi.util.*;
import org.jitsi.util.event.*;

/**
 * Represents an endpoint of a participant in a <tt>Conference</tt>.
 *
 * @author Lyubomir Marinov
 * @author Boris Grozev
 * @author Pawel Domas
 */
public class Endpoint
    extends PropertyChangeNotifier
{
    /**
     * The name of the <tt>Endpoint</tt> property <tt>channels</tt> which lists
     * the <tt>Channel</tt>s associated with the <tt>Endpoint</tt>.
     */
    public static final String CHANNELS_PROPERTY_NAME
        = Endpoint.class.getName() + ".channels";

    /**
     * The <tt>Logger</tt> used by the <tt>Endpoint</tt> class and its instances
     * to print debug information.
     */
    private static final Logger logger = Logger.getLogger(Endpoint.class);

    /**
     * The list of <tt>Channel</tt>s associated with this <tt>Endpoint</tt>.
     */
    private final List<WeakReference<RtpChannel>> channels
        = new LinkedList<WeakReference<RtpChannel>>();

    /**
     * SCTP connection bound to this endpoint.
     */
    private WeakReference<SctpConnection> sctpConnection
        = new WeakReference<SctpConnection>(null);

    /**
     * The (unique) identifier/ID of the endpoint of a participant in a
     * <tt>Conference</tt>.
     */
    private final String id;

    /**
     * Initializes a new <tt>Endpoint</tt> instance with a specific (unique)
     * identifier/ID of the endpoint of a participant in a <tt>Conference</tt>.
     *
     * @param id the identifier/ID of the endpoint of a participant in a
     * <tt>Conference</tt> with which the new instance is to be initialized
     */
    public Endpoint(String id)
    {
        if (id == null)
            throw new NullPointerException("id");

        this.id = id;
    }

    /**
     * Adds a specific <tt>Channel</tt> to the list of <tt>Channel</tt>s
     * associated with this <tt>Endpoint</tt>.
     *
     * @param channel the <tt>Channel</tt> to add to the list of
     * <tt>Channel</tt>s associated with this <tt>Endpoint</tt>
     * @return <tt>true</tt> if the list of <tt>Channel</tt>s associated with
     * this <tt>Endpoint</tt> changed as a result of the method invocation;
     * otherwise, <tt>false</tt>
     */
    public boolean addChannel(RtpChannel channel)
    {
        if (channel == null)
            throw new NullPointerException("channel");
        /*
         * The expire state of Channel is final. Adding an expired Channel to
         * an Endpoint is a no-op.
         */
        if (channel.isExpired())
            return false;

        boolean added = false;
        boolean removed = false;

        synchronized (channels)
        {
            boolean add = true;

            for (Iterator<WeakReference<RtpChannel>> i = channels.iterator();
                    i.hasNext();)
            {
                RtpChannel c = i.next().get();

                if (c == null)
                {
                    i.remove();
                    removed = true;
                }
                else if (c.equals(channel))
                {
                    add = false;
                }
                else if (c.isExpired())
                {
                    i.remove();
                    removed = true;
                }
            }
            if (add)
            {
                channels.add(new WeakReference<RtpChannel>(channel));
                added = true;
            }
        }

        if (added || removed)
            firePropertyChange(CHANNELS_PROPERTY_NAME, null, null);

        return added;
    }

    /**
     * Notifies this <tt>Endpoint</tt> that an associated <tt>Channel</tt> has
     * received or measured a new audio level for a specific (contributing)
     * synchronization source identifier/SSRC.
     *
     * @param channel the <tt>Channel</tt> which has received or measured the
     * specified <tt>audioLevel</tt> for the specified <tt>ssrc</tt>
     * @param ssrc the synchronization source identifier/SSRC of the RTP stream
     * received within the specified <tt>channel</tt> for which the specified
     * <tt>audioLevel</tt> was received or measured
     * @param audioLevel the audio level which was received or measured for the
     * specified <tt>ssrc</tt> received within the specified <tt>channel</tt>
     */
    void audioLevelChanged(Channel channel, long ssrc, int audioLevel)
    {
        // TODO Auto-generated method stub
    }

    /**
     * Gets a <tt>List</tt> with the channels of this <tt>Endpoint</tt> with
     * a particular <tt>MediaType</tt>.
     *
     * @param mediaType the <tt>MediaType</tt>.
     * @return a <tt>List</tt> with the channels of this <tt>Endpoint</tt> with
     * a particular <tt>MediaType</tt>.
     */
    public List<RtpChannel> getChannels(MediaType mediaType)
    {
        List<RtpChannel> channels = new LinkedList<RtpChannel>();

        synchronized (this.channels)
        {
            for (WeakReference<RtpChannel> channel1 : this.channels)
            {
                RtpChannel channel = channel1.get();

                if (channel == null)
                    continue;

                if ((mediaType == null)
                    || (mediaType.equals(
                    channel.getContent().getMediaType())))
                {
                    channels.add(channel);
                }
            }
        }
        return channels;
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
     * Removes a specific <tt>Channel</tt> from the list of <tt>Channel</tt>s
     * associated with this <tt>Endpoint</tt>.
     *
     * @param channel the <tt>Channel</tt> to remove from the list of
     * <tt>Channel</tt>s associated with this <tt>Endpoint</tt>
     * @return <tt>true</tt> if the list of <tt>Channel</tt>s associated with
     * this <tt>Endpoint</tt> changed as a result of the method invocation;
     * otherwise, <tt>false</tt>
     */
    public boolean removeChannel(RtpChannel channel)
    {
        if (channel == null)
            return false;

        boolean removed = false;

        synchronized (channels)
        {
            for (Iterator<WeakReference<RtpChannel>> i = channels.iterator();
                    i.hasNext();)
            {
                Channel c = i.next().get();

                if ((c == null) || c.equals(channel) || c.isExpired())
                {
                    i.remove();
                    removed = true;
                }
            }
        }

        if (removed)
            firePropertyChange(CHANNELS_PROPERTY_NAME, null, null);

        return removed;
    }

    /**
     * Sends a specific <tt>String</tt> <tt>msg</tt> over the data channel of
     * this <tt>Endpoint</tt>.
     *
     * @param msg message text to send.
     */
    public void sendMessageOnDataChannel(String msg)
    {
        SctpConnection sctpConnection = getSctpConnection();
        String endpointId = getID();

        if(sctpConnection == null)
        {
            logger.warn("No SCTP connection with " + endpointId);
        }
        else if(sctpConnection.isReady())
        {
            try
            {
                WebRtcDataStream dataStream
                    = sctpConnection.getDefaultDataStream();

                if(dataStream == null)
                {
                    logger.warn(
                            "WebRtc data channel not opened yet " + endpointId);
                }
                else
                {
                    dataStream.sendString(msg);
                }
            }
            catch (IOException e)
            {
                logger.error("SCTP error, endpoint: " + endpointId, e);
            }
        }
        else
        {
            logger.warn(
                    "SCTP connection with " + endpointId + " not ready yet");
        }
    }

    /**
     * Sets the <tt>SctpConnection</tt> associated with this <tt>Endpoint</tt>.
     * @param sctpConnection the <tt>SctpConnection</tt> that will be bound to
     *                       this <tt>Endpoint</tt>.
     */
    public void setSctpConnection(SctpConnection sctpConnection)
    {
        this.sctpConnection
            = new WeakReference<SctpConnection>(sctpConnection);
    }

    /**
     * Returns an <tt>SctpConnection</tt> bound to this <tt>Endpoint</tt>.
     * @return an <tt>SctpConnection</tt> bound to this <tt>Endpoint</tt>
     *         or <tt>null</tt> otherwise.
     */
    public SctpConnection getSctpConnection()
    {
        return this.sctpConnection.get();
    }
}
