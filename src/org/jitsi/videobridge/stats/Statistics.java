/*
 * Jitsi, the OpenSource Java VoIP and Instant Messaging client.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.stats;

import java.util.*;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;

/**
 * Abstract class that defines common interface for a collection of statistics.
 *
 * @author Hristo Terezov
 * @author Lyubomir Marinov
 */
public abstract class Statistics
{
    /**
     * Formats statistics in <tt>ColibriStatsExtension</tt> object
     * @param statistics the statistics instance
     * @return the <tt>ColibriStatsExtension</tt> instance.
     */
    public static ColibriStatsExtension toXMPP(Statistics statistics)
    {
        ColibriStatsExtension ext = new ColibriStatsExtension();

        for (Map.Entry<String,Object> e : statistics.getStats().entrySet())
        {
            ext.addStat(
                    new ColibriStatsExtension.Stat(e.getKey(), e.getValue()));
        }
        return ext;
    }

    /**
     * Map of the names of the statistics and their values.
     */
    private final Map<String,Object> stats = new HashMap<String,Object>();

    /**
     * Generates/updates the statistics represented by this instance.
     */
    public abstract void generate();

    /**
     * Returns the value of the statistic.
     * @param stat the name of the statistic.
     * @return the value.
     */
    public synchronized Object getStat(String stat)
    {
        return stats.get(stat);
    }

    /**
     * Returns the map with the names of the statistics and their values.
     * @return the map with the names of the statistics and their values.
     */
    public synchronized Map<String,Object> getStats()
    {
        return new HashMap<String,Object>(stats);
    }

    /**
     * Sets the value of statistic
     * @param stat the name of the statistic
     * @param value the value of the statistic
     */
    public synchronized void setStat(String stat, Object value)
    {
        if (value == null)
            stats.remove(stat);
        else
            stats.put(stat, value);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString()
    {
        StringBuilder s = new StringBuilder();

        for(Map.Entry<String,Object> e : getStats().entrySet())
        {
            s.append(e.getKey()).append(":").append(e.getValue()).append("\n");
        }
        return s.toString();
    }
}
