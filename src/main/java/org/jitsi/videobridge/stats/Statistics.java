/*
 * Copyright @ 2015 - Present, 8x8 Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jitsi.videobridge.stats;

import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;

import org.jitsi.utils.logging.*;
import org.jitsi.xmpp.extensions.colibri.*;

/**
 * Abstract class that defines common interface for a collection of statistics.
 *
 * @author Hristo Terezov
 * @author Lyubomir Marinov
 */
public abstract class Statistics
{
    /**
     * The {@link Logger} used by the {@link Endpoint} class to print debug
     * information.
     */
    private static final Logger logger = Logger.getLogger(Statistics.class);

    /**
     * Formats statistics in <tt>ColibriStatsExtension</tt> object
     * @param statistics the statistics instance
     * @return the <tt>ColibriStatsExtension</tt> instance.
     */
    public static ColibriStatsExtension toXmppExtensionElement(
            Statistics statistics)
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
     * Formats statistics in <tt>ColibriStatsIQ</tt> object
     * @param statistics the statistics instance
     * @return the <tt>ColibriStatsIQ</tt> instance.
     */
    public static ColibriStatsIQ toXmppIq(Statistics statistics)
    {
        ColibriStatsIQ iq = new ColibriStatsIQ();
        for (Map.Entry<String,Object> e : statistics.getStats().entrySet())
        {
            iq.addStat(
                    new ColibriStatsExtension.Stat(e.getKey(), e.getValue()));
        }
        return iq;
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
    private final Map<String,Object> stats = new HashMap<>();

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
     * Gets the value of a specific piece of statistic as a {@code double}
     * value.
     *
     * @param stat the name of the piece of statistics to return
     * @return the value of {@code stat} as a {@code double} value
     */
    public double getStatAsDouble(String stat)
    {
        Object o = getStat(stat);
        double d;
        double defaultValue = 0.0d;

        if (o == null)
        {
            d = defaultValue;
        }
        else if (o instanceof Number)
        {
            d = ((Number) o).floatValue();
        }
        else
        {
            String s = o.toString();

            if (s == null || s.length() == 0)
            {
                d = defaultValue;
            }
            else
            {
                try
                {
                    d = Double.parseDouble(s);
                }
                catch (NumberFormatException nfe)
                {
                    d = defaultValue;
                }
            }
        }
        return d;
    }

    /**
     * Gets the value of a specific piece of statistic as a {@code float} value.
     *
     * @param stat the name of the piece of statistics to return
     * @return the value of {@code stat} as a {@code float} value
     */
    public float getStatAsFloat(String stat)
    {
        Object o = getStat(stat);
        float f;
        float defaultValue = 0.0f;

        if (o == null)
        {
            f = defaultValue;
        }
        else if (o instanceof Number)
        {
            f = ((Number) o).floatValue();
        }
        else
        {
            String s = o.toString();

            if (s == null || s.length() == 0)
            {
                f = defaultValue;
            }
            else
            {
                try
                {
                    f = Float.parseFloat(s);
                }
                catch (NumberFormatException nfe)
                {
                    f = defaultValue;
                }
            }
        }
        return f;
    }

    /**
     * Gets the value of a specific piece of statistic as an {@code int} value.
     *
     * @param stat the name of the piece of statistics to return
     * @return the value of {@code stat} as an {@code int} value
     */
    public int getStatAsInt(String stat)
    {
        Object o = getStat(stat);
        int i;
        int defaultValue = 0;

        if (o == null)
        {
            i = defaultValue;
        }
        else if (o instanceof Number)
        {
            i = ((Number) o).intValue();
        }
        else
        {
            String s = o.toString();

            if (s == null || s.length() == 0)
            {
                i = defaultValue;
            }
            else
            {
                try
                {
                    i = Integer.parseInt(s);
                }
                catch (NumberFormatException nfe)
                {
                    i = defaultValue;
                }
            }
        }
        return i;
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
            stats = new HashMap<>(this.stats);
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
        if (value instanceof AtomicLong || value instanceof AtomicInteger)
        {
            logger.warn(
                "Using an Atomic number as a stat, probably not what we want.");
        }
        if (value == null)
            stats.remove(stat);
        else
            stats.put(stat, value);
    }
}
