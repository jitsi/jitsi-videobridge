/*
 * Jitsi, the OpenSource Java VoIP and Instant Messaging client.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.stats;

import java.util.*;

/**
 * Abstract class that defines common interface for a collection of statistics.
 *
 * @author Hristo Terezov
 */
public abstract class Statistics
{
    /**
     * Map of the names of the statistics and their values.
     */
    protected Map<String, Object> stats;

    /**
     * Returns the value of the statistic.
     * @param stat the name of the statistic.
     * @return the value.
     */
    public Object getStat(String stat)
    {
        return stats.get(stat);
    }

    /**
     * Returns the map with the names of the statistics and their values.
     * @return the map with the names of the statistics and their values.
     */
    public Map<String, Object> getStats()
    {
        return Collections.unmodifiableMap(stats);
    }

    /**
     * Returns the supported statistics.
     * @return the supported statistics
     */
    public Set<String> getSupportedStats()
    {
        return stats.keySet();
    }

    /**
     * Checks whether a statistics is supported or not.
     * @param stat the statistic
     * @return <tt>true</tt> if the statistic is supported.
     */
    public boolean isSupported(String stat)
    {
        return stats.containsKey(stat);
    }

    /**
     * Sets the value of statistic
     * @param stat the name of the statistic
     * @param value the value of the statistic
     */
    public void setStat(String stat, Object value)
    {
        if(!isSupported(stat))
        {
            throw new IllegalArgumentException(
                    "The statistic is not supported");
        }
        stats.put(stat, value);
    }

    @Override
    public String toString()
    {
        StringBuilder s = new StringBuilder();

        for(String key : stats.keySet())
            s.append(key).append(" : ").append(stats.get(key)).append("\n");

        return s.toString();
    }
}
