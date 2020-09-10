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

import org.jitsi.utils.concurrent.*;
import org.jitsi.videobridge.stats.config.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * A class that manages the statistics. Periodically calls the
 * <tt>StatsGenerator</tt> of the statistics to receive the statistics and then
 * passes them to <tt>StatsTransport</tt> instances that sends them.
 *
 * @author Hristo Terezov
 * @author Lyubomir Marinov
 */
public class StatsManager
{
    public static StatsManagerBundleActivatorConfig config = new StatsManagerBundleActivatorConfig();
    /**
     * The <tt>Statistics</tt> added to this <tt>StatsManager</tt>.
     */
    private final List<StatisticsPeriodicRunnable> statistics
        = new CopyOnWriteArrayList<>();

    /**
     * The {@link RecurringRunnableExecutor} which periodically invokes
     * {@link Statistics#generate()} on {@link #statistics}.
     */
    private final RecurringRunnableExecutor statisticsExecutor
        = new RecurringRunnableExecutor(
                StatsManager.class.getSimpleName() + "-statisticsExecutor");

    /**
     * The {@link RecurringRunnableExecutor} which periodically invokes
     * {@link StatsTransport#publishStatistics(Statistics, long)} on
     * {@link #transports}.
     */
    private final RecurringRunnableExecutor transportExecutor
        = new RecurringRunnableExecutor(
                StatsManager.class.getSimpleName() + "-transportExecutor");

    /**
     * The <tt>StatsTransport</tt>s added to this <tt>StatsManager</tt> to
     * transport {@link #statistics}.
     */
    private final List<TransportPeriodicRunnable> transports
        = new CopyOnWriteArrayList<>();

    /**
     * Adds a specific (set of) <tt>Statistics</tt> to be periodically
     * generated/updated by this <tt>StatsManager</tt>.
     * <p>
     * Warning: {@code Statistics} added to this {@code StatsManager} after
     * {@link #start()} has been invoked will not be updated.
     * </p>
     *
     * @param statistics the (set of) <tt>Statistics</tT> to be repeatedly
     * generated/updated by this <tt>StatsManager</tt> at the specified
     * <tt>period</tt>
     * @param period the internal/period in milliseconds at which the specified
     * <tt>statistics</tt> is to be generated/updated by this
     * <tt>StatsManager</tt>
     */
    public void addStatistics(Statistics statistics, long period)
    {
        if (statistics == null)
            throw new NullPointerException("statistics");
        if (period < 1)
            throw new IllegalArgumentException("period " + period);

        // XXX The field statistics is a CopyOnWriteArrayList in order to avoid
        // synchronization here.
        this.statistics.add(
                new StatisticsPeriodicRunnable(statistics, period));
    }

    /**
     * Adds a specific <tt>StatsTransport</tt> through which this
     * <tt>StatsManager</tt> is to periodically send the <tt>Statistics</tt>
     * added to it.
     * <p>
     * Warning: {@code StatsTransport}s added to this {@code StatsManager}
     * after {@link #start()} has been invoked will not be called.
     * </p>
     *
     * @param transport the <tt>StatsTransport</tt> to add to this
     * <tt>StatsManager</tt> so that the latter periodically sends the
     * <tt>Statistics</tt> added through the former
     * @param period the internal/period in milliseconds at which this
     * <tt>StatsManager</tt> is to repeatedly send the <tt>Statistics</tt> added
     * to it through the specified <tt>transport</tt>
     */
    public void addTransport(StatsTransport transport, long period)
    {
        if (transport == null)
            throw new NullPointerException("transport");
        if (period < 1)
            throw new IllegalArgumentException("period " + period);

        // XXX The field transport is a CopyOnWriteArrayList in order to avoid
        // synchronization here.
        transports.add(new TransportPeriodicRunnable(transport, period));
    }

    /**
     * Finds the first instance of {@code Statistics} with a specific runtime
     * type generated/updated at a specific interval/period.
     *
     * @param clazz the runtime type of the {@code Statistics} to be found
     * @param period the internal/period in milliseconds at which the
     * {@code Statistics} to be found is generated/updated by this
     * {@code StatsManager}
     * @return the first instance of {@code Statistics} with runtime type
     * {@code clazz} generated/updated every {@code period} milliseconds if any;
     * otherwise, {@code null}
     */
    public <T extends Statistics> T findStatistics(Class<T> clazz, long period)
    {
        // XXX The field statistics is a CopyOnWriteArrayList in order to avoid
        // synchronization here.
        for (StatisticsPeriodicRunnable spp : statistics)
        {
            if (spp.getPeriod() == period && clazz.isInstance(spp.o))
            {
                @SuppressWarnings("unchecked")
                T t = (T) spp.o;

                return t;
            }
        }
        return null;
    }

    /**
     * Gets the (sets of) <tt>Statistics</tt> which this <tt>StatsManager</tt>
     * periodically generates/updates.
     *
     * @return a <tt>Collection</tt> of the (sets of) <tt>Statistics</tt> which
     * this <tt>StatsManager</tt> periodically generates/updates
     */
    public Collection<Statistics> getStatistics()
    {
        // XXX The field statistics is a CopyOnWriteArrayList in order to avoid
        // synchronization here.

        // XXX The local variable count is an optimization effort and the
        // execution should be fine if the value is not precise.
        int count = statistics.size();
        Collection<Statistics> ret;

        if (count < 1)
        {
            ret = Collections.emptyList();
        }
        else
        {
            ret = new ArrayList<>(count);
            for (StatisticsPeriodicRunnable spp : statistics)
                ret.add(spp.o);
        }
        return ret;
    }

