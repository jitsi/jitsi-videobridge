/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge;

import java.lang.ref.*;
import java.util.*;

/**
 * Represents an endpoint of a participant in a <tt>Conference</tt>.
 *
 * @author Lyubomir Marinov
 */
public class Endpoint
{
    /**
     * The list of <tt>Channel</tt>s associated with this <tt>Endpoint</tt>.
     */
    private final List<WeakReference<Channel>> channels
        = new LinkedList<WeakReference<Channel>>();

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
    public boolean addChannel(Channel channel)
    {
        if (channel == null)
            throw new NullPointerException("channel");

        synchronized (channels)
        {
            for (Iterator<WeakReference<Channel>> i = channels.iterator();
                    i.hasNext();)
            {
                Channel c = i.next().get();

                if (c == null)
                    i.remove();
                else if (c.equals(channel))
                    return false;
            }

            return channels.add(new WeakReference<Channel>(channel));
        }
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
    public boolean removeChannel(Channel channel)
    {
        if (channel == null)
            return false;

        synchronized (channels)
        {
            for (Iterator<WeakReference<Channel>> i = channels.iterator();
                    i.hasNext();)
            {
                Channel c = i.next().get();

                if (c == null)
                {
                    i.remove();
                }
                else if (c.equals(channel))
                {
                    i.remove();
                    return true;
                }
            }
        }

        return false;
    }
}
