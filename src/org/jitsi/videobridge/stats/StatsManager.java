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
    private final List<TimeInfo<Statistics>> statistics
        = new LinkedList<TimeInfo<Statistics>>();

    private Thread thread;

    private final List<TimeInfo<StatsTransport>> transports
        = new LinkedList<TimeInfo<StatsTransport>>();

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

    public int getStatisticsCount()
    {
        synchronized (getSyncRoot())
        {
            return statistics.size();
        }
    }

    private Object getSyncRoot()
    {
        return this;
    }

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
     * Runs in {@link #thread}.
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

    private void stopThread()
    {
        Object syncRoot = getSyncRoot();

        synchronized (syncRoot)
        {
            if ((getBundleContext() == null) || (getStatisticsCount() < 1))
            {
                thread = null;
                syncRoot.notify();
            }
        }
    }

    private static class TimeInfo<T>
    {
        public long lastInvocationTime = System.currentTimeMillis();

        public T o;

        public final long period;

        public TimeInfo(T o, long period)
        {
            this.o = o;
            this.period = period;
        }
    }
}