    /**
     * Gets the number of (sets of) <tt>Statistics</tt> which this
     * <tt>StatsManager</tt> periodically generates/updates.
     *
     * @return the number of (sets of) <tt>Statistics</tt> which this
     * <tt>StatsManager</tt> periodically generates/updates
     */
    public int getStatisticsCount()
    {
        // XXX The field statistics is a CopyOnWriteArrayList in order to avoid
        // synchronization here.
        return statistics.size();
    }

    /**
     * Gets the <tt>StatTransport</tt>s through which this <tt>StatsManager</tt>
     * periodically sends the (sets of) <tt>Statistics</tt> that it
     * generates/updates.
     *
     * @return a <tt>Collection</tt> of the <tt>StatTransport</tt>s through
     * which this <tt>StatsManager</tt> periodically sends the (sets of)
     * <tt>Statistics</tt> that it generates/updates
     */
    public Collection<StatsTransport> getTransports()
    {
        // XXX The field transports is a CopyOnWriteArrayList in order to avoid
        // synchronization here.

        // XXX The local variable count is an optimization effort and the
        // execution should be fine if the value is not precise.
        int count = transports.size();
        Collection<StatsTransport> ret;

        if (count < 1)
        {
            ret = Collections.emptyList();
        }
        else
        {
            ret = new ArrayList<>(count);
            for (TransportPeriodicRunnable tpp : transports)
                ret.add(tpp.o);
        }
        return ret;
    }

    /**
     * {@inheritDoc}
     *
     * Starts the {@code StatsTransport}s added to this {@code StatsManager} in
     * the specified {@code bundleContext}. Commences the generation of the
     * {@code Statistics} added to this {@code StatsManager}.
     * <p>
     * Warning: {@code Statistics} and {@code StatsTransport}s added by way of
     * {@link #addStatistics(Statistics, long)} and
     * {@link #addTransport(StatsTransport, long)} after the current method
     * invocation will not be started.
     * </p>
     */
    public void start()
    {
        // Register statistics and transports with their respective
        // RecurringRunnableExecutor in order to have them periodically
        // executed.
        for (StatisticsPeriodicRunnable spp : statistics)
        {
            statisticsExecutor.registerRecurringRunnable(spp);
        }
        // Start the StatTransports added to this StatsManager in the specified
        // bundleContext.
        for (TransportPeriodicRunnable tpp : transports)
        {
            transportExecutor.registerRecurringRunnable(tpp);
        }
    }

    /**
     * {@inheritDoc}
     *
     * Stops the <tt>StatsTransport</tt>s added to this <tt>StatsManager</tt> in
     * the specified <tt>bundleContext</tt>.
     */
    public void stop()
    {
        // De-register statistics and transports from their respective
        // RecurringRunnableExecutor in order to have them no longer
        // periodically executed.
        for (StatisticsPeriodicRunnable spp : statistics)
        {
            statisticsExecutor.deRegisterRecurringRunnable(spp);
        }
        // Stop the StatTransports added to this StatsManager in the specified
        // bundleContext.
        for (TransportPeriodicRunnable tpp : transports)
        {
            transportExecutor.deRegisterRecurringRunnable(tpp);
        }
    }

    /**
     * Implements a {@link RecurringRunnable} which periodically generates a
     * specific (set of) {@link Statistics}.
     */
    private static class StatisticsPeriodicRunnable
        extends PeriodicRunnableWithObject<Statistics>
    {
        /**
         * Initializes a new {@code StatisticsPeriodicRunnable} instance
         * which is to {@code period}ically generate {@code statistics}.
         *
         * @param statistics the {@code Statistics} to be {@code period}ically
         * generated by the new instance
         * @param period the time in milliseconds between consecutive
         * generations of {@code statistics}
         */
        public StatisticsPeriodicRunnable(
                Statistics statistics,
                long period)
        {
            super(statistics, period);
        }

        /**
         * {@inheritDoc}
         *
         * Invokes {@link Statistics#generate()} on {@link #o}.
         */
        @Override
        protected void doRun()
        {
            o.generate();
        }
    }

    /**
     * Implements a {@link RecurringRunnable} which periodically publishes
     * {@link #statistics} through a specific {@link StatsTransport}.
     */
    private class TransportPeriodicRunnable
        extends PeriodicRunnableWithObject<StatsTransport>
    {
        /**
         * Initializes a new {@code StatisticsPeriodicRunnable} instance
         * which is to {@code period}ically generate {@code statistics}.
         *
         * @param transport the {@code StatsTransport} to {@code period}ically
         * publish {@link #statistics} to
         * @param period the time in milliseconds between consecutive
         * invocations of {@code publishStatistics()} on {@code transport}
         */
        public TransportPeriodicRunnable(
                StatsTransport transport,
                long period)
        {
            super(transport, period);
        }

        /**
         * {@inheritDoc}
         *
         * Invokes {@link StatsTransport#publishStatistics(Statistics, long)} on
         * {@link #o}.
         */
        protected void doRun()
        {
            long transportPeriod = getPeriod();

            // XXX The field statistics is a CopyOnWriteArrayList in order to
            // avoid synchronization here.
            for (StatisticsPeriodicRunnable spp
                    : StatsManager.this.statistics)
            {
                // A Statistics instance is associated with a period and a
                // StatsTransport is associated with a period. Match the two
                // periods.
                long statisticsPeriod = spp.getPeriod();

                if (transportPeriod == statisticsPeriod)
                {
                    // FIXME measurementInterval was meant to be the actual
                    // interval of time that the information of the Statistics
                    // covers. In contrast, statisticsPeriod is the intended
                    // interval. However, it became difficult after a
                    // refactoring to calculate measurementInterval.
                    long measurementInterval = statisticsPeriod;

                    o.publishStatistics(spp.o, measurementInterval);
                }
            }
        }
    }
}
