/*
 * Jitsi, the OpenSource Java VoIP and Instant Messaging client.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.stats.transport;

import java.util.*;

import org.jitsi.videobridge.stats.*;

/**
 * Defines an interface for classes that will send statistics.
 *
 * @author Hristo Terezov
 */
public abstract class StatsTransport
{
    /**
     * List of <tt>StatsTransportListener</tt>s
     */
    protected List<StatsTtransportListener> listeners
        = new LinkedList<StatsTtransportListener>();

    /**
     * Sends the given statistics.
     * @param stats the statistics to be sent.
     */
    public abstract void publishStatistics(Statistics stats);

    /**
     * Adds listener to the list.
     * @param l the listener
     */
    public void addStatsTransportListener(StatsTtransportListener l)
    {
        synchronized (listeners)
        {
            if(!listeners.contains(l))
            {
                listeners.add(l);
            }
        }
    }

    /**
     * Removes listener from the list.
     * @param l the listener.
     */
    public void removeStatsTransportListener(StatsTtransportListener l)
    {
        synchronized (listeners)
        {
            listeners.remove(l);
        }
    }

    /**
     * Fires event to the listeners.
     * @param event the event to be fired.
     */
    public void fireStatsTransportEvent(StatsTransportEvent event)
    {
        for(StatsTtransportListener l : listeners)
        {
            l.onStatsTransportEvent(event);
        }
    }

    /**
     * Inits the transport.
     */
    public abstract void init();

}