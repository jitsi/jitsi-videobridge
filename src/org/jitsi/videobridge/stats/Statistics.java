/*
 * Jitsi, the OpenSource Java VoIP and Instant Messaging client.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.stats;

import java.util.*;
import java.util.concurrent.locks.*;

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
     * The <tt>ReadWriteLock</tt> which synchronizes the access to and/or
     * modification of the state of this instance. Replaces
     * <tt>synchronized</tt> blocks in order to reduce the number of exclusive
     * locks and, therefore, the risks of superfluous waiting.
     */
    protected final ReadWriteLock lock = new ReentrantReadWriteLock();

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
     *
     * @param stat the name of the statistic.
     * @return the value.
     */
    public Object getStat(String stat)
    {
        Lock lock = this.lock.readLock();
        Object value;

        lock.lock();
        try
        {
            value = stats.get(stat);
        }
        finally
        {
            lock.unlock();
        }
        return value;
    }

    /**
     * Returns the map with the names of the statistics and their values.
     *
     * @return the map with the names of the statistics and their values.
     */
    public Map<String,Object> getStats()
    {
        Lock lock = this.lock.readLock();
        Map<String,Object> stats;

        lock.lock();
        try
        {
            stats = new HashMap<String,Object>(this.stats);
        }
        finally
        {
            lock.unlock();
        }
        return stats;
    }

    /**
     * Sets the value of statistic
     * @param stat the name of the statistic
     * @param value the value of the statistic
     */
    public void setStat(String stat, Object value)
    {
        Lock lock = this.lock.writeLock();

        lock.lock();
        try
        {
            unlockedSetStat(stat, value);
        }
        finally
        {
            lock.unlock();
        }
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

    /**
     * Sets the value of a specific piece of statistics. The method assumes that
     * the caller has acquired the write lock of {@link #lock} and, thus, allows
     * the optimization of batch updates to multiple pieces of statistics.
     *
     * @param stat the piece of statistics to set
     * @param value the value of the piece of statistics to set
     */
    protected void unlockedSetStat(String stat, Object value)
    {
        if (value == null)
            stats.remove(stat);
        else
            stats.put(stat, value);
    }
}
