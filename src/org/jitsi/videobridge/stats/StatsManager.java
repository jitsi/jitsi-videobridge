/*
 * Jitsi, the OpenSource Java VoIP and Instant Messaging client.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.stats;

import java.util.*;

import org.osgi.framework.*;

/**
 * A class that manages the statistics. Periodically calls the
 * <tt>StatsGenerator</tt> of the statistics to receive the statistics and then
 * passes them to <tt>StatsTransport</tt> instances that sends them.
 *
 * @author Hristo Terezov
 * @author Lyubomir Marinov
 */
public class StatsManager
    extends BundleContextHolder2
{
    /**
     * The <tt>Statistics</tt> added to this <tt>StatsManager</tt>.
     */
    private final List<TimeInfo<Statistics>> statistics
        = new LinkedList<TimeInfo<Statistics>>();

    /**
     * The backgroud/daemon <tt>Thread</tt> in which this <tt>StatsManager</tt>
     * generates {@link #statistics} and sends them through {@link #transports}.
     */
    private Thread thread;

    /**
     * The <tt>StatsTransport</tt>s added to this <tt>StatsManager</tt> to
     * transport {@link #statistics}.
     */
    private final List<TimeInfo<StatsTransport>> transports
        = new LinkedList<TimeInfo<StatsTransport>>();

    /**
     * Adds a specific (set of) <tt>Statistics</tt> to be periodically
     * generated/updated by this <tt>StatsManager</tt>.
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

        synchronized (getSyncRoot())
        {
            this.statistics.add(new TimeInfo<Statistics>(statistics, period));
            startThread();
        }
    }

    /**
     * Adds a specific <tt>StatsTransport</tt> through which this
     * <tt>StatsManager</tt> is to periodically send the <tt>Statistics</tt>
     * added to it.
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

        synchronized (getSyncRoot())
        {
            transports.add(new TimeInfo<StatsTransport>(transport, period));
        }
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
        Collection<Statistics> r;

        synchronized (getSyncRoot())
        {
            int count = statistics.size();

            if (count < 1)
            {
                r = Collections.emptyList();
            }
            else
            {
                r = new ArrayList<Statistics>(count);
                for (TimeInfo<Statistics> timeInfo : statistics)
                    r.add(timeInfo.o);
            }
        }
        return r;
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
        synchronized (getSyncRoot())
        {
            return statistics.size();
        }
    }

    /**
     * Gets the <tt>Object</tt> used by this instance for the purposes of
     * synchronization.
     *
     * @return the <tt>Object</tt> used by this instance for the purposes of
     * synchronization
     */
    private Object getSyncRoot()
    {
        return this;
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
        Collection<StatsTransport> r;

        synchronized (getSyncRoot())
        {
            int count = transports.size();

            if (count < 1)
            {
                r = Collections.emptyList();
            }
            else
            {
                r = new ArrayList<StatsTransport>(count);
                for (TimeInfo<StatsTransport> timeInfo : transports)
                    r.add(timeInfo.o);
            }
        }
        return r;
    }

    /**
     * Runs in {@link #thread} and periodically generates/updates
     * {@link #statistics} and/or sends them through {@link #transports}.
     */
    private void runInThread()
    {
        Object syncRoot = getSyncRoot();

        try
        {
            Thread currentThread = Thread.currentThread();
            /*
             * XXX The ArrayLists statistics and transports are allocated once
             * and are repeatedly filled and cleared in order to reduce the
             * effects of garbage collection which was observed to cause
             * noticeable delays.
             */
            ArrayList<TimeInfo<Statistics>> statistics
                = new ArrayList<TimeInfo<Statistics>>();
            ArrayList<TimeInfo<StatsTransport>> transports
                = new ArrayList<TimeInfo<StatsTransport>>();

            do
            {
                long timeout = 0;

                synchronized (syncRoot)
                {
                    if (!currentThread.equals(thread))
                        break;
                    if (getBundleContext() == null)
                        break;
                    if (this.statistics.isEmpty())
                        break;

                    long now = System.currentTimeMillis();

                    /*
                     * Determine which Statistics are to be generated now and
                     * how much time this Thread is to sleep until the earliest
                     * time to generate a Statistics.
                     */
                    for (TimeInfo<Statistics> timeInfo : this.statistics)
                    {
                        long elapsed = now - timeInfo.lastInvocationTime;

                        if (elapsed >= 0)
                        {
                            long aTimeout = timeInfo.period - elapsed;

                            if (aTimeout > 0)
                            {
                                if (timeout > aTimeout)
                                    timeout = aTimeout;
                            }
                            else
                            {
                                statistics.add(timeInfo);
                            }
                        }
                    }

                    /*
                     * Determine which StatsTransport are to publish Statistics
                     * now and how much time this Thread is to sleep until the
                     * earliest time to publish Statistics.
                     */
                    if (!this.transports.isEmpty())
                    {
                        for (TimeInfo<StatsTransport> timeInfo
                                : this.transports)
                        {
                            long elapsed = now - timeInfo.lastInvocationTime;

                            if (elapsed >= 0)
                            {
                                long aTimeout = timeInfo.period - elapsed;

                                if (aTimeout > 0)
                                {
                                    if (timeout > aTimeout)
                                        timeout = aTimeout;
                                }
                                else
                                {
                                    transports.add(timeInfo);
                                }
                            }
                        }
                    }

                    if ((statistics.isEmpty()) && (transports.isEmpty()))
                    {
                        if (timeout < 1)
                            timeout = 1;

                        /*
                         * The timeout to wait has been computed based on the
                         * current time at the beginning of the computation.
                         * Take into account the duration of the computation
                         * i.e. how much time has passed since the beginning of
                         * the computation.
                         */
                        long elapsed = System.currentTimeMillis() - now;

                        if (elapsed < 0)
                            elapsed = 0;
                        if ((elapsed == 0) || (elapsed < timeout))
                        {
                            try
                            {
                                syncRoot.wait(timeout - elapsed);
                            }
                            catch (InterruptedException ex)
                            {
                            }
                        }
                        continue;
                    }
                }

                /*
                 * Generate/update the (sets of) Statistics which are at or
                 * after their respective period.
                 */
                if (!statistics.isEmpty())
                {
                    for (TimeInfo<Statistics> timeInfo : statistics)
                    {
                        timeInfo.lastInvocationTime
                            = System.currentTimeMillis();
                        try
                        {
                            timeInfo.o.generate();
                        }
                        catch (Throwable t)
                        {
                            if (t instanceof InterruptedException)
                                Thread.currentThread().interrupt();
                            else if (t instanceof ThreadDeath)
                                throw (ThreadDeath) t;
                        }
                    }
                    statistics.clear();
                }
                /*
                 * Send the (sets of) Statistics through the StatTransports
                 * which are at or after their respective periods.
                 */
                if (!transports.isEmpty())
                {
                    Collection<Statistics> ss = getStatistics();

                    for (TimeInfo<StatsTransport> timeInfo : transports)
                    {
                        timeInfo.lastInvocationTime
                            = System.currentTimeMillis();

                        StatsTransport transport = timeInfo.o;

                        try
                        {
                            for (Statistics s : ss)
                                transport.publishStatistics(s);
                        }
                        catch (Throwable t)
                        {
                            if (t instanceof InterruptedException)
                                Thread.currentThread().interrupt();
                            else if (t instanceof ThreadDeath)
                                throw (ThreadDeath) t;
                        }
                    }
                    transports.clear();
                }
            }
            while (true);
        }
        finally
        {
            synchronized (syncRoot)
            {
                if (Thread.currentThread().equals(thread))
                {
                    thread = null;
                    syncRoot.notify();
                }
            }
        }
    }

    /**
     * {@inheritDoc}
     *
     * Starts the <tt>StatsTransport</tt>s added to this <tt>StatsManager</tt>
     * in the specified <tt>bundleContext</tt>.
     */
    @Override
    void start(BundleContext bundleContext)
        throws Exception
    {
        super.start(bundleContext);

        /*
         * Start the StatTransports added to this StatsManager in the specified
         * bundleContext.
         */
        for (StatsTransport transport : getTransports())
            transport.start(bundleContext);

        startThread();
    }

    /**
     * Starts {@link #thread} if it has not been started yet, this
     * <tt>StatsManager</tt> has been started in a <tt>BundleContext</tt> and
     * (sets of) <tt>Statistics</tt> have been added to this
     * <tt>StatsManager</tt>.
     */
    private void startThread()
    {
        Object syncRoot = getSyncRoot();

        synchronized (syncRoot)
        {
            if (this.thread == null)
            {
                if ((getBundleContext() != null) && (getStatisticsCount() > 0))
                {
                    Thread thread
                        = new Thread()
                        {
                            @Override
                            public void run()
                            {
                                StatsManager.this.runInThread();
                            }
                        };

                    thread.setDaemon(true);
                    thread.setName(StatsManager.class.getName());
                    this.thread = thread;

                    boolean started = false;

                    try
                    {
                        thread.start();
                        started = true;
                    }
                    finally
                    {
                        if (!started && thread.equals(this.thread))
                            this.thread = null;
                    }
                }
            }
            else
            {
                syncRoot.notify();
            }
        }
    }

    /**
     * {@inheritDoc}
     *
     * Stops the <tt>StatsTransport</tt>s added to this <tt>StatsManager</tt> in
     * the specified <tt>bundleContext</tt>.
     */
    @Override
    void stop(BundleContext bundleContext)
        throws Exception
    {
        super.stop(bundleContext);

        stopThread();

        /*
         * Stop the StatTransports added to this StatsManager in the specified
         * bundleContext.
         */
        for (StatsTransport transport : getTransports())
            transport.stop(bundleContext);
    }

    /**
     * Stops {@link #thread} if it has been started already and this
     * <tt>StatsManager</tt> has been stopped in the <tt>BundleContext</tt> in
     * which it was previously started or this <tt>StatsManager</tt> does not
     * have any (sets of) <tt>Statistics</tt> to periodically generate/update.
     */
    private void stopThread()
    {
        Object syncRoot = getSyncRoot();

        synchronized (syncRoot)
        {
            if ((thread != null)
                    && ((getBundleContext() == null)
                            || (getStatisticsCount() < 1)))
            {
                thread = null;
                syncRoot.notify();
            }
        }
    }

    /**
     * The internal information associated with <tt>Statistics</tt> or
     * <tt>StatsTransport</tt> by <tt>StatsManager</tt> in order to enable its
     * operational logic.
     *
     * @param <T> the class of <tt>Statistics</tt> or <tt>StatsTransport</tt>
     * for which internal <tt>StatsManager</tt> information is maintained
     *
     * @author Lyubomir Marinov
     */
    private static class TimeInfo<T>
    {
        /**
         * The last time in milliseconds at which {@link #o} was invoked.
         */
        public long lastInvocationTime = System.currentTimeMillis();

        /**
         * The <tt>Statistics</tt> or <tt>StatsTransport</tt> which is being
         * invoked by <tt>StatsManager</tt>.
         */
        public T o;

        /**
         * The interval/period in milliseconds at which {@link #o} is to be
         * invoked.
         */
        public final long period;

        /**
         * Initializes a new <tt>TimeInfo</tt> instance which is to maintain
         * internal information to enable <tt>StatsManager</tt> to repeatedly
         * invoke a specific <tt>Statistics</tt> or <tt>StatsTransport</tt> at a
         * specific internal/period in milliseconds.
         *
         * @param o the <tt>Statistics</tt> or <tt>StatsTransport</tt> to be
         * repeatedly invoked by <tt>StatsManager</tt> at the specified
         * <tt>period</tt>
         * @param period the internal/period in milliseconds at which
         * <tt>StatsManager</tt> is to invoke the specified <tt>o</tt>
         */
        public TimeInfo(T o, long period)
        {
            this.o = o;
            this.period = period;
        }
    }
}
